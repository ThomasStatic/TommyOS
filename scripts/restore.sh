#!/usr/bin/env bash
# Rollback: re-enable every package this project ever disabled (from state/applied.log),
# newest first. Safe to re-run. Launcher/settings undo steps are appended here as later phases add them.
# Usage: scripts/restore.sh [--dry-run]
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/state/applied.log"
DRY="${1:-}"
export MSYS_NO_PATHCONV=1

[ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ] || { echo "TV $TV not connected (re-enable ADB debugging, then: adb connect $TV)"; exit 1; }
[ -f "$LOG" ] || { echo "No changes logged; nothing to restore."; exit 0; }

fail=0

# --- Launcher undo: put each logged secure setting back to its FIRST recorded (original) value ---
awk '$2=="setting" && $3=="secure" && !seen[$4]++ {print $4, $5}' "$LOG" | while read -r key old; do
  if [ "$DRY" = "--dry-run" ]; then echo "would restore secure $key -> $old"; continue; fi
  if [ "$old" = null ]; then "$ADB" -s "$TV" shell settings delete secure "$key" < /dev/null >/dev/null
  else "$ADB" -s "$TV" shell settings put secure "$key" "$old" < /dev/null; fi
  echo "secure $key restored to $old"
  echo "$(date -Iseconds) setting-restored secure $key $old" >> "$LOG"
done
# --- Lumen Sports PoC: uninstalling also clears its app-op; restore the logged mode first in case uninstall fails ---
if grep -q " install app.lumen.sports" "$LOG" && "$ADB" -s "$TV" shell pm list packages app.lumen.sports < /dev/null | grep -q app.lumen.sports; then
  awk '$2=="appop" && !seen[$3" "$4]++ {print $3, $4, $5}' "$LOG" | while read -r pkg op old; do
    if [ "$DRY" = "--dry-run" ]; then echo "would restore appop $pkg $op -> $old"; continue; fi
    "$ADB" -s "$TV" shell appops set "$pkg" "$op" "$old" < /dev/null
    echo "$(date -Iseconds) appop-restored $pkg $op $old" >> "$LOG"
  done
  if [ "$DRY" = "--dry-run" ]; then echo "would uninstall app.lumen.sports"
  else "$ADB" -s "$TV" shell pm uninstall app.lumen.sports < /dev/null | tr -d '\r'; echo "$(date -Iseconds) uninstall app.lumen.sports (restore)" >> "$LOG"; fi
fi
if grep -q " install app.lumen.home" "$LOG" && "$ADB" -s "$TV" shell pm list packages app.lumen.home < /dev/null | grep -q app.lumen.home; then
  if [ "$DRY" = "--dry-run" ]; then echo "would uninstall app.lumen.home"
  else "$ADB" -s "$TV" shell pm uninstall app.lumen.home < /dev/null | tr -d '\r'; echo "$(date -Iseconds) uninstall app.lumen.home (restore)" >> "$LOG"; fi
fi

# Every package that was ever disabled, newest first, deduplicated.
pkgs="$(awk '$2=="disable"{print $3}' "$LOG" | tac | awk '!seen[$0]++')"
[ -n "$pkgs" ] || { echo "No disabled packages logged."; exit 0; }

for p in $pkgs; do
  if [ "$DRY" = "--dry-run" ]; then echo "would enable $p"; continue; fi
  "$ADB" -s "$TV" shell pm enable --user 0 "$p" 2>&1 | tr -d '\r'
  if "$ADB" -s "$TV" shell pm list packages -d "$p" | tr -d '\r' | grep -qx "package:$p"; then
    echo "  !! $p still disabled"; fail=1
  else
    echo "$(date -Iseconds) enable $p (restore)" >> "$LOG"
  fi
done

[ "$fail" = 0 ] && echo "Restore complete." || { echo "Restore finished with errors."; exit 1; }
