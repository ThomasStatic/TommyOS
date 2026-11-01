#!/usr/bin/env bash
# Lumen Sports long-run measurement: samples the TV every interval and keeps a lossless ticker log, read-only.
# Usage: scripts/soak.sh run [minutes=240] [interval_s=30] [label=soak]   -> backup/soak/<label>-<time>/
#        scripts/soak.sh summary <dir>                                  -> pass/fail summary (scripts/soak_summary.py)
# Per sample (one adb round trip): CPU ticks and RSS of the ticker, SurfaceFlinger and TSN; the ticker's frames since
# the last sample (gfxinfo, reset each time); whether its window is on screen; the resumed activity; TSN's audio
# playback state. Every 10th sample adds the ticker's PSS. Network bytes for the app's uid at start and end (netstats).
# The sampling itself costs a few dumpsys calls in system_server per interval; it does not touch the ticker.
# Changes nothing on the TV (gfxinfo reset only clears the frame counters it reads).
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=app.lumen.sports
TSN=ca.tsn.mobile.android
export MSYS_NO_PATHCONV=1

# Runs on the TV (sent on stdin). One line per field group: "key value...".
SAMPLE='
echo "t $(date +%s)"
for p in '"$PKG"' surfaceflinger '"$TSN"'; do
  pid=$(pidof $p | cut -d" " -f1)
  if [ -n "$pid" ]; then
    set -- $(cut -d" " -f14,15 /proc/$pid/stat)
    echo "proc $p $pid $(($1 + $2)) $(awk "/^VmRSS/ {print \$2}" /proc/$pid/status)"
  else
    echo "proc $p - 0 0"
  fi
done
dumpsys gfxinfo '"$PKG"' reset | sed -n "s/^Total frames rendered: \([0-9]*\)/frames \1/p; s/^Janky frames: \([0-9]*\).*/janky \1/p; s/^Number Missed Vsync: \([0-9]*\)/missed \1/p" | head -3
echo "window $(dumpsys window LumenSportsTicker | grep -c "isOnScreen=true")"
echo "top $(dumpsys activity activities | sed -n "s/.*mResumedActivity: ActivityRecord{[^ ]* [^ ]* \([^ ]*\) .*/\1/p" | head -1)"
tsnuid=$(dumpsys package '"$TSN"' | sed -n "s/.*userId=\([0-9]*\).*/\1/p" | head -1)
echo "tsnaudio $(dumpsys audio | grep "u/pid:$tsnuid/" | grep -c "state:started")"
[ "${PSS:-0}" = 1 ] && echo "pss $(dumpsys meminfo -s '"$PKG"' | sed -n "s/^ *TOTAL PSS: *\([0-9]*\).*/\1/p; s/^ *TOTAL: *\([0-9]*\).*/\1/p" | head -1)"
'

sh_() { "$ADB" -s "$TV" shell "$@" < /dev/null 2>&1 | tr -d '\r'; }
connected() { [ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ]; }

# Received and sent bytes of the app's uid on all networks, after forcing a netstats poll.
net_bytes() {
  local uid; uid="$(sh_ dumpsys package "$PKG" | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)"
  sh_ dumpsys netstats --poll > /dev/null
  sh_ dumpsys netstats detail | awk -v u="uid=$uid" '
    /ident=/ { inuid = index($0, u " ") > 0 }
    inuid && / st=/ { for (i = 1; i <= NF; i++) { split($i, kv, "="); if (kv[1] == "rb") rb += kv[2]; if (kv[1] == "tb") tb += kv[2] } }
    END { printf "%d %d\n", rb, tb }'
}

case "${1:-}" in
  run)
    minutes="${2:-240}"; interval="${3:-30}"; label="${4:-soak}"
    connected || { echo "TV $TV not connected"; exit 1; }
    dir="$ROOT/backup/soak/$label-$(date +%Y%m%d-%H%M)"; mkdir -p "$dir"
    echo "$(date -Iseconds) start $(net_bytes)" > "$dir/net.txt"
    sh_ dumpsys gfxinfo "$PKG" reset > /dev/null
    # Lossless log of the ticker (fetches, failures, alerts) plus crashes and process deaths. Restarted if adb drops.
    # adb writes the file directly (no pipeline), so $! is the only process to stop at the end.
    logpid=""
    start_log() {
      "$ADB" -s "$TV" logcat -T 1 -v epoch -b main,system,crash LumenSports:V AndroidRuntime:E ActivityManager:I '*:S' \
        >> "$dir/ticker.log" 2>/dev/null < /dev/null &
      logpid=$!
    }
    start_log
    end=$(( $(date +%s) + $(awk -v m="$minutes" 'BEGIN { printf "%d", m * 60 }') )); n=0
    echo "sampling every ${interval}s for ${minutes} min into $dir"
    while [ "$(date +%s)" -lt "$end" ]; do
      n=$((n + 1))
      if connected; then
        kill -0 "$logpid" 2>/dev/null || start_log
        pss=0; [ $((n % 10)) -eq 1 ] && pss=1
        { echo "PSS=$pss"; echo "$SAMPLE"; } | "$ADB" -s "$TV" shell 2>&1 | tr -d '\r' | tr '\n' '|' >> "$dir/samples.txt"
        echo >> "$dir/samples.txt"
      else
        echo "t $(date +%s)|offline|" >> "$dir/samples.txt"
      fi
      sleep "$interval"
    done
    kill "$logpid" 2>/dev/null
    connected && echo "$(date -Iseconds) end $(net_bytes)" >> "$dir/net.txt"
    python "$(cygpath -w "$ROOT/scripts/soak_summary.py")" "$(cygpath -w "$dir")" ;;
  summary)
    python "$(cygpath -w "$ROOT/scripts/soak_summary.py")" "$(cygpath -w "${2:?dir}")" ;;
  *) echo "usage: scripts/soak.sh run [minutes] [interval_s] [label] | summary <dir>"; exit 2 ;;
esac
