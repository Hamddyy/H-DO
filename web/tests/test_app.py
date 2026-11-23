import functools, http.server, io, os, subprocess, sys, tempfile, threading, time, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
TMP = tempfile.mkdtemp()
os.environ["HDO_DATA"] = os.path.join(TMP, "data")
os.environ["HDO_PASSWORD"] = "secret"
import app as hdo  # noqa: E402

SRV = os.path.join(TMP, "srv"); os.makedirs(SRV)
subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-shortest", "-pix_fmt", "yuv420p",
                os.path.join(SRV, "sample.mp4")], check=True)


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass


httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=SRV))
threading.Thread(target=httpd.serve_forever, daemon=True).start()
URL = f"http://127.0.0.1:{httpd.server_port}/sample.mp4"


def wait(c, jid, until=("done", "error", "canceled"), t=60):
    end = time.time() + t
    j = None
    while time.time() < end:
        j = next(x for x in c.get("/api/downloads").json["jobs"] if x["id"] == jid)
        if j["status"] in until:
            return j
        time.sleep(0.3)
    raise AssertionError("timeout " + str(j))


class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.c = hdo.app.test_client()
        assert cls.c.post("/api/login", json={"password": "secret"}).status_code == 200
        r = cls.c.post("/api/settings", json={"download_dir": os.path.join(TMP, "dl"), "max_concurrent": 2})
        assert r.status_code == 200

    def test_auth(self):
        anon = hdo.app.test_client()
        self.assertEqual(anon.get("/api/status").status_code, 401)
        self.assertEqual(anon.post("/api/login", json={"password": "x"}).status_code, 401)
        self.assertEqual(anon.get("/").status_code, 200)

    def test_status_and_validation(self):
        s = self.c.get("/api/status").json
        self.assertTrue(s["ffmpeg"]); self.assertEqual(s["app"], "H-DO")
        self.assertEqual(self.c.post("/api/downloads", json={"url": "ftp://x"}).status_code, 400)
        self.assertEqual(self.c.get("/api/info?url=notaurl").status_code, 400)

    def test_info(self):
        r = self.c.get("/api/info?url=" + URL).json
        self.assertEqual(r["type"], "video"); self.assertEqual(r["title"], "sample")

    def test_video_download_and_file(self):
        r = self.c.post("/api/downloads", json={"url": URL, "mode": "video", "quality": "720", "embed": False}).json
        j = wait(self.c, r["created"][0])
        self.assertEqual(j["status"], "done", j["error"])
        self.assertTrue(os.path.isfile(j["filepath"])); self.assertIn("Video", j["filepath"])
        self.assertGreater(j["size"], 1000)
        f = self.c.get(f"/api/files/{j['id']}")
        self.assertEqual(f.status_code, 200); self.assertGreater(len(f.data), 1000)
        dup = self.c.post("/api/downloads", json={"url": URL, "mode": "video", "quality": "720"})
        self.assertEqual(dup.status_code, 409)
        forced = self.c.post("/api/downloads", json={"url": URL, "mode": "video", "quality": "720", "force": True, "embed": False})
        self.assertEqual(forced.status_code, 200)

    def test_audio_mp3(self):
        r = self.c.post("/api/downloads", json={"url": URL + "?x=audio", "mode": "audio", "audio_format": "mp3", "embed": False}).json
        j = wait(self.c, r["created"][0])
        self.assertEqual(j["status"], "done", j["error"])
        self.assertTrue(j["filepath"].endswith(".mp3")); self.assertIn("Audio", j["filepath"])
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0", j["filepath"]],
                               capture_output=True, text=True).stdout
        self.assertIn("mp3", probe)

    def test_error_retry_remove(self):
        bad = f"http://127.0.0.1:{httpd.server_port}/missing.mp4"
        r = self.c.post("/api/downloads", json={"url": bad, "embed": False}).json
        j = wait(self.c, r["created"][0])
        self.assertEqual(j["status"], "error")
        self.assertTrue(self.c.post(f"/api/downloads/{j['id']}/retry").json["ok"])
        wait(self.c, j["id"], until=("error",))
        self.assertTrue(self.c.delete(f"/api/downloads/{j['id']}").json["ok"])

    def test_cancel_queued(self):
        self.c.post("/api/settings", json={"max_concurrent": 1})
        a = self.c.post("/api/downloads", json={"url": URL + "?a=1", "embed": False}).json["created"][0]
        b = self.c.post("/api/downloads", json={"url": URL + "?b=1", "embed": False}).json["created"][0]
        self.c.post(f"/api/downloads/{b}/cancel")
        self.assertEqual(wait(self.c, b)["status"], "canceled")
        wait(self.c, a)
        self.c.post("/api/settings", json={"max_concurrent": 2})

    def test_cookies_and_settings(self):
        self.assertEqual(self.c.post("/api/cookies", data={"file": (io.BytesIO(b"garbage"), "c.txt")}).status_code, 400)
        ok = self.c.post("/api/cookies", data={"file": (io.BytesIO(b".x.com\tTRUE\t/\tTRUE\t0\tn\tv"), "c.txt")})
        self.assertEqual(ok.status_code, 200)
        self.assertTrue(self.c.get("/api/status").json["cookies"])
        self.c.post("/api/cookies?delete=1")

    def test_persistence(self):
        m2 = hdo.Manager(os.environ["HDO_DATA"])
        self.assertTrue(len(m2.jobs) >= 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
