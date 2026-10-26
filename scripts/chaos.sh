#!/usr/bin/env bash
# Lumen Sports disruption scenarios, each with its expected outcome checked on the TV (PASS/FAIL lines).
# Usage: scripts/chaos.sh switch [n] | sleep [s] | kill | toggle [n] | wifi [s] | reboot | all
#   switch  cycles TSN -> Netflix -> Lumen -> TSN n times (default 3): ticker follows its mode, the app keeps focus,
#           one ticker process throughout, no crash
#   sleep   Sleep key, s seconds asleep (default 60), wake: no ESPN requests or frames while asleep, resumes after
#   kill    force-stops Lumen Sports: overlay gone at once, focus unchanged, nothing restarts within 60 s
#   toggle  n hide/show cycles through the control provider (default 20): ends in the state it started, no leak
#   wifi    Wi-Fi off for s seconds (default 120), scheduled on the TV: cached scores stay up, the phone listener
#           unbinds and rebinds, polling recovers within 30 s of Wi-Fi returning (logged in state/applied.log)
#   reboot  restarts the TV: ticker off after boot, phone listener back if phone control is on
# Interrupts whatever is playing: run when nobody is watching. Run with the ticker showing (sports.sh show).
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=app.lumen.sports
TSN=ca.tsn.mobile.android
NETFLIX=com.netflix.ninja
export MSYS_NO_PATHCONV=1
sh_() { "$ADB" -s "$TV" shell "$@" < /dev/null 2>&1 | tr -d '\r'; }
log() { echo "$(date -Iseconds) $*" >> "$ROOT/state/applied.log"; }
pass=0; fail=0
check() { if [ "$2" = 0 ]; then echo "  PASS  $1${3:+  ($3)}"; pass=$((pass + 1)); else echo "  FAIL  $1${3:+  ($3)}"; fail=$((fail + 1)); fi; }
on_screen() { sh_ dumpsys window LumenSportsTicker | grep -c "isOnScreen=true"; }
focus() { sh_ dumpsys window | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^/ }]*\).*/\1/p' | head -1; }
pid() { sh_ pidof "$PKG"; }
control() { sh_ content call --uri "content://$PKG.control" --method "$@"; }
status_field() { control status | grep -o "$1=[a-z0-9]*" | head -1 | cut -d= -f2; }
fetches_since() { sh_ logcat -d -v epoch -s LumenSports:V | awk -v t="$1" '$1 >= t && /fetched week|fetch failed/' | wc -l; }
launch() { sh_ monkey -p "$1" -c android.intent.category.LEANBACK_LAUNCHER 1 > /dev/null; }
auto_mode() { [ "$(status_field auto)" = true ]; }
crashes() { sh_ logcat -d -b crash | grep -c "$PKG"; }

switch_() {
  local n="${1:-3}" p0 c0; p0="$(pid)"; c0="$(crashes)"
  for i in $(seq "$n"); do
    for app in "$TSN" "$NETFLIX" app.lumen.home "$TSN"; do
      launch "$app"; sleep 6
      f="$(focus)"
      check "switch $i: $app has focus" "$([ "$f" = "$app" ]; echo $?)" "$f"
      if auto_mode; then
        # Auto mode shows only over TSN's player; launching TSN lands on its browse screen, so it stays hidden.
        check "switch $i: hidden over $app (auto)" "$([ "$(on_screen)" = 0 ]; echo $?)"
      else
        check "switch $i: shown over $app" "$([ "$(on_screen)" -ge 1 ]; echo $?)"
      fi
    done
  done
  check "same ticker process throughout" "$([ "$(pid)" = "$p0" ]; echo $?)" "pid $p0 -> $(pid)"
  check "no new crash" "$([ "$(crashes)" = "$c0" ]; echo $?)"
}

sleep_() {
  local s="${1:-60}" t0 frames
  sh_ input keyevent KEYCODE_SLEEP; sleep 5
  # Counted from here: the frames of the screen-off transition aren't "asleep".
  sh_ dumpsys gfxinfo "$PKG" reset > /dev/null
  t0="$(sh_ date +%s)"
  sleep "$s"
  frames="$(sh_ dumpsys gfxinfo "$PKG" | sed -n 's/^Total frames rendered: //p')"
  check "no ESPN requests while asleep" "$([ "$(fetches_since "$t0")" = 0 ]; echo $?)"
  check "no frames drawn while asleep" "$([ "${frames:-0}" -le 2 ]; echo $?)" "${frames:-0} frames"
  t0="$(sh_ date +%s)"
  sh_ input keyevent KEYCODE_WAKEUP; sleep 10
  if auto_mode; then
    check "awake: ticker process alive" "$([ -n "$(pid)" ]; echo $?)"
  else
    check "awake: ticker on screen" "$([ "$(on_screen)" -ge 1 ]; echo $?)"
    check "awake: fetched again" "$([ "$(fetches_since "$t0")" -ge 1 ]; echo $?)"
  fi
}

