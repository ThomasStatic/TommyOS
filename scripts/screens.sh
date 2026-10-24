#!/usr/bin/env bash
# Capture a standard set of Lumen screenshots for design review into screens/<label>/.
# Drives the UI with remote key events over ADB. Safety: detects which theme layout is showing and
# never presses OK where it could open an app; after every OK it checks Lumen is still in front and
# aborts (pressing Home) if not. Usage: scripts/screens.sh <label>
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="${1:-review-$(date +%H%M%S)}"
OUT="$ROOT/screens/$LABEL"; mkdir -p "$OUT"
export MSYS_NO_PATHCONV=1
sh_() { "$ADB" -s "$TV" shell "$@" < /dev/null | tr -d '\r'; }
key() { for k in "$@"; do sh_ input keyevent "$k"; sleep 0.25; done; }
shot() { sleep 0.7; "$ADB" -s "$TV" exec-out screencap -p > "$OUT/$1.png"; echo "  $1.png"; }
front() { sh_ dumpsys activity activities | grep -m1 mResumedActivity | grep -oE '[a-z0-9.]+/[A-Za-z0-9_.]+'; }
# Press OK only if the expected Lumen screen is in front afterwards; otherwise go Home and stop.
ok_expect() {
  key KEYCODE_DPAD_CENTER; sleep 1
  local now; now="$(front)"
  case "$now" in app.lumen.home/*) return 0 ;; esac
  echo "!! OK opened $now (expected Lumen) - pressing Home and aborting"; key KEYCODE_HOME; exit 3
}
[ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ] || { echo "TV $TV not connected"; exit 1; }

sh_ am start -n app.lumen.home/.HomeActivity >/dev/null; sleep 2
[ "$(front)" = "app.lumen.home/.HomeActivity" ] || { echo "Lumen home not in front"; exit 1; }
# Cinema has an All Apps overlay. A cold start can draw late, so ask a few times; default to the
# Classic sequence, which never presses OK.
THEME=classic
for _ in 1 2 3 4 5; do
  if sh_ dumpsys activity top | grep -q 'app:id/appsOverlay'; then THEME=cinema; break; fi
  if sh_ dumpsys activity top | grep -q 'app:id/scroll'; then break; fi
  sleep 1
done
echo "Capturing $THEME layout to $OUT"

if [ "$THEME" = cinema ]; then
  key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
  shot 01-home
  key KEYCODE_DPAD_RIGHT;                 shot 02-dock-second
  key KEYCODE_DPAD_DOWN KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
  shot 03-controls-first
  key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; shot 04-controls-settings
  key KEYCODE_DPAD_RIGHT;                 shot 05-controls-apps
  ok_expect;                              shot 06-all-apps
  key KEYCODE_DPAD_RIGHT;                 shot 07-all-apps-next
  key KEYCODE_MENU;                       shot 08-options
  key KEYCODE_BACK KEYCODE_BACK
  key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
  key KEYCODE_MENU;                       shot 09-dock-options
  key KEYCODE_BACK
  # Lumen settings: never press OK in there (it would change a setting); only move and look.
  key KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
  ok_expect;                              shot 10-settings
  key KEYCODE_DPAD_DOWN; sleep 1;         shot 11-wallpaper-preview
  key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; sleep 1; shot 12-wallpaper-preview-next
  key KEYCODE_BACK
else
  # Classic scrolls vertically; stay in the dock and the inputs row, never press OK.
  key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
  shot 01-home
  key KEYCODE_DPAD_RIGHT;                 shot 02-dock-second
  key KEYCODE_DPAD_DOWN;                  shot 03-inputs-row
  key KEYCODE_DPAD_UP KEYCODE_MENU;       shot 09-dock-options
  key KEYCODE_BACK
fi
echo "done"
