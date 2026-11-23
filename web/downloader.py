"""H-DO download engine: queue, persistence, yt-dlp integration."""
import json
import os
import re
import shutil
import sqlite3
import threading
import time
import uuid
from pathlib import Path

import yt_dlp
from yt_dlp.utils import DownloadCancelled, parse_bytes


def _default_download_dir() -> str:
    for p in ("/sdcard/Download", "/storage/emulated/0/Download"):  # Android / Termux
        if os.path.isdir(p):
            return os.path.join(p, "H-DO")
    return str(Path.home() / "Downloads" / "H-DO")


DEFAULTS = {
    "download_dir": _default_download_dir(),
    "max_concurrent": 2,
    "rate_limit": "",
    "proxy": "",
    "sub_langs": "ar,en",
    "quality": "1080",
    "container": "mp4",
    "audio_format": "mp3",
    "embed": True,
    "sponsorblock": False,
}

ACTIVE = ("downloading", "processing")
SPONSOR_CATS = {"sponsor", "selfpromo", "interaction"}


def sanitize(name: str, limit: int = 80) -> str:
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name or "").strip(" .")
    return name[:limit] or "playlist"


def friendly_error(msg: str) -> str:
    m = msg.lower()
    if "sign in to confirm" in m or "not a bot" in m:
        return "cookies_needed"
    if "login" in m or "private" in m or "log in" in m or "cookies" in m:
        return "cookies_needed"
    if "ffmpeg" in m and ("not found" in m or "not installed" in m):
        return "ffmpeg_missing"
    if "unsupported url" in m:
        return "unsupported"
    if "unable to download" in m or "urlopen error" in m or "timed out" in m:
        return "network"
    return ""


