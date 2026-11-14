"""Isolated integration fixture: private owned bytes; no trackers or external peers."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import sys
import time
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("v", Path(__file__).with_name("versions-fixture.py"))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
v.NAME = "QLCStream Owned ação com espaços.mp4"
v.INFO[b"name"] = v.NAME.encode()
v.HASH = hashlib.sha1(v.encode(v.INFO)).hexdigest()
v.TORRENT = v.encode({b"info": v.INFO})


class Source(v.Source):
    served = 0
    def do_GET(self):
        if self.path == "/probe":
            body = json.dumps({"served": Source.served}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if "slow=1" in self.path:
            time.sleep(12)
        super().do_GET()
        if self.path.startswith("/owned.torrent"):
            Source.served += 1


def clients():
    q, b = v.API("http://qbittorrent:8080"), v.API("http://backend:8080")
    v.wait(q.login, "engine login")
    v.wait(lambda: b.call("/actuator/health")["status"] == "UP", "backend health")
    return q, b


def request(reference):
    return {"seriesTmdbId": 202020, "seriesTitle": "Owned file actions", "seasonNumber": 1,
            "episodeNumber": 1, "releaseTitle": v.NAME, "acquisitionRef": reference}


def cancel():
    q, b = clients()
    assert not q.call("/api/v2/torrents/info"), "fixture must start empty"
    b.call("/api/system/integration-settings/key", "POST", {})
    b.call("/api/system/integration-settings/qbittorrent", "PUT", {
        "source": "application", "baseUrl": q.base, "username": v.USER,
        "secret": {"action": "replace", "value": v.PASSWORD}})
    row = b.call("/api/downloads/series", "POST", request("http://source:8000/owned.torrent?slow=1"))
    assert not q.call("/api/v2/torrents/info"), "URL must still be pending"
    b.raw("/api/downloads/" + row["id"] + "/CANCEL", "POST", {})
    pending = next(item for item in b.call("/api/downloads") if item["id"] == row["id"])
    assert pending["status"] == "CANCELED", "pending cancellation must remain visible"
    print("PASS: URL inclusion pending; cancellation intent is persisted and visible", flush=True)


def verify_cancel():
    q, b = clients()
    v.wait(lambda: v.API("http://source:8000").call("/probe")["served"] >= 1, "slow source fetched")
    v.wait(lambda: not b.call("/api/downloads") and not q.call("/api/v2/torrents/info"), "late torrent removed")
    assert not q.call("/api/v2/torrents/info"), "late torrent must not survive cancellation"
    print("PASS: after backend restart the late torrent is removed without deleting bytes or resubmission", flush=True)


def delete():
    q, b = clients()
    seed = v.API("http://seeder:8080")
    v.wait(seed.login, "seed login")
    seed.post("torrents/add", {"urls": "http://source:8000/owned.torrent", "savepath": "/downloads"})
    v.wait(lambda: any(item["progress"] == 1 for item in seed.call("/api/v2/torrents/info")), "owned seed complete")
    row = b.call("/api/downloads/series", "POST", request("http://source:8000/owned.torrent?delete=1"))
    peer = socket.gethostbyname("seeder") + ":6881"
    def transferred():
        q.post("torrents/addPeers", {"hashes": v.HASH, "peers": peer})
        return any(item["progress"] == 1 for item in q.call("/api/v2/torrents/info"))
    v.wait(transferred, "owned transfer", 180)
    item = v.wait(lambda: next(iter(b.call("/api/library/series")), None), "library discovery")
    file = Path("/media") / item["relativePath"]
    assert hashlib.sha256(file.read_bytes()).hexdigest() == v.DIGEST, "transferred bytes must match"
    assert file.parent.stat().st_mode & 0o020, "engine subfolder must allow group writes"
    sibling = file.parent / "other owned episode.mp4"
    sibling.write_bytes(b"owned sibling preserved")
    def removed():
        try:
            b.raw("/api/library/" + str(item["id"]), "DELETE")
            return True
        except HTTPError as failure:
            if failure.code != 409:
                raise
            return False
    v.wait(removed, "coordinated file deletion")
    assert not file.exists(), "selected bytes must be deleted"
    assert sibling.read_bytes() == b"owned sibling preserved", "sibling bytes must survive"
    assert not q.call("/api/v2/torrents/info"), "linked torrent must be removed"
    b.raw("/api/library/" + str(item["id"]), "DELETE")
    time.sleep(6)
    assert not any(current["id"] == item["id"] for current in b.call("/api/library/series")), "deleted file must not revive"
    print("PASS: real transfer, compatible umask, exact-file deletion, torrent removal and sibling preservation", flush=True)
    print("PASS: repeated deletion succeeds and synchronization does not revive the deleted record", flush=True)


def escape_prepare():
    folder = Path("/media/incoming/escape")
    folder.mkdir()
    (folder / "owned.mp4").write_bytes(b"original authorized")
    outside = Path("/outside")
    outside.chmod(0o777)
    (outside / "owned.mp4").write_bytes(b"external marker")
    folder.rename(folder.with_name("escape-original"))
    folder.symlink_to(outside, target_is_directory=True)


def escape_verify():
    _, b = clients()
    item = next(item for item in b.call("/api/library/series") if item["seriesTmdbId"] == 202021)
    try:
        b.raw("/api/library/" + str(item["id"]), "DELETE")
        raise AssertionError("unsafe deletion accepted")
    except HTTPError as failure:
        assert failure.code == 409, "unsafe path must return controlled conflict"
    assert Path("/outside/owned.mp4").read_bytes() == b"external marker", "external bytes changed"
    assert Path("/media/incoming/escape-original/owned.mp4").read_bytes() == b"original authorized"
    print("PASS: real API rejects swapped parent symlink; outside and original bytes remain intact", flush=True)


def sse():
    from urllib.request import urlopen
    _, b = clients()
    before = b.call("/api/downloads")
    assert len(before) == 1, "SSE must preserve an actual row, not just an empty queue"
    served = v.API("http://source:8000").call("/probe")["served"]
    for _ in range(2):
        with urlopen("http://frontend/api/downloads/events", timeout=10) as response:
            lines = [response.readline().decode().strip() for _ in range(3)]
            assert "event:connected" in lines, "SSE connected event did not cross nginx"
        assert b.call("/api/downloads") == before, "reconnect changed downloads"
    assert v.API("http://source:8000").call("/probe")["served"] == served, "reconnect initiated a source fetch"
    print("PASS: real SSE reconnect through nginx preserves queue and does not submit sources", flush=True)


if __name__ == "__main__":
    action = sys.argv[1]
    try:
        if action == "prepare": v.prepare()
        elif action == "serve": ThreadingHTTPServer(("0.0.0.0", 8000), Source).serve_forever()
        else: {"cancel": cancel, "verify-cancel": verify_cancel, "delete": delete,
               "escape-prepare": escape_prepare, "escape-verify": escape_verify, "sse": sse}[action]()
    except Exception as failure:
        print("FAIL: fixture action " + action + " (" + type(failure).__name__ + ")", flush=True)
        sys.exit(1)
