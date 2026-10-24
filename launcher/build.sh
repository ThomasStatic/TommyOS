#!/usr/bin/env bash
# Build a signed release APK of Lumen. First run creates a local signing key; KEEP lumen-release.jks
# and keystore.properties — Android only accepts updates signed with the same key.
set -euo pipefail
cd "$(dirname "$0")"
JDK="$(ls -d "/c/Program Files/Microsoft/jdk-17"* | head -1)"
export JAVA_HOME="$(cygpath -w "$JDK")"
export PATH="$JDK/bin:$PATH"
GRADLE=/c/dev/tools/gradle-8.14.6/bin/gradle

if [ ! -f keystore.properties ]; then
  PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  keytool -genkeypair -keystore lumen-release.jks -alias lumen -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Lumen" -storepass "$PASS" -keypass "$PASS" >/dev/null 2>&1
  printf 'storeFile=lumen-release.jks\nstorePassword=%s\nkeyAlias=lumen\nkeyPassword=%s\n' "$PASS" "$PASS" > keystore.properties
  echo "Created signing key lumen-release.jks (password in keystore.properties)."
fi

"$GRADLE" --no-daemon -q assembleRelease "$@"
APK=app/build/outputs/apk/release/app-release.apk
ls -la "$APK"