class Manager:
    def __init__(self, data_dir: str):
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.settings_path = self.data_dir / "settings.json"
        self.cookies_path = self.data_dir / "cookies.txt"
        self.lock = threading.RLock()
        self.cv = threading.Condition(self.lock)
        self.flags: dict[str, threading.Event] = {}
        self.settings = dict(DEFAULTS)
        if self.settings_path.exists():
            try:
                self.settings.update(json.loads(self.settings_path.read_text("utf-8")))
            except Exception:
                pass
        self.db = sqlite3.connect(self.data_dir / "jobs.db", check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, created REAL, data TEXT)")
        self.jobs: dict[str, dict] = {}
        for jid, data in self.db.execute("SELECT id, data FROM jobs"):
            j = json.loads(data)
            if j["status"] in ACTIVE:  # interrupted by restart -> resume
                j["status"] = "queued"
            self.jobs[jid] = j
        self.ffmpeg = bool(shutil.which("ffmpeg"))
        threading.Thread(target=self._scheduler, daemon=True).start()

    # ---------------------------------------------------------------- settings
    def save_settings(self, new: dict) -> dict:
        with self.lock:
            for k, v in new.items():
                if k in DEFAULTS:
                    self.settings[k] = v
            self.settings["max_concurrent"] = max(1, min(6, int(self.settings["max_concurrent"])))
            Path(self.settings["download_dir"]).mkdir(parents=True, exist_ok=True)
            self.settings_path.write_text(json.dumps(self.settings, ensure_ascii=False, indent=2), "utf-8")
            self.cv.notify_all()
            return dict(self.settings)

    # -------------------------------------------------------------------- jobs
    def _persist(self, j: dict):
        self.db.execute("INSERT OR REPLACE INTO jobs VALUES (?,?,?)", (j["id"], j["created"], json.dumps(j, ensure_ascii=False)))
        self.db.commit()

    def add(self, url: str, opts: dict, meta: dict | None = None, folder: str = "") -> dict:
        meta = meta or {}
        j = {
            "id": uuid.uuid4().hex[:12], "url": url, "opts": opts, "folder": folder,
            "title": meta.get("title") or url, "thumbnail": meta.get("thumbnail", ""),
            "uploader": meta.get("uploader", ""), "duration": meta.get("duration"),
            "status": "queued", "percent": 0.0, "speed": 0, "eta": None,
            "downloaded": 0, "total": 0, "filepath": "", "size": 0, "error": "", "hint": "",
            "created": time.time(), "finished": None,
        }
        with self.lock:
            self.jobs[j["id"]] = j
            self._persist(j)
            self.cv.notify_all()
        return j

    def find_duplicate(self, url: str, opts: dict) -> dict | None:
        key = (opts.get("mode"), opts.get("quality") if opts.get("mode") == "video" else opts.get("audio_format"))
        for j in self.jobs.values():
            o = j["opts"]
            k = (o.get("mode"), o.get("quality") if o.get("mode") == "video" else o.get("audio_format"))
            if j["url"] == url and k == key and j["status"] in ("done", "queued", "downloading", "processing"):
                if j["status"] != "done" or os.path.exists(j["filepath"] or ""):
                    return j
        return None

    def list(self) -> list[dict]:
        with self.lock:
            return sorted((dict(j) for j in self.jobs.values()), key=lambda j: j["created"], reverse=True)

    def get(self, jid: str) -> dict | None:
        return self.jobs.get(jid)

    def cancel(self, jid: str) -> bool:
        with self.lock:
            j = self.jobs.get(jid)
            if not j:
                return False
            if j["status"] == "queued":
                j["status"] = "canceled"
                j["finished"] = time.time()
                self._persist(j)
            elif j["status"] in ACTIVE and jid in self.flags:
                self.flags[jid].set()
            return True

    def retry(self, jid: str) -> bool:
        with self.lock:
            j = self.jobs.get(jid)
            if not j or j["status"] not in ("error", "canceled"):
                return False
            j.update(status="queued", error="", hint="", finished=None)
            self._persist(j)
            self.cv.notify_all()
            return True

    def remove(self, jid: str, delete_file: bool = False) -> bool:
        with self.lock:
            j = self.jobs.get(jid)
            if not j:
                return False
            if j["status"] in ACTIVE:
                self.cancel(jid)
                return False
            if delete_file and j["filepath"] and self._inside_download_dir(j["filepath"]):
                try:
                    os.remove(j["filepath"])
                except OSError:
                    pass
            del self.jobs[jid]
            self.db.execute("DELETE FROM jobs WHERE id=?", (jid,))
            self.db.commit()
            return True

    def clear_finished(self):
        with self.lock:
            for jid in [k for k, j in self.jobs.items() if j["status"] in ("done", "error", "canceled")]:
                self.remove(jid)

    def _inside_download_dir(self, path: str) -> bool:
        try:
            base = Path(self.settings["download_dir"]).resolve()
            return Path(path).resolve().is_relative_to(base)
        except Exception:
            return False

    # --------------------------------------------------------------- scheduler
    def _scheduler(self):
        while True:
            with self.cv:
                while True:
                    running = sum(1 for j in self.jobs.values() if j["status"] in ACTIVE)
                    pending = sorted((j for j in self.jobs.values() if j["status"] == "queued"), key=lambda j: j["created"])
                    if pending and running < self.settings["max_concurrent"]:
                        break
                    self.cv.wait(1.0)
                job = pending[0]
                job["status"] = "downloading"
                self.flags[job["id"]] = threading.Event()
            threading.Thread(target=self._run, args=(job,), daemon=True).start()

    # ---------------------------------------------------------------- download
    def ydl_options(self, job: dict, hooks: list, pp_hooks: list) -> dict:
        s, o = self.settings, job["opts"]
        base = Path(s["download_dir"])
        mode = o.get("mode", "video")
        sub = base / ("Audio" if mode == "audio" else "Video")
        tmpl = "%(title).150B.%(ext)s"
        if job.get("folder"):
            sub = sub / sanitize(job["folder"])
            tmpl = "%(title).150B.%(ext)s"
        opts = {
            "outtmpl": str(sub / tmpl), "quiet": True, "no_warnings": True, "noprogress": True,
            "noplaylist": True, "retries": 10, "fragment_retries": 10, "continuedl": True,
            "concurrent_fragment_downloads": 4, "windowsfilenames": True, "overwrites": False,
            "progress_hooks": hooks, "postprocessor_hooks": pp_hooks, "writethumbnail": False,
        }
        if s.get("rate_limit"):
            try:
                opts["ratelimit"] = parse_bytes(s["rate_limit"])
            except Exception:
                pass
        if s.get("proxy"):
            opts["proxy"] = s["proxy"]
        if self.cookies_path.exists():
            opts["cookiefile"] = str(self.cookies_path)
        pps = []
        ff, embed = self.ffmpeg, bool(o.get("embed", True))
        if mode == "audio":
            fmt = o.get("audio_format", "mp3")
            if ff:
                opts["format"] = "ba/b"
                pps.append({"key": "FFmpegExtractAudio", "preferredcodec": fmt, "preferredquality": "0" if fmt != "mp3" else "192"})
            else:
                opts["format"] = "ba[ext=m4a]/ba/b"
        else:
            q = str(o.get("quality", "best"))
            h = "" if q == "best" else f"[height<={int(q)}]"
            if ff:
                opts["format"] = f"bv*{h}+ba/b{h}/b"
                opts["merge_output_format"] = o.get("container", "mp4")
            else:
                opts["format"] = f"b{h}/b"
        if ff and embed:
            opts["writethumbnail"] = True
            pps.append({"key": "FFmpegMetadata", "add_chapters": True, "add_metadata": True})
            pps.append({"key": "EmbedThumbnail"})
        if o.get("subtitles"):
            opts["writesubtitles"] = True
            opts["writeautomaticsub"] = True
            opts["subtitleslangs"] = [x.strip() for x in s.get("sub_langs", "en").split(",") if x.strip()]
            if ff and mode == "video" and o.get("container", "mp4") in ("mp4", "mkv"):
                pps.append({"key": "FFmpegEmbedSubtitle"})
        if ff and o.get("sponsorblock") and ("youtube.com" in job["url"] or "youtu.be" in job["url"]):
            pps.insert(0, {"key": "SponsorBlock", "categories": SPONSOR_CATS, "api": "https://sponsor.ajay.app", "when": "after_filter"})
            pps.append({"key": "ModifyChapters", "remove_chapters_patterns": [], "remove_sponsor_segments": SPONSOR_CATS,
                        "remove_ranges": [], "sponsorblock_chapter_title": "[SponsorBlock]: %(category_names)l", "force_keyframes": False})
        opts["postprocessors"] = pps
        return opts

    def _run(self, job: dict):
        flag = self.flags[job["id"]]
        state = {"stage": 0, "streams": 1}

        def hook(d):
            if flag.is_set():
                raise DownloadCancelled()
            if d["status"] == "downloading":
                info = d.get("info_dict") or {}
                state["streams"] = max(1, len(info.get("requested_formats") or []))
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                done = d.get("downloaded_bytes") or 0
                p = (done / total) if total else 0
                job["percent"] = round(min(99.9, (state["stage"] + p) / state["streams"] * 100), 1)
                job["speed"] = d.get("speed") or 0
                job["eta"] = d.get("eta")
                job["downloaded"], job["total"] = done, total
            elif d["status"] == "finished":
                state["stage"] += 1

        def pp_hook(d):
            if d["status"] == "started":
                job["status"] = "processing"
                job["speed"], job["eta"] = 0, None

        try:
            with yt_dlp.YoutubeDL(self.ydl_options(job, [hook], [pp_hook])) as ydl:
                info = ydl.extract_info(job["url"], download=True)
                if info and info.get("entries"):
                    info = next((e for e in info["entries"] if e), info)
                if info:
                    job["title"] = info.get("title") or job["title"]
                    job["thumbnail"] = job["thumbnail"] or info.get("thumbnail") or ""
                    job["uploader"] = job["uploader"] or info.get("uploader") or ""
                    job["duration"] = job["duration"] or info.get("duration")
                    rd = info.get("requested_downloads") or []
                    path = (rd[0].get("filepath") if rd else None) or ydl.prepare_filename(info)
                    job["filepath"] = path
                    job["size"] = os.path.getsize(path) if os.path.exists(path) else 0
            job.update(status="done", percent=100.0, speed=0, eta=0)
        except DownloadCancelled:
            job.update(status="canceled", speed=0, eta=None)
        except Exception as e:  # noqa: BLE001
            msg = re.sub(r"\x1b\[[0-9;]*m", "", str(e))
            job.update(status="error", error=msg[:500], hint=friendly_error(msg), speed=0, eta=None)
        finally:
            if flag.is_set() and job["status"] == "done":
                pass
            job["finished"] = time.time()
            with self.lock:
                self.flags.pop(job["id"], None)
                self._persist(job)
                self.cv.notify_all()

    # -------------------------------------------------------------------- info
    def fetch_info(self, url: str, playlist: bool = False) -> dict:
        opts = {"quiet": True, "no_warnings": True, "skip_download": True, "noplaylist": not playlist,
                "extract_flat": "in_playlist", "socket_timeout": 20}
        if self.cookies_path.exists():
            opts["cookiefile"] = str(self.cookies_path)
        if self.settings.get("proxy"):
            opts["proxy"] = self.settings["proxy"]
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        if info.get("_type") == "playlist" or info.get("entries"):
            entries = []
            for e in info.get("entries") or []:
                if not e:
                    continue
                u = e.get("url") or e.get("webpage_url") or ""
                if u and not u.startswith("http") and e.get("id") and "youtube" in (e.get("ie_key") or "").lower():
                    u = f"https://www.youtube.com/watch?v={e['id']}"
                entries.append({"title": e.get("title") or u, "url": u, "duration": e.get("duration"),
                                "thumbnail": (e.get("thumbnails") or [{}])[-1].get("url", "") if e.get("thumbnails") else e.get("thumbnail", "")})
                if len(entries) >= 500:
                    break
            return {"type": "playlist", "title": info.get("title"), "uploader": info.get("uploader") or info.get("channel"),
                    "thumbnail": info.get("thumbnail") or (entries[0]["thumbnail"] if entries else ""),
                    "count": len(entries), "entries": entries}
        heights = sorted({f["height"] for f in info.get("formats") or [] if f.get("height") and f.get("vcodec") not in (None, "none")}, reverse=True)
        return {"type": "video", "title": info.get("title"), "uploader": info.get("uploader") or info.get("channel"),
                "thumbnail": info.get("thumbnail"), "duration": info.get("duration"), "heights": heights,
                "extractor": info.get("extractor_key"), "url": info.get("webpage_url") or url}
