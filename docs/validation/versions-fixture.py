"""Real isolated services with an owned private torrent and a controlled Torznab source."""
import base64
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener

USER = "fixture"
PASSWORD = "fictional_versions_password"
API_KEY = "0123456789abcdef0123456789abcdef"
NAME = "QLCStream.Owned.2026.1080p.bin"
PAYLOAD = (b"QLCStream owned integration fixture; no third party content.\n" * 131072)
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()


def encode(value):
    if isinstance(value, int):
        return b"i" + str(value).encode() + b"e"
    if isinstance(value, bytes):
        return str(len(value)).encode() + b":" + value
    if isinstance(value, dict):
        return b"d" + b"".join(encode(k) + encode(v) for k, v in sorted(value.items())) + b"e"
    raise TypeError(type(value))


INFO = {b"name": NAME.encode(), b"length": len(PAYLOAD), b"piece length": 16384, b"private": 1,
        b"pieces": b"".join(hashlib.sha1(PAYLOAD[i:i + 16384]).digest() for i in range(0, len(PAYLOAD), 16384))}
HASH = hashlib.sha1(encode(INFO)).hexdigest()
TORRENT = encode({b"info": INFO})  # No public tracker. Peers are added explicitly.


def prepare():
    salt = b"synthetic-salt-issue20"
    derived = hashlib.pbkdf2_hmac("sha512", PASSWORD.encode(), salt, 100000)
    password = "@ByteArray(" + base64.b64encode(salt).decode() + ":" + base64.b64encode(derived).decode() + ")"
    for root in ("/engine", "/seed"):
        folder = Path(root + "/qBittorrent/config")
        folder.mkdir(parents=True, exist_ok=True)
        config = folder / "qBittorrent.conf"
        # Preparation never overwrites an existing configuration.
        if not config.exists():
            config.write_text("[BitTorrent]\nSession\\DHTEnabled=false\nSession\\PeXEnabled=false\n"
                              "Session\\LSDEnabled=false\nSession\\DefaultSavePath=/downloads\n"
                              "Session\\TempPathEnabled=false\n[WebUI]\nUsername=" + USER +
                              "\nPassword_PBKDF2=" + password + "\nLocalHostAuth=true\n"
                              "AuthSubnetWhitelistEnabled=false\nHostHeaderValidation=false\n"
                              "CSRFProtection=false\n[Meta]\nMigrationVersion=12\n")
    folder = Path("/prowlarr")
    folder.mkdir(exist_ok=True)
    if not (folder / "config.xml").exists():
        (folder / "config.xml").write_text("<Config><BindAddress>*</BindAddress><Port>9696</Port>"
                                          "<ApiKey>" + API_KEY + "</ApiKey><AuthenticationMethod>External</AuthenticationMethod>"
                                          "<AuthenticationRequired>Enabled</AuthenticationRequired><UpdateMechanism>Docker</UpdateMechanism>"
                                          "<AnalyticsEnabled>False</AnalyticsEnabled></Config>")
    Path("/payload").mkdir(exist_ok=True)
    (Path("/payload") / NAME).write_bytes(PAYLOAD)
    Path("/payload/owned.torrent").write_bytes(TORRENT)
    incoming = Path("/media/incoming")
    if not incoming.exists():
        incoming.mkdir(parents=True)
        os.chown(incoming, 100, 1000)
        incoming.chmod(0o2770)
    Path("/keys").mkdir(exist_ok=True)
    os.chown("/keys", 100, 101)
    Path("/keys").chmod(0o700)
    print("PASS: synthetic configuration and owned private torrent prepared")


