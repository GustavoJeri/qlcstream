"""Contract tests for safe version reporting and nonzero exit on drift/failure."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/check-integration-versions.py"
spec = importlib.util.spec_from_file_location("checker", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class Handler(BaseHTTPRequestHandler):
    version = "v5.3.0"
    rejected = False

    def log_message(self, *args):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"Fails." if self.rejected else b"Ok.")

    def do_GET(self):
        body = self.version if self.path.endswith("/version") else '{"version":"2.7.0.5723"}'
        self.send_response(200)
        self.end_headers()
        self.wfile.write(body.encode())


class VersionCheckTest(unittest.TestCase):
    def test_drift_and_safe_reporting(self):
        self.assertEqual(checker.compare("qbittorrent", "v5.3.0")["status"], "match")
        self.assertEqual(checker.compare("qbittorrent", "5.3.1")["status"], "mismatch")
        self.assertEqual(checker.compare("prowlarr", "2.7.0.5724")["status"], "mismatch")
        for body in (None, "", "secret=value", "5.3.0\nsecret", {"version": "5.3.0"}):
            with self.assertRaises(ValueError):
                checker.compare("qbittorrent", body)

    def test_cli_match_drift_auth_and_invalid_body(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        base = "http://127.0.0.1:" + str(server.server_port)
        env = dict(os.environ, QBITTORRENT_BASE_URL=base, PROWLARR_BASE_URL=base,
                   QBITTORRENT_USERNAME="fictional_user", QBITTORRENT_PASSWORD="fictional_password",
                   PROWLARR_API_KEY="fictional_key")
        try:
            for version, rejected, code, status in (("v5.3.0", False, 0, "match"),
                                                     ("v5.3.1", False, 1, "mismatch"),
                                                     ("secret=fictional_password", False, 1, "check_failed"),
                                                     ("v5.3.0", True, 1, "check_failed")):
                Handler.version, Handler.rejected = version, rejected
                result = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, code)
                self.assertEqual(json.loads(result.stdout.splitlines()[0])["status"], status)
                for secret in ("fictional_password", "fictional_key", base):
                    self.assertNotIn(secret, result.stdout + result.stderr)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == "__main__":
    unittest.main()
