#!/usr/bin/env bash
# Reversible package state changes, one package per adb call, each logged to state/applied.log.
# Usage: scripts/pkg.sh disable <pkg>   |   scripts/pkg.sh enable <pkg>   |   scripts/pkg.sh status <pkg>
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/state/applied.log"
mkdir -p "$ROOT/state"
export MSYS_NO_PATHCONV=1

sh_() { "$ADB" -s "$TV" shell "$@" 2>&1 | tr -d '\r'; }

# Prints enabled|disabled-user|disabled|missing
state() {
  local p="$1"
  sh_ pm list packages "$p" | grep -qx "package:$p" || { echo missing; return; }
  if sh_ pm list packages -d "$p" | grep -qx "package:$p"; then
    sh_ dumpsys package "$p" | grep -m1 -oE 'enabled=[0-9]' | grep -q 'enabled=3' && echo disabled-user || echo disabled
  else
    echo enabled
  fi
}

cmd="${1:-}"; pkg="${2:-}"
[ -n "$pkg" ] || { echo "usage: $0 disable|enable|status <pkg>"; exit 2; }
[ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ] || { echo "TV $TV not connected"; exit 1; }

case "$cmd" in
  status) echo "$pkg: $(state "$pkg")" ;;
  disable)
    before="$(state "$pkg")"
    [ "$before" = missing ] && { echo "$pkg: not installed"; exit 1; }
    out="$(sh_ pm disable-user --user 0 "$pkg")"
    after="$(state "$pkg")"
    echo "$pkg: $before -> $after   [$out]"
    if [ "$after" != enabled ]; then
      echo "$(date -Iseconds) disable $pkg" >> "$LOG"
    else
      echo "$(date -Iseconds) REFUSED $pkg :: $out" >> "$LOG"; exit 1
    fi ;;
  enable)
    out="$(sh_ pm enable --user 0 "$pkg")"
    after="$(state "$pkg")"
    echo "$pkg: -> $after   [$out]"
    echo "$(date -Iseconds) enable $pkg" >> "$LOG"
    [ "$after" = enabled ] ;;
  *) echo "unknown command $cmd"; exit 2 ;;
esac