class Source(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Request URLs can contain API keys.

    def do_GET(self):
        params = parse_qs(urlsplit(self.path).query)
        if self.path.startswith("/owned.torrent"):
            # Slow URL fetch ensures the real engine exercises asynchronous HTTP/202.
            time.sleep(2)
            body, content = TORRENT, "application/x-bittorrent"
        elif params.get("t") == ["caps"]:
            body = b'''<?xml version="1.0"?><caps><server title="Owned fixture"/><limits max="100" default="100"/>
<searching><search available="yes" supportedParams="q"/><movie-search available="yes" supportedParams="q"/>
<tv-search available="yes" supportedParams="q"/></searching><categories><category id="2000" name="Movies"/></categories></caps>'''
            content = "application/xml"
        else:
            body = ('''<?xml version="1.0"?><rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>
<title>Owned fixture</title><description>Owned fixture</description><link>http://source:8000</link><item><title>''' + NAME + '''</title>
<guid>qlcstream-owned-fixture</guid><link>http://source:8000/owned.torrent</link><pubDate>Fri, 13 Nov 2026 12:00:00 +0000</pubDate>
<size>''' + str(len(PAYLOAD)) + '''</size><enclosure url="http://source:8000/owned.torrent" length="''' + str(len(PAYLOAD)) + '''" type="application/x-bittorrent"/>
<torznab:attr name="category" value="2000"/><torznab:attr name="seeders" value="1"/>
<torznab:attr name="peers" value="1"/><torznab:attr name="infohash" value="''' + HASH + '''"/>
</item></channel></rss>''').encode()
            content = "application/xml"
        self.send_response(200)
        self.send_header("Content-Type", content)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class API:
    def __init__(self, base, key=None):
        self.base = base
        self.key = key
        self.client = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def raw(self, path, method="GET", data=None, form=False):
        headers = {"X-Api-Key": self.key} if self.key else {}
        if data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
            data = (urlencode(data) if form else json.dumps(data)).encode()
        with self.client.open(Request(self.base + path, data=data, method=method, headers=headers), timeout=20) as response:
            return response.status, response.read().decode()

    def call(self, *args, **kwargs):
        _, body = self.raw(*args, **kwargs)
        return json.loads(body) if body else None

    def login(self, password=PASSWORD):
        status, body = self.raw("/api/v2/auth/login", "POST", {"username": USER, "password": password}, True)
        assert status == 204 or (status == 200 and body.strip() == "Ok."), "login rejected"
        return status

    def post(self, path, data):
        return self.raw("/api/v2/" + path, "POST", data, True)


def wait(probe, description, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = probe()
            if result:
                return result
        except (OSError, ValueError, AssertionError):
            pass
        time.sleep(1)
    raise AssertionError("timeout: " + description)


def indexer_state(indexers):
    return [{key: item[key] for key in ("id", "name", "enable", "implementation", "configContract", "appProfileId", "priority", "fields")}
            for item in sorted(indexers, key=lambda item: item["id"])]


def check_indexer(p):
    marker = Path("/media/incoming/prowlarr-indexer.json")
    assert marker.exists(), "persisted Prowlarr indexer marker missing"
    expected = json.loads(marker.read_text())
    observed = indexer_state(p.call("/api/v1/indexer"))
    assert observed == expected, "persisted Prowlarr indexer missing or changed"


def test(clean):
    spec = importlib.util.spec_from_file_location("versions", "/scripts/check-integration-versions.py")
    versions = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(versions)
    q = API("http://qbittorrent:8080")
    seed = API("http://seeder:8080")
    p = API("http://prowlarr:9696", API_KEY)
    b = API("http://backend:8080")
    wait(lambda: q.login(), "engine")
    wait(lambda: seed.login(), "seeder")
    wait(lambda: p.call("/api/v1/system/status"), "prowlarr")
    wait(lambda: b.call("/actuator/health")["status"] == "UP", "backend")
    for service, base, secret in (("qbittorrent", q.base, PASSWORD), ("prowlarr", p.base, API_KEY)):
        result = versions.check(service, base, secret, USER)
        assert result["status"] == "match", "unexpected version"
        print(json.dumps(result))
        assert versions.compare(service, "99.0.0")["status"] == "mismatch"
    try:
        versions.compare("qbittorrent", "secret-invalid-body")
        raise AssertionError("unsafe version accepted")
    except ValueError:
        pass
    # Refusal tested through independent sessions, never using an authenticated cookie.
    try:
        API(q.base).login("fictional_wrong_password")
        raise AssertionError("bad password accepted")
    except HTTPError as failure:
        assert failure.code in (401, 403)
    except AssertionError as failure:
        assert str(failure) == "login rejected"
    for path in ("/api/v1/system/status", "/api/v1/search?query=Owned"):
        try:
            API(p.base, "fictional_wrong_key").raw(path)
            raise AssertionError("bad API key accepted")
        except HTTPError as failure:
            assert failure.code in (401, 403)
    preferences = q.call("/api/v2/app/preferences")
    assert not preferences["dht"] and not preferences["pex"] and not preferences["lsd"]
    assert q.call("/api/v2/torrents/info") == []
    assert seed.call("/api/v2/torrents/info") == []
    # Persisted managed configuration is created explicitly only on a clean fixture.
    existing = b.call("/api/system/integration-settings/key")["ready"]
    assert existing != clean, "unexpected installation key state"
    if clean:
        b.call("/api/system/integration-settings/key", "POST", {})
        for service, base, secret in (("qbittorrent", q.base, PASSWORD), ("prowlarr", p.base, API_KEY)):
            change = {"source": "application", "baseUrl": base, "username": USER,
                      "secret": {"action": "replace", "value": secret}}
            assert b.call("/api/system/integration-settings/" + service + "/test", "POST", change)["reason"] == "ok"
            b.call("/api/system/integration-settings/" + service, "PUT", change)
        b.call("/api/library/storage", "PUT", {"downloadDirectory": "incoming"})
        Path("/media/incoming/preserved.txt").write_text("synthetic prior media")
    assert Path("/media/incoming/preserved.txt").read_text() == "synthetic prior media"
    managed = {row["id"]: row for row in b.call("/api/system/integration-settings")
               if row["id"] in ("qbittorrent", "prowlarr")}
    assert set(managed) == {"qbittorrent", "prowlarr"}
    assert all(row["configured"] and row["source"] == "application" for row in managed.values())
    # Only a clean installation may create state. Existing installations must retain it.
    indexers = p.call("/api/v1/indexer")
    if clean:
        assert indexers == [], "clean installation already has indexers"
        schema = next(item for item in p.call("/api/v1/indexer/schema") if item["implementation"] == "Torznab")
        schema.update(name="Owned fixture", enable=True, appProfileId=1, priority=25)
        for field in schema["fields"]:
            if field["name"] == "baseUrl":
                field["value"] = "http://source:8000"
            elif field["name"] == "apiPath":
                field["value"] = "/api"
            elif field["name"] == "apiKey":
                field["value"] = "fictional_source_key"
        p.call("/api/v1/indexer", "POST", schema)
        Path("/media/incoming/prowlarr-indexer.json").write_text(json.dumps(indexer_state(p.call("/api/v1/indexer")), sort_keys=True))
    check_indexer(p)
    found = p.call("/api/v1/search?query=Owned&type=search")
    assert any(item["title"] == NAME for item in found), "real Prowlarr search missing result"
    assert any(item["title"] == NAME for item in b.call("/api/search/releases?query=Owned&year=2026"))
    diagnosis = b.call("/api/system/storage/check", "POST", {})
    assert diagnosis["backend"] == "read_write_verified" and not diagnosis["sharedDirectoryVerified"]
    # Seed our bytes; download using the same URL inclusion contract as the Java client.
    status, body = seed.post("torrents/add", {"urls": "http://source:8000/owned.torrent", "savepath": "/downloads"})
    assert status == 202 and json.loads(body)["pending_count"] > 0
    wait(lambda: any(t["progress"] == 1 for t in seed.call("/api/v2/torrents/info")), "owned seeder ready")
    status, body = q.post("torrents/add", {"urls": "http://source:8000/owned.torrent", "savepath": "/downloads/incoming"})
    assert status == 202 and json.loads(body)["pending_count"] > 0
    print("PASS: real URL inclusion HTTP 202 / JSON pending_count (seeder and downloader)")
    wait(lambda: q.call("/api/v2/torrents/info"), "URL retrieval")
    q.post("torrents/stop", {"hashes": HASH})
    wait(lambda: q.call("/api/v2/torrents/info")[0]["state"].startswith("stopped"), "stop")
    q.post("torrents/recheck", {"hashes": HASH})
    q.post("torrents/start", {"hashes": HASH})
    peer = socket.gethostbyname("seeder") + ":6881"
    def transferred():
        q.post("torrents/addPeers", {"hashes": HASH, "peers": peer})
        return any(t["progress"] == 1 for t in q.call("/api/v2/torrents/info"))
    wait(transferred, "owned peer transfer", 180)
    downloaded = Path("/media/incoming") / NAME
    assert hashlib.sha256(downloaded.read_bytes()).hexdigest() == DIGEST
    # The backend must read identical bytes using its own UID and volume mapping.
    q.post("torrents/stop", {"hashes": HASH})
    q.post("torrents/recheck", {"hashes": HASH})
    wait(lambda: any(t["progress"] == 1 and not t["state"].startswith("checking") for t in q.call("/api/v2/torrents/info")), "recheck owned bytes")
    q.post("torrents/delete", {"hashes": HASH, "deleteFiles": "false"})
    wait(lambda: q.call("/api/v2/torrents/info") == [], "removal")
    assert hashlib.sha256(downloaded.read_bytes()).hexdigest() == DIGEST
    # Exercise the production Java client as well as direct protocol observations.
    submitted = b.call("/api/downloads/series", "POST", {
        "seriesTmdbId": 202020, "seriesTitle": "Owned fixture", "seasonNumber": 1, "episodeNumber": 1,
        "releaseTitle": NAME, "acquisitionRef": "http://source:8000/owned.torrent?java=" + str(time.time_ns()),
        "indexerName": "Owned fixture"})
    identifier = submitted["id"]
    wait(lambda: q.call("/api/v2/torrents/info"), "Java client URL inclusion")
    # Synchronization attaches the hash before commands are issued by the application.
    wait(lambda: any(row["id"] == identifier and row["status"] not in ("QUEUED", "SUBMITTING")
                     for row in b.call("/api/downloads")), "Java client synchronization")
    queue = b.call("/api/downloads/queue")
    assert queue["sync"]["state"] == "available" and queue["sync"]["lastSuccessfulAt"]
    assert queue["sync"]["queueingEnabled"] == preferences["queueing_enabled"]
    queued = next(row for row in queue["items"] if row["id"] == identifier)
    assert queued["engineState"] and queued["canRetry"] is False
    b.raw("/api/downloads/" + identifier + "/PAUSE", "POST", {})
    wait(lambda: q.call("/api/v2/torrents/info")[0]["state"].startswith("stopped"), "Java pause")
    b.raw("/api/downloads/" + identifier + "/RESUME", "POST", {})
    wait(lambda: not q.call("/api/v2/torrents/info")[0]["state"].startswith("stopped"), "Java resume")
    java_torrent = q.call("/api/v2/torrents/info")[0]
    assert java_torrent["hash"] == HASH
    assert java_torrent["save_path"].rstrip("/") == "/downloads/incoming/" + identifier
    java_file = Path("/media/incoming") / identifier / NAME
    wait(transferred, "Java-owned peer transfer", 180)
    assert hashlib.sha256(java_file.read_bytes()).hexdigest() == DIGEST
    b.raw("/api/downloads/" + identifier + "/CANCEL", "POST", {})
    wait(lambda: q.call("/api/v2/torrents/info") == [], "Java cancel")
    assert hashlib.sha256(java_file.read_bytes()).hexdigest() == DIGEST
    seed.post("torrents/delete", {"hashes": HASH, "deleteFiles": "false"})
    print("PASS: production Java client via series submission, polling, PAUSE/RESUME/CANCEL; its downloaded bytes preserved")
    print("PASS: real login/refusal, preferences/list, Prowlarr status/refusal/search and backend search/diagnosis")
    print("PASS: stop/start/recheck, private peer transfer SHA256=" + DIGEST + ", deleteFiles=false preserved bytes")
    print("PASS: " + ("existing copied/restarted" if existing else "clean") + " installation; managed settings, key availability, indexer and media")


if __name__ == "__main__":
    action = sys.argv[1]
    if action == "prepare":
        prepare()
    elif action == "serve":
        ThreadingHTTPServer(("0.0.0.0", 8000), Source).serve_forever()
    elif action in ("test-clean", "test-existing"):
        test(clean=action == "test-clean")
    elif action == "check-indexer":
        p = API("http://prowlarr:9696", API_KEY)
        wait(lambda: p.call("/api/v1/system/status"), "prowlarr")
        check_indexer(p)
