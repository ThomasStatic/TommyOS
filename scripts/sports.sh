#!/usr/bin/env bash
# Lumen Sports (ESPN fantasy ticker overlay): install, overlay app-op, ESPN sign-in, show/hide, diagnostics.
# Changes are logged to state/applied.log (the app-op with its previous mode) so scripts/restore.sh can undo them.
# Usage: scripts/sports.sh install | grant | revoke | auth | sign-out | show [opacity] | opacity <0-1> | hide
#                          | set <height 40-96 | speed 10-240 | fps 30|60 | alert 0-15 | opacity 0-1 | edge top|bottom | auto on|off>...
#                          | preview highlight|stale|offline
#                          | refresh | status | diag | shot <label> | espn-dump [dir] | uninstall
#                          | phone on | off | pair [new] [--link] | unpair
# Settings (height in dp, background opacity, crawl speed in dp/s, edge) are kept on the TV across shows. Everything but
# auth goes through the app's control provider (DUMP-guarded for the shell, signature-guarded for Lumen).
# ESPN credentials come from sports/espn-auth.properties (git-ignored): espn_s2=..., SWID={...}, optional league=<id>.
# They go to the TV on stdin (never on a PC command line) and are never printed. On the TV, `content` holds them as
# arguments for the moment it runs; Android hides other processes' arguments from apps (hidepid), so only the shell sees them.
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/state/applied.log"
APK="$ROOT/sports/app/build/outputs/apk/release/app-release.apk"
PKG=app.lumen.sports
CONTROL="content://$PKG.control"
OP=SYSTEM_ALERT_WINDOW
AUTH_FILE="$ROOT/sports/espn-auth.properties"
DEFAULT_LEAGUE=2061761840
export MSYS_NO_PATHCONV=1
mkdir -p "$ROOT/state"
sh_() { "$ADB" -s "$TV" shell "$@" < /dev/null 2>&1 | tr -d '\r'; }
log() { echo "$(date -Iseconds) $*" >> "$LOG"; }
# espn-dump only talks to ESPN, so it doesn't need the TV.
[ "${1:-}" = espn-dump ] || [ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ] || { echo "TV $TV not connected"; exit 1; }

# Current app-op mode: allow | ignore | deny | default (no entry = default).
op_mode() { sh_ appops get "$PKG" "$OP" | sed -n "s/^$OP: \([a-z]*\).*/\1/p" | head -1 | grep . || echo default; }
installed() { sh_ pm list packages "$PKG" | grep -qx "package:$PKG"; }
control() { sh_ content call --uri "$CONTROL" --method "$@"; }

# Reads the auth file into S2 / SWID / LEAGUE, validating the shapes (cookie values are URL-safe, no quotes).
read_auth() {
  [ -f "$AUTH_FILE" ] || { echo "missing $AUTH_FILE (espn_s2=..., SWID={...})"; exit 1; }
  S2="$(sed -n 's/^espn_s2=//p' "$AUTH_FILE" | tr -d '\r\n ')"
  SWID="$(sed -n 's/^SWID=//p' "$AUTH_FILE" | tr -d '\r\n ')"
  LEAGUE="$(sed -n 's/^league=//p' "$AUTH_FILE" | tr -d '\r\n ')"; LEAGUE="${LEAGUE:-$DEFAULT_LEAGUE}"
  [[ "$S2" =~ ^[A-Za-z0-9%+/=_.-]+$ ]] || { echo "espn_s2 missing or has unexpected characters"; exit 1; }
  [[ "$SWID" =~ ^\{[0-9A-Fa-f-]{36}\}$ ]] || { echo "SWID must look like {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}"; exit 1; }
  [[ "$LEAGUE" =~ ^[0-9]+$ ]] || { echo "league must be numeric"; exit 1; }
}

