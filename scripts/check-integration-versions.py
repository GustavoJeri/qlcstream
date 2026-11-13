"""Read-only API version check. Credentials come from the process environment only."""
import http.cookiejar
import json
import os
import re
import sys
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPCookieProcessor, HTTPRedirectHandler, Request, build_opener

EXPECTED = {"qbittorrent": "5.3.0", "prowlarr": "2.7.0.5723"}


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Credentials and session cookies must never follow a Location response.
        return None


def compare(service, observed):
    # Do not print arbitrary remote bodies, addresses or credentials.
    if not isinstance(observed, str) or not re.fullmatch(r"v?\d+\.\d+\.\d+(?:\.\d+)?", observed):
        raise ValueError("invalid_version")
    version = observed.removeprefix("v")
    return {"service": service, "expected": EXPECTED[service], "observed": version,
            "status": "match" if version == EXPECTED[service] else "mismatch"}


def check(service, base, secret, username=""):
    parsed = urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("invalid_address")
    opener = build_opener(RejectRedirects(), HTTPCookieProcessor(http.cookiejar.CookieJar()))
    headers = {}
    if service == "qbittorrent":
        data = urlencode({"username": username, "password": secret}).encode()
        with opener.open(Request(base.rstrip("/") + "/api/v2/auth/login", data=data), timeout=5) as response:
            body = response.read().strip()
            if response.status != 204 and not (response.status == 200 and body == b"Ok."):
                raise ValueError("authentication_rejected")
        path = "/api/v2/app/version"
    else:
        headers["X-Api-Key"] = secret
        path = "/api/v1/system/status"
    with opener.open(Request(base.rstrip("/") + path, headers=headers), timeout=5) as response:
        body = response.read(65536).decode()
    return compare(service, json.loads(body).get("version") if service == "prowlarr" else body.strip())


def main():
    failed = False
    for service in EXPECTED:
        prefix = service.upper()
        try:
            result = check(service, os.environ[prefix + "_BASE_URL"],
                           os.environ[prefix + ("_PASSWORD" if service == "qbittorrent" else "_API_KEY")],
                           os.environ.get(prefix + "_USERNAME", ""))
        except Exception:
            result = {"service": service, "expected": EXPECTED[service], "status": "check_failed"}
        print(json.dumps(result))
        failed |= result["status"] != "match"
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
