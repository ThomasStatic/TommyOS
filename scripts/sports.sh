#!/usr/bin/env bash
# Lumen Sports overlay proof of concept: install, overlay app-op, show/hide, diagnostics.
# Changes are logged to state/applied.log (the app-op with its previous mode) so scripts/restore.sh can undo them.
# Usage: scripts/sports.sh install | grant | revoke | show [opacity] [scroll] | opacity <0-1> | hide
#                          | status | diag | shot <label> | uninstall
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/state/applied.log"
APK="$ROOT/sports/app/build/outputs/apk/release/app-release.apk"
PKG=app.lumen.sports
SVC="$PKG/.TickerService"
OP=SYSTEM_ALERT_WINDOW
export MSYS_NO_PATHCONV=1
mkdir -p "$ROOT/state"
sh_() { "$ADB" -s "$TV" shell "$@" < /dev/null 2>&1 | tr -d '\r'; }
log() { echo "$(date -Iseconds) $*" >> "$LOG"; }
[ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ] || { echo "TV $TV not connected"; exit 1; }

# Current app-op mode: allow | ignore | deny | default (no entry = default).
op_mode() { sh_ appops get "$PKG" "$OP" | sed -n "s/^$OP: \([a-z]*\).*/\1/p" | head -1 | grep . || echo default; }
installed() { sh_ pm list packages "$PKG" | grep -qx "package:$PKG"; }

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
  show)
    args=(-n "$SVC" --ef opacity "${2:-0.9}")
    [ -n "${3:-}" ] && args+=(--ez scroll "$3")
    sh_ am start-foreground-service "${args[@]}" ;;
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
  uninstall)
    sh_ pm uninstall "$PKG" && log "uninstall $PKG" ;;
  *) echo "unknown command"; exit 2 ;;
esac
