#!/usr/bin/env bash
# Build a signed release APK of the Lumen Sports overlay proof of concept, using Lumen's signing key.
set -euo pipefail
cd "$(dirname "$0")"
JDK="$(ls -d "/c/Program Files/Microsoft/jdk-17"* | head -1)"
export JAVA_HOME="$(cygpath -w "$JDK")"
export PATH="$JDK/bin:$PATH"
GRADLE=/c/dev/tools/gradle-8.14.6/bin/gradle

[ -f ../launcher/keystore.properties ] || { echo "Lumen's signing key is missing: run launcher/build.sh first"; exit 1; }

"$GRADLE" --no-daemon -q assembleRelease "$@"
APK=app/build/outputs/apk/release/app-release.apk
ls -la "$APK"
