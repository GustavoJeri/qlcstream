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
from urllib.error import HTTPError

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/check-integration-versions.py"
spec = importlib.util.spec_from_file_location("checker", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class Handler(BaseHTTPRequestHandler):
    version = "v5.3.0"
    rejected = False
    login_status = 200

    def log_message(self, *args):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(self.login_status)
        if not self.rejected:
            self.send_header("Set-Cookie", "SID=fictional_session; Path=/")
        self.end_headers()
        if self.login_status != 204:
            self.wfile.write(b"Fails." if self.rejected else b"Ok.")

    def do_GET(self):
        if self.path.endswith("/version") and "SID=fictional_session" not in self.headers.get("Cookie", ""):
            self.send_response(403)
            self.end_headers()
            return
        body = self.version if self.path.endswith("/version") else '{"version":"2.7.0.5723"}'
        self.send_response(200)
        self.end_headers()
        self.wfile.write(body.encode())


class VersionCheckTest(unittest.TestCase):
    def test_redirects_never_reach_target(self):
        reached = []

        class Target(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                reached.append(self.path)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"version":"2.7.0.5723"}')

            do_POST = do_GET

        class Source(Target):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                if self.server.stage == "version":
                    self.send_response(204)
                    self.send_header("Set-Cookie", "SID=fictional_session; Path=/")
                    self.end_headers()
                else:
                    self.redirect()

            def do_GET(self):
                if self.path == "/target":
                    return super().do_GET()
                self.redirect()

            def redirect(self):
                self.send_response(self.server.code)
                self.send_header("Location", self.server.location)
                self.end_headers()

        target = ThreadingHTTPServer(("127.0.0.1", 0), Target)
        source = ThreadingHTTPServer(("127.0.0.1", 0), Source)
        threads = [threading.Thread(target=s.serve_forever, daemon=True) for s in (source, target)]
        for thread in threads:
            thread.start()
        base = "http://127.0.0.1:" + str(source.server_port)
        other = "127.0.0.1:" + str(target.server_port)
        try:
            for location in ("http://" + other + "/target", "//" + other + "/target", "/target"):
                for code in (301, 302, 303, 307, 308):
                    for stage, service in (("login", "qbittorrent"), ("version", "qbittorrent"), ("status", "prowlarr")):
                        with self.subTest(location=location, code=code, stage=stage):
                            source.location, source.code, source.stage = location, code, stage
                            with self.assertRaises(HTTPError):
                                checker.check(service, base, "fictional_key", "fictional_user")
                            self.assertEqual(reached, [], "redirect target received an authenticated request")
            env = dict(os.environ, QBITTORRENT_BASE_URL=base, PROWLARR_BASE_URL=base,
                       QBITTORRENT_USERNAME="fictional_user", QBITTORRENT_PASSWORD="fictional_password",
                       PROWLARR_API_KEY="fictional_key")
            result = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertTrue(all(json.loads(row)["status"] == "check_failed" for row in result.stdout.splitlines()))
            self.assertEqual(reached, [])
            for value in (base, "fictional_key", "fictional_password", "fictional_session"):
                self.assertNotIn(value, result.stdout + result.stderr)
        finally:
            for server in (source, target):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join()

    def test_drift_and_safe_reporting(self):
        self.assertEqual(checker.compare("qbittorrent", "v5.3.0")["status"], "match")
        self.assertEqual(checker.compare("qbittorrent", "5.3.1")["status"], "mismatch")
        self.assertEqual(checker.compare("prowlarr", "2.7.0.5724")["status"], "mismatch")
        for body in (None, "", "secret=value", "5.3.0\nsecret", {"version": "5.3.0"}):
            with self.assertRaises(ValueError):
                checker.compare("qbittorrent", body)

    def test_cli_match_drift_auth_and_invalid_body(self):
        Handler.login_status = 200
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

    def test_login_200_and_204_preserve_cookie_session(self):
        Handler.rejected, Handler.version = False, "v5.3.0"
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            for status in (200, 204):
                with self.subTest(login=status):
                    Handler.login_status = status
                    result = checker.check("qbittorrent", "http://127.0.0.1:" + str(server.server_port),
                                           "fictional_password", "fictional_user")
                    self.assertEqual(result["status"], "match")
        finally:
            Handler.login_status = 200
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == "__main__":
    unittest.main()
