#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
chmod +x android/gradlew
android/gradlew -p android --no-daemon --console=plain :scream-runner:assembleDebug :sams-dashboard:assembleDebug
# android/build.gradle explicitly overrides buildDir to /tmp/android-build/<module>
RUNNER="/tmp/android-build/scream-runner/outputs/apk/debug/scream-runner-debug.apk"
DASH="/tmp/android-build/sams-dashboard/outputs/apk/debug/sams-dashboard-debug.apk"
if [ ! -s "$RUNNER" ] || [ ! -s "$DASH" ]; then
  echo "Expected APKs missing. Actual outputs:" >&2
  find /tmp/android-build -type f -name '*.apk' -print >&2 || true
  exit 1
fi
mkdir -p "$ROOT_DIR/public/downloads"
cp "$RUNNER" "$ROOT_DIR/public/downloads/scream-runner.apk"
cp "$DASH" "$ROOT_DIR/public/downloads/sams-dashboard.apk"
echo "Compiled DEBUG APKs (not production releases):"
sha256sum "$ROOT_DIR/public/downloads/scream-runner.apk" "$ROOT_DIR/public/downloads/sams-dashboard.apk"
