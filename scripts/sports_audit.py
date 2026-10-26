"""Lumen Sports privacy and exposure audit (read-only). Usage: python scripts/sports_audit.py

Checks, with PASS/FAIL lines:
  1. The built APKs' manifests: every exported component of Lumen Sports and Lumen is guarded
     (DUMP, the signature control permission, or the accessibility binding), and the control
     permission is signature-level.
  2. On the TV: who holds the control permission; the overlay app-op; the listening sockets of the
     Sports app (only the phone port, on the Wi-Fi address).
  3. Secrets in diagnostics: searches every logcat buffer and the activity, broadcast, service and
     provider dumps for the real ESPN cookie and SWID (read from sports/espn-auth.properties on this
     PC) and the real pairing key (read from the TV over ADB), and for anything shaped like them.
     The values are compared in memory and never printed.
"""
import base64
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = os.environ.get("ADB", r"C:\Users\PC\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe")
TV = os.environ.get("TV", "10.0.0.213:5555")
AAPT2 = sorted(Path(r"C:\dev\android-sdk\build-tools").glob("*/aapt2.exe"))[-1]
APKS = {
    "app.lumen.sports": ROOT / "sports/app/build/outputs/apk/release/app-release.apk",
    "app.lumen.home": ROOT / "launcher/app/build/outputs/apk/release/app-release.apk",
}
CONTROL = "app.lumen.sports.permission.CONTROL"
GUARDS = {"android.permission.DUMP", CONTROL, "android.permission.BIND_ACCESSIBILITY_SERVICE"}
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{('  (' + detail + ')') if detail else ''}")


def adb(*args):
    return subprocess.run([ADB, "-s", TV, "shell", *args], capture_output=True, stdin=subprocess.DEVNULL).stdout.decode("utf-8", "replace").replace("\r", "")


