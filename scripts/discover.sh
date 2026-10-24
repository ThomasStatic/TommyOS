#!/usr/bin/env bash
# Phase 1 discovery + configuration backup. READ-ONLY: only getprop, pm list,
# settings list, dumpsys, cmd ... query/resolve, and /proc reads. Changes nothing on the TV.
# Usage: scripts/discover.sh [host:port]
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${1:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/backup/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

sh_() { "$ADB" -s "$TV" shell "$@" 2>&1 | tr -d '\r'; }
save() { local f="$1"; shift; sh_ "$@" > "$OUT/$f"; echo "  $f ($(wc -l < "$OUT/$f") lines)"; }

[ "$("$ADB" -s "$TV" get-state 2>&1)" = "device" ] || { echo "TV $TV not authorized/connected"; exit 1; }
echo "Saving to $OUT"

# Identity / firmware
save getprop.txt getprop
# Packages: all (with APK path, installer, uid), enabled, disabled, system, third-party, versions
save packages_all.txt pm list packages -f -i -U
save packages_enabled.txt pm list packages -e
save packages_disabled.txt pm list packages -d
save packages_system.txt pm list packages -s
save packages_thirdparty.txt pm list packages -3
save packages_versions.txt pm list packages --show-versioncode
save users.txt pm list users
# Settings (the main thing a restore needs)
save settings_global.txt settings list global
save settings_secure.txt settings list secure
save settings_system.txt settings list system
# Launcher / home routing
save home_resolve.txt cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
save home_candidates.txt cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
save leanback_launchers.txt cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER
save accessibility.txt dumpsys accessibility
save top_activity.txt dumpsys activity activities
# TV inputs / HDMI-CEC
save tv_input.txt dumpsys tv_input
save hdmi_control.txt dumpsys hdmi_control
# Resources
save meminfo.txt cat /proc/meminfo
save cpuinfo.txt cat /proc/cpuinfo
save df.txt df -h
save display.txt sh -c 'wm size; wm density'
save ps.txt ps -A -o USER,PID,RSS,NAME
save running_services.txt dumpsys activity services
save jobs.txt dumpsys jobscheduler
save device_policy.txt dumpsys device_policy

echo "--- summary ---" | tee "$OUT/SUMMARY.txt"
for p in ro.product.manufacturer ro.product.model ro.product.name ro.product.device ro.build.product \
         ro.build.version.release ro.build.version.sdk ro.build.display.id ro.build.version.name \
         ro.build.mktg.fireos ro.build.version.fireos ro.build.version.number ro.build.fingerprint \
         ro.build.date ro.product.cpu.abi ro.boot.hardware ro.board.platform; do
  v="$(grep -F "[$p]" "$OUT/getprop.txt" | sed 's/.*: \[\(.*\)\]/\1/')"
  [ -n "$v" ] && echo "$p = $v" | tee -a "$OUT/SUMMARY.txt"
done
{
  echo "packages total=$(grep -c '^package:' "$OUT/packages_all.txt") disabled=$(grep -c '^package:' "$OUT/packages_disabled.txt") thirdparty=$(grep -c '^package:' "$OUT/packages_thirdparty.txt")"
  echo "home resolves to: $(tail -n1 "$OUT/home_resolve.txt")"
  grep -E 'MemTotal|MemAvailable' "$OUT/meminfo.txt"
  grep -E ' /data$| /data ' "$OUT/df.txt"
  cat "$OUT/display.txt"
} | tee -a "$OUT/SUMMARY.txt"
echo "$OUT" > "$ROOT/backup/LATEST"
