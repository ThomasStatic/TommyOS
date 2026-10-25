#!/usr/bin/env bash
# Lumen Sports (ESPN fantasy ticker overlay): install, overlay app-op, ESPN sign-in, show/hide, diagnostics.
# Changes are logged to state/applied.log (the app-op with its previous mode) so scripts/restore.sh can undo them.
# Usage: scripts/sports.sh install | grant | revoke | auth | sign-out | show [opacity] | opacity <0-1> | hide
#                          | refresh | status | diag | shot <label> | espn-dump [dir] | uninstall
# ESPN credentials come from sports/espn-auth.properties (git-ignored): espn_s2=..., SWID={...}, optional league=<id>.
# They go to the TV on stdin (never on a PC command line) and are never printed. On the TV, `am` holds them as
# arguments for the moment it runs; Android hides other processes' arguments from apps (hidepid), so only the shell sees them.
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/state/applied.log"
APK="$ROOT/sports/app/build/outputs/apk/release/app-release.apk"
PKG=app.lumen.sports
SVC="$PKG/.TickerService"
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
    result="$(printf "am broadcast -n %s/.AuthReceiver -a %s.AUTH --es league '%s' --es espn_s2 '%s' --es swid '%s'\n" \
        "$PKG" "$PKG" "$LEAGUE" "$S2" "$SWID" | "$ADB" -s "$TV" shell 2>&1 | tr -d '\r' | grep -o 'result=[0-9]*')"
    case "$result" in
      result=1) echo "  saved on the TV (encrypted)"; log "auth $PKG league $LEAGUE" ;;
      result=2) echo "  REJECTED by the TV: check the values in $AUTH_FILE"; exit 1 ;;
      *) echo "  no answer from the TV (${result:-none})"; exit 1 ;;
    esac ;;
  sign-out)
    sh_ am broadcast -n "$PKG/.AuthReceiver" -a "$PKG.SIGN_OUT" | grep -q 'result=1' && echo "  credentials deleted from the TV" && log "sign-out $PKG" ;;
  show)
    sh_ am start-foreground-service -n "$SVC" --ef opacity "${2:-0.9}" ;;
  refresh)
    sh_ am start-foreground-service -n "$SVC" -a "$PKG.REFRESH" ;;
  opacity)
    sh_ am start-foreground-service -n "$SVC" --ef opacity "${2:?opacity 0-1}" ;;
  hide)
    sh_ am start-foreground-service -n "$SVC" -a "$PKG.HIDE" ;;
  status)
    echo "installed: $(installed && echo yes || echo no)"
    installed || exit 0
    echo "appop $OP: $(op_mode)"
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
