#!/usr/bin/env bash
# Lumen launcher install/redirect management. Every change is logged to state/applied.log so
# scripts/restore.sh can undo it (settings are logged with their previous value).
# Usage: scripts/launcher.sh install | open | enable-redirect | disable-redirect | uninstall | status
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/state/applied.log"
APK="$ROOT/launcher/app/build/outputs/apk/release/app-release.apk"
PKG=app.lumen.home
SERVICE="$PKG/$PKG.HomeRedirectService"
export MSYS_NO_PATHCONV=1
mkdir -p "$ROOT/state"
sh_() { "$ADB" -s "$TV" shell "$@" < /dev/null 2>&1 | tr -d '\r'; }
log() { echo "$(date -Iseconds) $*" >> "$LOG"; }
[ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ] || { echo "TV $TV not connected"; exit 1; }

# put_secure KEY VALUE — logs the previous value first so restore can put it back exactly.
put_secure() {
  local old; old="$(sh_ settings get secure "$1")"
  log "setting secure $1 ${old:-null}"
  sh_ settings put secure "$1" "$2" >/dev/null
  echo "  secure $1: ${old:-null} -> $(sh_ settings get secure "$1")"
}

case "${1:-status}" in
  install)
    [ -f "$APK" ] || { echo "APK not built: run launcher/build.sh"; exit 1; }
    "$ADB" -s "$TV" install -r "$(cygpath -w "$APK")" | tr -d '\r' && log "install $PKG $(sha256sum "$APK" | cut -c1-16)" ;;
  open)
    sh_ am start -n "$PKG/.HomeActivity" ;;
  enable-redirect)
    current="$(sh_ settings get secure enabled_accessibility_services)"
    case "$current" in *"$SERVICE"*) echo "already enabled"; exit 0 ;; esac
    if [ -z "$current" ] || [ "$current" = null ]; then new="$SERVICE"; else new="$current:$SERVICE"; fi
    # Log accessibility_enabled first: Android flips it to 1 as soon as a service is listed.
    put_secure accessibility_enabled 1
    put_secure enabled_accessibility_services "$new" ;;
  disable-redirect)
    current="$(sh_ settings get secure enabled_accessibility_services)"
    new="$(echo "$current" | tr ':' '\n' | grep -vx "$SERVICE" | paste -sd: -)"
    put_secure enabled_accessibility_services "${new:-}"
    [ -z "$new" ] && put_secure accessibility_enabled 0 ;;
  uninstall)
    sh_ pm uninstall "$PKG" && log "uninstall $PKG" ;;
  status)
    echo "installed: $(sh_ pm list packages "$PKG" | grep -c "package:$PKG\$")"
    echo "a11y services: $(sh_ settings get secure enabled_accessibility_services)"
    echo "a11y enabled:  $(sh_ settings get secure accessibility_enabled)"
    sh_ dumpsys accessibility | grep -E 'Bound services|Enabled services' | sed 's/^ */  /'
    sh_ ps -A -o RSS,NAME | grep -E "$PKG" | awk '{printf "  RSS %d MB  %s\n",$1/1024,$2}' ;;
  *) echo "unknown command"; exit 2 ;;
esac
