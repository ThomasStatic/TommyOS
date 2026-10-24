#!/usr/bin/env bash
# Run the JVM unit tests. Optional: a test class filter, e.g. test.sh TickerItemsTest
set -euo pipefail
cd "$(dirname "$0")"
JDK="$(ls -d "/c/Program Files/Microsoft/jdk-17"* | head -1)"
export JAVA_HOME="$(cygpath -w "$JDK")"
export PATH="$JDK/bin:$PATH"
GRADLE=/c/dev/tools/gradle-8.14.6/bin/gradle
"$GRADLE" --no-daemon -q testDebugUnitTest ${1:+--tests "app.lumen.sports.$1"}