case "${1:-status}" in
  install)
    [ -f "$APK" ] || { echo "APK not built: run sports/build.sh"; exit 1; }
    "$ADB" -s "$TV" install -r "$(cygpath -w "$APK")" | tr -d '\r' && log "install $PKG $(sha256sum "$APK" | cut -c1-16)"
    echo "  $OP after install: $(op_mode)" ;;
  grant)
    installed || { echo "not installed"; exit 1; }
    old="$(op_mode)"
    log "appop $PKG $OP $old"
    sh_ appops set "$PKG" "$OP" allow
    echo "  appop $OP: $old -> $(op_mode)" ;;
  revoke)
    installed || { echo "not installed"; exit 0; }
    orig="$(awk -v p="$PKG" -v o="$OP" '$2=="appop" && $3==p && $4==o {print $5; exit}' "$LOG")"
    sh_ appops set "$PKG" "$OP" "${orig:-default}"
    log "appop-restored $PKG $OP ${orig:-default}"
    echo "  appop $OP -> $(op_mode)" ;;
  auth)
    installed || { echo "not installed"; exit 1; }
    read_auth
    # The command goes to the TV's shell on stdin, so the cookie never appears in a PC process list (see the header for the TV side).
    # A provider call, not a broadcast: the TV keeps recent broadcasts' extras in plain text (dumpsys, bug reports).
    result="$(printf "content call --uri content://%s.auth --method auth --extra 'league:s:%s' --extra 'espn_s2:s:%s' --extra 'swid:s:%s'\n" \
        "$PKG" "$LEAGUE" "$S2" "$SWID" | "$ADB" -s "$TV" shell 2>&1 | tr -d '\r' | grep -o 'result=[0-9]*')"
    case "$result" in
      result=1) echo "  saved on the TV (encrypted)"; log "auth $PKG league $LEAGUE" ;;
      result=2) echo "  REJECTED by the TV: check the values in $AUTH_FILE"; exit 1 ;;
      *) echo "  no answer from the TV (${result:-none})"; exit 1 ;;
    esac ;;
  sign-out)
    sh_ content call --uri "content://$PKG.auth" --method sign-out | grep -q 'result=1' && echo "  credentials deleted from the TV" && log "sign-out $PKG" ;;
  show)
    control show ${2:+--extra "opacity:f:$2"} ;;
  set)
    # e.g. set height 60 speed 75 fps 30 edge top. Saved on the TV; a showing ticker applies them at once (a hidden one stays hidden).
    shift; extras=()
    while [ $# -ge 2 ]; do
      case "$1" in
        height|speed|fps|alert) [[ "$2" =~ ^[0-9]+$ ]] || { echo "$1 must be a whole number"; exit 2; }; extras+=(--extra "$1:i:$2") ;;
        opacity) [[ "$2" =~ ^[0-9.]+$ ]] || { echo "opacity must be 0-1"; exit 2; }; extras+=(--extra "opacity:f:$2") ;;
        edge) [[ "$2" =~ ^(top|bottom)$ ]] || { echo "edge must be top or bottom"; exit 2; }; extras+=(--extra "edge:s:$2") ;;
        auto) [[ "$2" =~ ^(on|off)$ ]] || { echo "auto must be on or off"; exit 2; }; extras+=(--extra "auto:s:$2") ;;
        *) echo "unknown setting $1"; exit 2 ;;
      esac
      shift 2
    done
    [ $# -eq 0 ] && [ ${#extras[@]} -gt 0 ] || { echo "usage: set <height|speed|opacity|edge> <value>..."; exit 2; }
    control set "${extras[@]}" ;;
  preview)
    # Design review on the current scores: highlight banners, or the stale / ESPN-unavailable status. The next fetch clears the last two.
    [[ "${2:-}" =~ ^(highlight|stale|offline)$ ]] || { echo "usage: preview highlight|stale|offline"; exit 2; }
    control preview --arg "$2" ;;
  refresh)
    control refresh ;;
  phone)
    # Phone controller (LAN page on port 8737). `pair` prints the pairing QR code here; `pair new` replaces the key
    # (every paired phone must scan again); --link also prints the link, which contains the key: don't paste it anywhere.
    case "${2:-}" in
      on|off) control phone --arg "$2" | grep -q 'ok=true' && echo "  phone control $2" && log "phone $PKG $2" ;;
      unpair) control unpair | grep -q 'ok=true' && echo "  pairing key deleted: no phone can control the ticker" && log "phone $PKG unpair" ;;
      pair)
        renew=""; link=""
        for a in "${@:3}"; do case "$a" in new) renew="--arg new" ;; --link) link=1 ;; *) echo "usage: phone pair [new] [--link]"; exit 2 ;; esac; done
        out="$(control pair $renew)"
        [ -n "$renew" ] && log "phone $PKG pair new"
        # The QR code as half blocks (two modules per character row), with a quiet zone; never logged.
        printf '%s' "$out" | LINK="$link" PYTHONIOENCODING=utf-8 python -c '
import os, re, sys
out = sys.stdin.read()
if "ok=true" not in out:
    err = re.search(r"error=([a-z-]+)", out)
    print("  no pairing code:", {"phone-off": "phone control is off (phone on)", "no-network": "the TV has no Wi-Fi address"}.get(err and err.group(1), out.strip()[:200]))
    sys.exit(1)
