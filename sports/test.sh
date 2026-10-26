#!/usr/bin/env bash
# Run the JVM unit tests, then the phone page's JavaScript tests. Optional: a JVM test class filter, e.g. test.sh TickerItemsTest
set -euo pipefail
cd "$(dirname "$0")"
JDK="$(ls -d "/c/Program Files/Microsoft/jdk-17"* | head -1)"
export JAVA_HOME="$(cygpath -w "$JDK")"
export PATH="$JDK/bin:$PATH"
GRADLE=/c/dev/tools/gradle-8.14.6/bin/gradle
"$GRADLE" --no-daemon -q testDebugUnitTest ${1:+--tests "app.lumen.sports.$1"}
# The phone page's signing code (JavaScript), against the same vectors as the Kotlin side.
[ -n "${1:-}" ] || node app/src/test/js/phone-crypto.test.js
