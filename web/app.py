"""H-DO — video/audio downloader (YouTube, Facebook, 1000+ sites) powered by yt-dlp."""
import hashlib
import os
import subprocess
import sys
from importlib import metadata
from pathlib import Path

from flask import Flask, jsonify, request, send_file, send_from_directory

from downloader import Manager

APP_VERSION = "1.0.0"
DATA_DIR = os.environ.get("HDO_DATA", str(Path.home() / ".hdo"))
PASSWORD = os.environ.get("HDO_PASSWORD", "")
TOKEN = hashlib.sha256(("hdo:" + PASSWORD).encode()).hexdigest() if PASSWORD else ""

app = Flask(__name__, static_folder="static", static_url_path="/static")
app.config["MAX_CONTENT_LENGTH"] = 5 * 1024 * 1024
mgr = Manager(DATA_DIR)


def ytdlp_version() -> str:
    try:
        return metadata.version("yt-dlp")
    except Exception:
        return "?"


def err(msg: str, code: int = 400):
    return jsonify({"error": msg}), code


@app.before_request
def guard():
    if not PASSWORD or not request.path.startswith("/api/") or request.path == "/api/login":
        return None
    if request.cookies.get("hdo_auth") == TOKEN or request.headers.get("X-HDO-Token") == TOKEN:
        return None
    return err("unauthorized", 401)


@app.post("/api/login")
def login():
    ok = (not PASSWORD) or (request.get_json(silent=True) or {}).get("password") == PASSWORD
    if not ok:
        return err("bad_password", 401)
    resp = jsonify({"ok": True})
    if PASSWORD:
        resp.set_cookie("hdo_auth", TOKEN, httponly=True, samesite="Strict", max_age=60 * 60 * 24 * 30)
    return resp


@app.get("/")
def index():
    return send_from_directory("static", "index.html")


@app.get("/manifest.webmanifest")
def manifest():
    return send_from_directory("static", "manifest.webmanifest", mimetype="application/manifest+json")


@app.get("/api/status")
def status():
    return jsonify({"app": "H-DO", "version": APP_VERSION, "ytdlp": ytdlp_version(), "ffmpeg": mgr.ffmpeg,
                    "auth": bool(PASSWORD), "cookies": mgr.cookies_path.exists(), "settings": mgr.settings})


@app.get("/api/info")
def info():
    url = (request.args.get("url") or "").strip()
    if not url.startswith(("http://", "https://")):
        return err("invalid_url")
    try:
        return jsonify(mgr.fetch_info(url, playlist=request.args.get("playlist") == "1"))
    except Exception as e:  # noqa: BLE001
        from downloader import friendly_error
        m = str(e)
        return jsonify({"error": m[-400:], "hint": friendly_error(m)}), 422


def _opts_from(body: dict) -> dict:
    s = mgr.settings
    mode = "audio" if body.get("mode") == "audio" else "video"
    q = str(body.get("quality") or s["quality"])
    if q != "best" and not q.isdigit():
        q = "best"
    af = body.get("audio_format") or s["audio_format"]
    ct = body.get("container") or s["container"]
    return {"mode": mode, "quality": q,
            "audio_format": af if af in ("mp3", "m4a", "opus", "flac", "wav") else "mp3",
            "container": ct if ct in ("mp4", "mkv", "webm") else "mp4",
            "subtitles": bool(body.get("subtitles")), "embed": bool(body.get("embed", s["embed"])),
            "sponsorblock": bool(body.get("sponsorblock", s["sponsorblock"]))}


