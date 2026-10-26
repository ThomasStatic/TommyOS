"""On-device test of the Lumen Sports phone controller, from the PC on the same LAN.

Usage: python scripts/phone_test.py [--cycles N] [--no-block]
  Needs phone control on (scripts/sports.sh phone on) and the TV connected over ADB. Fetches the
  pairing key from the TV over ADB (never printed or saved), then checks:
    - the page and icon are served; the API refuses unsigned, replayed, stale, wrong-key and
      wrong-Host requests, oversized and chunked requests;
    - the listener is bound to the Wi-Fi address only (not 0.0.0.0, not loopback);
    - N show/hide cycles (default 5) through signed requests, with latency;
    - burst rate limiting, then (unless --no-block) the one-minute block after bad signatures.
  Leaves the ticker as it found it (on or off) and restores the edge. Exit code 0 when all pass.
"""
import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time

ADB = os.environ.get("ADB", r"C:\Users\PC\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe")
TV = os.environ.get("TV", "10.0.0.213:5555")
PORT = 8737
results = []


def adb(*args):
    return subprocess.run([ADB, "-s", TV, "shell", *args], capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout.replace("\r", "")


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{('  (' + detail + ')') if detail else ''}")


def pairing():
    out = adb("content", "call", "--uri", "content://app.lumen.sports.control", "--method", "pair")
    m = re.search(r"url=(http://([0-9.]+):\d+/)#k=([A-Za-z0-9_-]+)", out)
    if not m:
        sys.exit("no pairing link: is phone control on (scripts/sports.sh phone on)? " + re.sub(r"k=[^,}]+", "k=…", out.strip())[:200])
    key = base64.urlsafe_b64decode(m.group(3) + "=" * (-len(m.group(3)) % 4))
    return m.group(2), key


def raw(host_ip, data, timeout=6):
    """Sends raw bytes, returns (status, headers dict, body bytes)."""
    s = socket.create_connection((host_ip, PORT), timeout=timeout)
    try:
        s.sendall(data)
        buf = b""
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
    finally:
        s.close()
    head, _, body = buf.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    status = int(lines[0].split()[1]) if lines and len(lines[0].split()) > 1 else 0
    headers = {l.split(":", 1)[0].lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}
    return status, headers, body


def request(ip, method, path, body=b"", headers=None, host=None):
    h = {"Host": host or f"{ip}:{PORT}", "Connection": "close"}
    h.update(headers or {})
    if body:
        h["Content-Length"] = str(len(body))
    data = f"{method} {path} HTTP/1.1\r\n".encode() + "".join(f"{k}: {v}\r\n" for k, v in h.items()).encode() + b"\r\n" + body
    return raw(ip, data)


class Signer:
    def __init__(self, key):
        self.key, self.offset = key, 0

    def headers(self, method, path, body=b"", time_ms=None, nonce=None, key=None):
        t = time_ms if time_ms is not None else int(time.time() * 1000) + self.offset
        n = nonce or secrets.token_urlsafe(18)
        msg = f"LUMEN1\n{method}\n{path}\n{t}\n{n}\n".encode() + body
        sig = hmac.new(key or self.key, msg, hashlib.sha256).hexdigest()
        return {"X-Lumen-Time": str(t), "X-Lumen-Nonce": n, "X-Lumen-Sig": sig}


def api(ip, signer, method, path, obj=None):
    body = json.dumps(obj, separators=(",", ":")).encode() if obj is not None else b""
    hdr = signer.headers(method, path, body)
    if body:
        hdr["Content-Type"] = "application/json"
    t0 = time.time()
    status, _, resp = request(ip, method, path, body, hdr)
    return status, (json.loads(resp) if resp.startswith(b"{") else {}), (time.time() - t0) * 1000


def main():
    cycles = int(sys.argv[sys.argv.index("--cycles") + 1]) if "--cycles" in sys.argv else 5
    ip, key = pairing()
    signer = Signer(key)
    print(f"phone controller at {ip}:{PORT}")

    # Listening address: /proc/net/tcp{,6} show the local address of LISTEN sockets (state 0A) in little-endian hex.
    # Java sockets on Android are dual-stack, so an IPv4 bind shows in tcp6 as ::ffff:a.b.c.d.
    v4 = "".join(f"{int(o):02X}" for o in reversed(ip.split(".")))
    allowed = {f"{v4}:{PORT:04X}", f"0000000000000000FFFF0000{v4}:{PORT:04X}"}
    listen = []
    for f in ("/proc/net/tcp", "/proc/net/tcp6"):
        for l in adb("cat", f).splitlines()[1:]:
            p = l.split()
            if len(p) > 3 and p[1].endswith(f":{PORT:04X}") and p[3] == "0A":
                listen.append(p[1])
    check("listens on the Wi-Fi address only", bool(listen) and set(listen) <= allowed, f"listen sockets {listen}")

    status, headers, body = request(ip, "GET", "/")
    check("page served", status == 200 and b"Lumen Sports" in body and headers.get("content-security-policy", "").startswith("default-src 'none'"))
    status, headers, body = request(ip, "GET", "/icon.png")
    check("icon served", status == 200 and body[:8] == b"\x89PNG\r\n\x1a\n", f"{len(body)} bytes")
    status, _, _ = request(ip, "GET", "/", host="evil.example")
    check("other Host refused (DNS rebinding)", status == 421, str(status))
    status, _, body = request(ip, "GET", "/api/status")
    check("unsigned API call refused", status == 401 and b"malformed" in body, f"{status} {body[:60]!r}")

    status, s, ms = api(ip, signer, "GET", "/api/status")
    check("signed status", status == 200, f"{status}, {ms:.0f} ms")
    expected_keys = {"enabled", "visible", "edge", "auto", "height", "opacity", "speed", "alert", "espn", "signedIn", "lastRefresh", "now"}
    check("status carries no league data", set(s) == expected_keys, ",".join(sorted(set(s) ^ expected_keys)))
    signer.offset = s.get("now", 0) - int(time.time() * 1000)
    was_on, was_top = s.get("enabled"), s.get("edge") == "top"

    hdr = signer.headers("GET", "/api/status")
    first = request(ip, "GET", "/api/status", headers=hdr)[0]
    status, _, body = request(ip, "GET", "/api/status", headers=hdr)
    check("replayed request refused", first == 200 and status == 401 and b"replayed" in body, f"{first}/{status}")
    status, _, body = request(ip, "GET", "/api/status", headers=signer.headers("GET", "/api/status", time_ms=int(time.time() * 1000) - 300_000))
    check("stale request refused with the TV clock", status == 401 and b"stale" in body and b"now" in body, f"{status}")
    status, _, body = request(ip, "POST", "/api/show", headers=signer.headers("POST", "/api/show", key=bytes(32)))
    check("wrong key refused", status == 401 and b"bad_signature" in body, f"{status}")
    hdr = signer.headers("POST", "/api/set", b'{"edge":"top"}')
    status, _, _ = request(ip, "POST", "/api/set", b'{"edge":"bottom"}', hdr)
    check("altered body refused", status == 401, f"{status}")

    status, _, _ = raw(ip, b"GET / HTTP/1.1\r\nHost: x\r\nX-Big: " + b"a" * 5000 + b"\r\n\r\n")
    check("oversized headers refused", status == 431, str(status))
    status, _, _ = request(ip, "POST", "/api/set", b"x" * 3000)
    check("oversized body refused", status == 413, str(status))
    status, _, _ = request(ip, "POST", "/api/set", headers={"Transfer-Encoding": "chunked"})
    check("chunked body refused", status == 501, str(status))
    time.sleep(3)  # Let the rate limiter refill after the bursts above.

    times = []
    ok = True
    for i in range(cycles):
        s1, j1, t1 = api(ip, signer, "POST", "/api/show")
        s2, j2, t2 = api(ip, signer, "POST", "/api/hide")
        times += [t1, t2]
        ok &= s1 == 200 and j1.get("visible") is True and s2 == 200 and j2.get("enabled") is False
        time.sleep(1.2)
    if cycles:
        check(f"{cycles} show/hide cycles", ok, f"latency median {sorted(times)[len(times) // 2]:.0f} ms, max {max(times):.0f} ms")

    s1, j1, _ = api(ip, signer, "POST", "/api/set", {"edge": "top"})
    s2, j2, _ = api(ip, signer, "POST", "/api/set", {"edge": "top" if was_top else "bottom"})
    check("settings change and restore", s1 == 200 and j1.get("edge") == "top" and s2 == 200, f"edge restored to {j2.get('edge')}")
    if was_on and cycles:
        api(ip, signer, "POST", "/api/show")

    time.sleep(10)
    codes = [request(ip, "GET", "/")[0] for _ in range(30)]
    check("burst limited", codes.count(429) > 0 and codes[:15].count(200) == 15, f"{codes.count(200)} served, {codes.count(429)} limited")

    if "--no-block" not in sys.argv:
        time.sleep(12)
        for _ in range(5):
            request(ip, "POST", "/api/show", headers=signer.headers("POST", "/api/show", key=bytes(32)))
        status, _, _ = api(ip, signer, "GET", "/api/status")
        check("address blocked after 5 bad signatures", status == 429, f"{status} (this PC is refused for a minute)")

    passed = sum(results)
    print(f"{passed}/{len(results)} passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
