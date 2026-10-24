#!/usr/bin/env bash
# Disable packages one at a time; after each, wait and check for new crashes and a sane foreground.
# Stops at the first package that causes a new crash (that package stays disabled for inspection).
# Usage: scripts/disable_batch.sh pkg1 pkg2 ...
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WAIT="${WAIT:-8}"
export MSYS_NO_PATHCONV=1
sh_() { "$ADB" -s "$TV" shell "$@" < /dev/null 2>&1 | tr -d '\r'; }
crashes() { sh_ logcat -d -b crash | grep -E 'Process: [a-z]|Fatal signal'; }

for p in "$@"; do
  before="$(crashes)"
  bash "$ROOT/scripts/pkg.sh" disable "$p" < /dev/null || { echo "   (refused/failed; continuing)"; continue; }
  sleep "$WAIT"
  new="$(diff <(echo "$before") <(crashes) | grep '^>' | sed 's/^> //')"
  fg="$(sh_ dumpsys activity activities | grep -m1 mResumedActivity | grep -oE '[a-z0-9.]+/[A-Za-z0-9_.]+')"
  echo "   foreground: ${fg:-none}"
  if [ -n "$new" ]; then
    echo "   !! NEW CRASH after disabling $p — stopping:"; echo "$new" | cut -c1-200 | sed 's/^/      /'
    exit 3
  fi
done
echo "batch complete"