@app.post("/api/downloads")
def add_downloads():
    body = request.get_json(silent=True) or {}
    urls = [u.strip() for u in (body.get("urls") or [body.get("url", "")]) if u and u.strip()]
    urls = [u for u in urls if u.startswith(("http://", "https://"))]
    if not urls:
        return err("invalid_url")
    opts = _opts_from(body)
    meta = body.get("meta") or {}
    created, dups = [], []
    if body.get("playlist") and len(urls) == 1:
        try:
            pl = mgr.fetch_info(urls[0], playlist=True)
        except Exception as e:  # noqa: BLE001
            return err(str(e)[-300:], 422)
        if pl["type"] == "playlist":
            wanted = body.get("items")
            for i, e in enumerate(pl["entries"]):
                if not e["url"] or (wanted is not None and i not in wanted):
                    continue
                created.append(mgr.add(e["url"], opts, e, folder=pl["title"] or "playlist"))
            return jsonify({"created": [j["id"] for j in created], "count": len(created)})
    for u in urls:
        d = mgr.find_duplicate(u, opts)
        if d and not body.get("force"):
            dups.append(u)
            continue
        created.append(mgr.add(u, opts, meta if len(urls) == 1 else None))
    if dups and not created:
        return jsonify({"duplicate": True, "urls": dups}), 409
    return jsonify({"created": [j["id"] for j in created], "count": len(created), "skipped": len(dups)})


@app.get("/api/downloads")
def list_downloads():
    return jsonify({"jobs": mgr.list()})


@app.post("/api/downloads/<jid>/cancel")
def cancel(jid):
    return jsonify({"ok": mgr.cancel(jid)})


@app.post("/api/downloads/<jid>/retry")
def retry(jid):
    return jsonify({"ok": mgr.retry(jid)})


@app.delete("/api/downloads/<jid>")
def remove(jid):
    return jsonify({"ok": mgr.remove(jid, delete_file=request.args.get("file") == "1")})


@app.post("/api/downloads/clear")
def clear():
    mgr.clear_finished()
    return jsonify({"ok": True})


@app.get("/api/files/<jid>")
def get_file(jid):
    j = mgr.get(jid)
    if not j or j["status"] != "done" or not j["filepath"] or not os.path.isfile(j["filepath"]) \
            or not mgr._inside_download_dir(j["filepath"]):
        return err("not_found", 404)
    return send_file(j["filepath"], as_attachment=True)


@app.route("/api/settings", methods=["GET", "POST"])
def settings():
    if request.method == "POST":
        try:
            return jsonify(mgr.save_settings(request.get_json(silent=True) or {}))
        except Exception as e:  # noqa: BLE001
            return err(str(e))
    return jsonify(mgr.settings)


@app.post("/api/cookies")
def cookies():
    f = request.files.get("file")
    if request.args.get("delete") == "1":
        mgr.cookies_path.unlink(missing_ok=True)
        return jsonify({"ok": True})
    if not f:
        return err("no_file")
    data = f.read()
    if b"\t" not in data:
        return err("not_netscape_cookies")
    mgr.cookies_path.write_bytes(data)
    return jsonify({"ok": True})


@app.post("/api/update-ytdlp")
def update_ytdlp():
    before = ytdlp_version()
    try:
        r = subprocess.run([sys.executable, "-m", "pip", "install", "-U", "yt-dlp"], capture_output=True, text=True, timeout=180)
    except Exception as e:  # noqa: BLE001
        return err(str(e), 500)
    if r.returncode != 0 and "externally-managed" in (r.stderr or ""):
        r = subprocess.run([sys.executable, "-m", "pip", "install", "-U", "yt-dlp", "--break-system-packages"],
                           capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        return err((r.stderr or "pip failed")[-300:], 500)
    return jsonify({"ok": True, "before": before, "after": ytdlp_version(), "restart": True})


if __name__ == "__main__":
    host = os.environ.get("HDO_HOST", "127.0.0.1")
    port = int(os.environ.get("HDO_PORT", "8080"))
    if host not in ("127.0.0.1", "localhost") and not PASSWORD:
        print("WARNING: exposing H-DO on the network without HDO_PASSWORD. Anyone on your network can use it.")
    print(f"H-DO {APP_VERSION}  ->  http://{host}:{port}   (yt-dlp {ytdlp_version()}, ffmpeg: {mgr.ffmpeg})")
    app.run(host=host, port=port, threaded=True)