kill_() {
  local f0; f0="$(focus)"
  sh_ am force-stop "$PKG"; sleep 1
  check "overlay gone at once" "$([ "$(on_screen)" = 0 ]; echo $?)"
  check "focus unchanged" "$([ "$(focus)" = "$f0" ]; echo $?)" "$f0"
  sleep 60
  check "nothing restarted in 60 s" "$([ -z "$(pid)" ] || [ "$(on_screen)" = 0 ]; echo $?)" "pid '$(pid)'"
  control show > /dev/null; sleep 3
  check "shows again when asked" "$([ "$(on_screen)" -ge 1 ] || auto_mode; echo $?)"
}

toggle_() {
  local n="${1:-20}" was rss0 rss1
  was="$(status_field enabled)"
  control show > /dev/null; sleep 2
  rss0="$(sh_ ps -o RSS= -p "$(pid)" | tr -d ' ')"
  for i in $(seq "$n"); do control hide > /dev/null; sleep 0.8; control show > /dev/null; sleep 1.5; done
  check "$n hide/show cycles: showing at the end" "$([ "$(status_field enabled)" = true ]; echo $?)"
  rss1="$(sh_ ps -o RSS= -p "$(pid)" | tr -d ' ')"
  check "memory after cycles" "$([ "${rss1:-0}" -le $(( ${rss0:-0} + 20480 )) ]; echo $?)" "RSS $((${rss0:-0} / 1024)) -> $((${rss1:-0} / 1024)) MB"
  [ "$was" = true ] || control hide > /dev/null
}

wifi_() {
  local s="${1:-120}" t0
  log "wifi-test TV wifi disable ${s} s then enable (scheduled on the TV, chaos.sh)"
  # Scheduled on the TV itself: adb runs over Wi-Fi, so this PC loses the TV until Wi-Fi returns.
  sh_ "nohup sh -c 'svc wifi disable; sleep $s; svc wifi enable' > /dev/null 2>&1 &"
  sleep $((s + 25))
  until "$ADB" connect "$TV" 2>&1 | grep -q connected; do sleep 5; done
  "$ADB" -s "$TV" wait-for-device
  log "wifi-restored TV wifi enabled by the scheduled chaos.sh test"
  t0="$(( $(sh_ date +%s) - 25 ))"
  check "ticker stayed up through the outage" "$([ "$(on_screen)" -ge 1 ] || auto_mode; echo $?)"
  # Outside game windows the ticker polls every 15 min, so no fetch need fall in the outage; ask for one.
  # Read from the app's status: logcat's 256 KiB buffer rotates within seconds when Wi-Fi reconnects.
  control refresh > /dev/null; sleep 8
  check "ESPN fetched again after Wi-Fi returned" "$([ "$(status_field lastRefresh)" -ge "${t0}000" ]; echo $?)" "espn=$(status_field espn)"
  if [ "$(status_field phone)" = true ]; then
    local waited=0
    until [ "$(status_field phoneListening)" = true ] || [ $waited -ge 30 ]; do sleep 2; waited=$((waited + 2)); done
    check "phone listener rebound" "$([ "$(status_field phoneListening)" = true ]; echo $?)" "listening ${waited} s after the checks above"
  fi
}

reboot_() {
  local phone; phone="$(status_field phone)"
  log "reboot TV (chaos.sh)"
  "$ADB" -s "$TV" reboot; sleep 60
  until "$ADB" connect "$TV" 2>&1 | grep -q connected; do sleep 5; done
  "$ADB" -s "$TV" wait-for-device
  until [ "$(sh_ getprop sys.boot_completed)" = 1 ]; do sleep 3; done
  sleep 40
  check "ticker off after reboot" "$([ "$(on_screen)" = 0 ]; echo $?)"
  check "overlay app-op still allowed" "$(sh_ appops get "$PKG" SYSTEM_ALERT_WINDOW | grep -q allow; echo $?)"
  if [ "$phone" = true ]; then
    check "phone listener back after boot" "$([ "$(status_field phoneListening)" = true ]; echo $?)"
  fi
}

case "${1:-}" in
  switch) switch_ "${2:-3}" ;;
  sleep) sleep_ "${2:-60}" ;;
  kill) kill_ ;;
  toggle) toggle_ "${2:-20}" ;;
  wifi) wifi_ "${2:-120}" ;;
  reboot) reboot_ ;;
  all) toggle_ 20; switch_ 2; sleep_ 60; kill_; wifi_ 120; reboot_ ;;
  *) sed -n 2,13p "$0"; exit 2 ;;
esac
echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
