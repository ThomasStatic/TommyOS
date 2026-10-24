#!/usr/bin/env bash
# READ-ONLY health check after a change or reboot:
#  - every package logged as disabled (and not later re-enabled) is still disabled
#  - crashes / ANRs since boot
#  - home activity, key processes, free memory
#  - N network snapshots (default 3, 20 s apart) saved under backup/<ts>-verify/
# Usage: scripts/verify.sh [snapshots] [interval_s]
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/state/applied.log"
N="${1:-3}"; GAP="${2:-20}"
export MSYS_NO_PATHCONV=1
sh_() { "$ADB" -s "$TV" shell "$@" < /dev/null 2>&1 | tr -d '\r'; }
[ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ] || { echo "TV $TV not connected"; exit 1; }
OUT="$ROOT/backup/$(date +%Y%m%d-%H%M%S)-verify"; mkdir -p "$OUT"

echo "== uptime: $(sh_ uptime | sed 's/,.*load.*//')"

echo "== logged package states"
disabled_now="$(sh_ pm list packages -d)"
# last logged action per package decides the expected state
awk '$2=="disable"||$2=="enable"{last[$3]=$2} END{for(p in last) print p, last[p]}' "$LOG" 2>/dev/null | sort | while read -r p want; do
  if echo "$disabled_now" | grep -qx "package:$p"; then have=disable; else have=enable; fi
  [ "$have" = "$want" ] && m="ok" || m="!! MISMATCH"
  printf '  %-45s expected=%-7s %s\n' "$p" "${want}d" "$m"
done

echo "== crashes / ANRs since boot"
sh_ logcat -d -b crash 2>/dev/null > "$OUT/logcat_crash.txt"
sh_ logcat -d -b main,system 2>/dev/null | grep -E 'FATAL EXCEPTION|ANR in|SecurityException|Force finishing' > "$OUT/logcat_problems.txt"
if [ -s "$OUT/logcat_crash.txt" ] || [ -s "$OUT/logcat_problems.txt" ]; then
  # Java crashes name the process on a "Process:" line; native crashes on "Fatal signal ... pid N (name)"
  grep -hE 'Process: [a-z]|ANR in|SecurityException' "$OUT/logcat_crash.txt" "$OUT/logcat_problems.txt" | sed -E 's/^.*(Process: |ANR in )([^ ,]+).*/  java crash\/ANR: \2/' | sort | uniq -c
  grep -hE 'Fatal signal' "$OUT/logcat_crash.txt" | sed -E 's/.*(Fatal signal [0-9]+ \([A-Z]+\)).*\(([^)]+)\)$/  native: \2 \1/' | sort | uniq -c
else echo "  none"; fi

echo "== home + processes"
echo "  home resolves to: $(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n1)"
echo "  foreground: $(sh_ dumpsys activity activities | grep -m1 -E 'mResumedActivity' | grep -oE '[a-z0-9.]+/[A-Za-z0-9_.]+')"
sh_ ps -A -o RSS,NAME | grep -E 'tv\.launcher$|ftvambient|settings\.v2$|vizzini|livetv' | awk '{printf "  %5d MB  %s\n",$1/1024,$2}'
sh_ cat /proc/meminfo | grep -E 'MemAvailable' | awk '{printf "  MemAvailable %d MB\n",$2/1024}'

echo "== network snapshots ($N x ${GAP}s)"
sh_ cmd package list packages -U > "$OUT/uids.txt"
for i in $(seq 1 "$N"); do
  sh_ cat /proc/net/tcp /proc/net/tcp6 > "$OUT/net_tcp_$i.txt"
  python -I "$(cygpath -w "$ROOT/scripts/netsnap.py")" "$(cygpath -w "$OUT")" "net_tcp_$i.txt" | sed "s/^/  [$i] /"
  [ "$i" -lt "$N" ] && sleep "$GAP"
done
echo "saved: $OUT"