rows = re.search(r"qr=([01\n]+)", out).group(1).strip().split("\n")
n = len(rows); q = 2
grid = [[False] * (n + 2 * q) for _ in range(n + 2 * q)]
for y, r in enumerate(rows):
    for x, c in enumerate(r):
        grid[y + q][x + q] = c == "1"
if len(grid) % 2: grid.append([False] * len(grid[0]))
# Light modules print as full blocks (dark terminals), so the code reads as dark on light.
for y in range(0, len(grid), 2):
    line = ""
    for x in range(len(grid[0])):
        top, bottom = not grid[y][x], not grid[y + 1][x]
        line += chr(0x2588) if top and bottom else chr(0x2580) if top else chr(0x2584) if bottom else " "
    print("  " + line)
print("  scan with the iPhone Camera; page at " + re.search(r"address=(\S+?)[,}]", out).group(1))
if os.environ.get("LINK"):
    print("  link (contains the key):", re.search(r"url=(\S+?)[,}]", out).group(1))
' ;;
      *) echo "usage: phone on | off | pair [new] [--link] | unpair"; exit 2 ;;
    esac ;;
  opacity)
    control set --extra "opacity:f:${2:?opacity 0-1}" ;;
  hide)
    control hide ;;
  status)
    echo "installed: $(installed && echo yes || echo no)"
    installed || exit 0
    echo "appop $OP: $(op_mode)"
    # The app's own view (no credentials in it; team and league ID are shown on the PC only).
    echo "app:       $(control status | sed -n 's/^Result: Bundle\[{\(.*\)}\]$/\1/p')"
    echo "service:   $(sh_ dumpsys activity services "$PKG" | grep -c 'ServiceRecord') record(s)"
    echo "focus:     $(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/^ *//')"
    sh_ dumpsys window windows | grep -A12 'LumenSportsTicker' | grep -E 'Window #|mHasSurface|isVisible|mViewVisibility|isOnScreen' | sed 's/^ */  /'
    sh_ ps -A -o RSS,NAME | grep "$PKG" | awk '{printf "  RSS %d MB  %s\n",$1/1024,$2}' ;;
  diag)
    sh_ logcat -d -s LumenSports:V | tail -40
    sh_ dumpsys gfxinfo "$PKG" | grep -E 'Total frames|Janky|percentile' ;;
  shot)
    dir="$ROOT/screens/sports-poc"; mkdir -p "$dir"
    "$ADB" -s "$TV" exec-out screencap -p > "$dir/${2:?label}.png" && echo "$dir/$2.png" ;;
  espn-dump)
    # Raw ESPN responses for building parser fixtures. Saved outside the repo: they name league members.
    read_auth
    out="${2:-$ROOT/../espn-dump}"; mkdir -p "$out"
    season="$(date +%Y)"; [ "$(date +%-m)" -lt 3 ] && season=$((season - 1))
    base="https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/$season"
    # curl reads the cookie from a config on stdin (-K -), so it never appears in a process list.
    fetch() { # url outfile [x-fantasy-filter JSON]
      {
        printf 'header = "Cookie: espn_s2=%s; SWID=%s"\n' "$S2" "$SWID"
        [ -n "${3:-}" ] && printf 'header = "x-fantasy-filter: %s"\n' "${3//\"/\\\"}"
      } | curl -s --compressed -K - -o "$2" -w "  %{http_code} %{size_download}B $(basename "$2")\n" "$1"
    }
    fetch "$base/segments/0/leagues/$LEAGUE?view=mStatus&view=mSettings" "$out/status.json"
    period="$(python -c "import json,sys;print(json.load(open(sys.argv[1]))['status']['currentMatchupPeriod'])" "$out/status.json" 2>/dev/null)"
    echo "  season $season, matchup period ${period:-?}"
    fetch "$base/segments/0/leagues/$LEAGUE?view=mMatchupScore&view=mScoreboard&view=mTeam&view=mSettings&view=mStatus" \
      "$out/league.json" "{\"schedule\":{\"filterMatchupPeriodIds\":{\"value\":[${period:-1}]}}}"
    fetch "$base?view=proTeamSchedules_wl" "$out/proschedule.json"
    echo "  saved in $out" ;;
  uninstall)
    sh_ pm uninstall "$PKG" && log "uninstall $PKG" ;;
  *) echo "unknown command"; exit 2 ;;
esac