def components(apk):
    """(kind, name, exported, guards) for each activity/service/receiver/provider in the APK's manifest."""
    tree = subprocess.run([str(AAPT2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], capture_output=True, text=True).stdout
    out, cur, perm_levels = [], None, {}
    for line in tree.splitlines():
        m = re.match(r"\s*E: (\S+)", line)
        if m:
            if cur:
                out.append(cur)
            cur = {"kind": m.group(1), "attrs": {}} if m.group(1) in ("activity", "service", "receiver", "provider", "permission") else None
            continue
        a = re.match(r'\s*A: http://schemas.android.com/apk/res/android:(\w+)\(0x[0-9a-f]+\)=(?:"([^"]*)"|(\S+))', line)
        if a and cur is not None:
            cur["attrs"][a.group(1)] = a.group(2) if a.group(2) is not None else a.group(3)
    if cur:
        out.append(cur)
    return out


def main():
    print("1. Manifests (built APKs)")
    for pkg, apk in APKS.items():
        if not apk.exists():
            check(f"{pkg}: APK built", False, str(apk))
            continue
        for c in components(apk):
            a = c["attrs"]
            if c["kind"] == "permission":
                if a.get("name") == CONTROL:
                    check(f"{pkg}: control permission is signature-level", a.get("protectionLevel") == "0x00000002", a.get("protectionLevel", "?"))
                continue
            exported = a.get("exported") == "true"
            guards = {a.get(k) for k in ("permission", "readPermission", "writePermission")} - {None}
            name = a.get("name", "?").replace(pkg, "")
            if not exported:
                check(f"{pkg}: {c['kind']} {name} not exported", True)
            elif pkg == "app.lumen.home" and name == ".HomeActivity":
                check(f"{pkg}: {name} exported as the launcher (no data, no actions)", True)
            else:
                check(f"{pkg}: exported {c['kind']} {name} guarded", bool(guards) and guards <= GUARDS, ", ".join(sorted(guards)) or "no permission")

    print("2. On the TV")
    perm = adb("dumpsys", "package", "permission", CONTROL)
    check("control permission defined by Lumen apps, signature level",
          re.search(r"sourcePackage=app\.lumen\.(sports|home)", perm) is not None and "prot=signature" in perm, re.sub(r"\s+", " ", perm)[:120])
    for pkg in ("app.lumen.home", "app.lumen.sports"):
        granted = re.search(re.escape(CONTROL) + r": granted=(\w+)", adb("dumpsys", "package", pkg))
        check(f"{pkg} holds the control permission", bool(granted) and granted.group(1) == "true")
    # Any other package requesting it would show here; signature level means it couldn't be granted anyway.
    others = [p for p in re.findall(r"package:(\S+)", adb("pm", "list", "packages", "-3")) if p not in ("app.lumen.home", "app.lumen.sports")]
    holders = [p for p in others if f"{CONTROL}: granted=true" in adb("dumpsys", "package", p)]
    check("no other installed app holds it", not holders, ",".join(holders))
    op = adb("appops", "get", "app.lumen.sports", "SYSTEM_ALERT_WINDOW")
    check("overlay app-op", "allow" in op, op.strip()[:80])
    uid = re.search(r"userId=(\d+)", adb("dumpsys", "package", "app.lumen.sports"))
    if uid:
        sockets = []
        for f in ("/proc/net/tcp", "/proc/net/tcp6"):
            for l in adb("cat", f).splitlines()[1:]:
                p = l.split()
                if len(p) > 7 and p[3] == "0A" and p[7] == uid.group(1):
                    sockets.append(p[1])
        ip = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/", adb("ip", "-4", "addr", "show", "wlan0"))
        v4 = "".join(f"{int(o):02X}" for o in reversed(ip.group(1).split("."))) if ip else "?"
        allowed = {f"{v4}:2221", f"0000000000000000FFFF0000{v4}:2221"}
        check("Sports listens only on the phone port, Wi-Fi address", set(sockets) <= allowed, f"{sockets or 'none (phone control off)'}")

    print("3. Secrets in logs and system dumps")
    dumps = {
        "logcat (all buffers)": adb("logcat", "-d", "-b", "all"),
        "activity broadcasts": adb("dumpsys", "activity", "broadcasts"),
        "activity services": adb("dumpsys", "activity", "services", "app.lumen"),
        "activity providers": adb("dumpsys", "activity", "providers", "app.lumen"),
        "activity intents": adb("dumpsys", "activity", "intents"),
        "package app.lumen.sports": adb("dumpsys", "package", "app.lumen.sports"),
    }
    secrets_ = {}
    auth = ROOT / "sports/espn-auth.properties"
    if auth.exists():
        for line in auth.read_text().splitlines():
            k, _, v = line.partition("=")
            if k.strip() in ("espn_s2", "SWID") and len(v.strip()) >= 16:
                secrets_[k.strip()] = v.strip()
    # Only an existing key: asking for the pairing link when there is none would create one.
    status = adb("content", "call", "--uri", "content://app.lumen.sports.control", "--method", "status")
    pair = adb("content", "call", "--uri", "content://app.lumen.sports.control", "--method", "pair") if "phonePaired=true" in status else ""
    m = re.search(r"#k=([A-Za-z0-9_-]{40,})", pair)
    if m:
        secrets_["pairing key"] = m.group(1)
        secrets_["pairing key (hex)"] = base64.urlsafe_b64decode(m.group(1) + "=" * (-len(m.group(1)) % 4)).hex()
    for label, value in secrets_.items():
        # Long fragments too: a value split across lines or truncated still matches.
        probes = {value, value[:24], value[-24:]} if len(value) > 30 else {value}
        found = [where for where, text in dumps.items() if any(p in text for p in probes)]
        check(f"real {label} absent from diagnostics", not found, ", ".join(found))
    if not secrets_:
        check("real secrets available to compare", False, "no espn-auth.properties and no pairing key")
    shapes = {
        "espn_s2=": re.compile(r"espn_s2\s*[=:]\s*[A-Za-z0-9%+/]{20,}"),
        "SWID GUID in braces": re.compile(r"\{[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\}", re.I),
        "pairing fragment #k=": re.compile(r"#k=[A-Za-z0-9_-]{40,}"),
        "X-Lumen-Sig value": re.compile(r"x-lumen-sig\s*[:=]\s*[0-9a-f]{64}", re.I),
    }
    for label, rx in shapes.items():
        found = [where for where, text in dumps.items() if rx.search(text)]
        check(f"nothing shaped like {label}", not found, ", ".join(found))

    passed = sum(results)
    print(f"{passed}/{len(results)} passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
