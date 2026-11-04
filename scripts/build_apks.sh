#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
chmod +x android/gradlew
android/gradlew -p android --no-daemon --console=plain :scream-runner:assembleDebug :sams-dashboard:assembleDebug
RUNNER_DIR="$ROOT_DIR/android/scream-runner/build/outputs/apk/debug"
DASH_DIR="$ROOT_DIR/android/sams-dashboard/build/outputs/apk/debug"
echo "Scream Runner APK output:"
find "$RUNNER_DIR" -maxdepth 1 -type f -print
echo "Sam's Dashboard APK output:"
find "$DASH_DIR" -maxdepth 1 -type f -print
mapfile -t runner_apks < <(find "$RUNNER_DIR" -maxdepth 1 -type f -name '*.apk' | sort)
mapfile -t dash_apks < <(find "$DASH_DIR" -maxdepth 1 -type f -name '*.apk' | sort)
if [ "${#runner_apks[@]}" -ne 1 ] || [ "${#dash_apks[@]}" -ne 1 ]; then
  echo "ERROR: Expected exactly one APK from each module" >&2
  exit 1
fi
test -s "${runner_apks[0]}"
test -s "${dash_apks[0]}"
mkdir -p "$ROOT_DIR/public/downloads"
cp "${runner_apks[0]}" "$ROOT_DIR/public/downloads/scream-runner.apk"
cp "${dash_apks[0]}" "$ROOT_DIR/public/downloads/sams-dashboard.apk"
echo "Built installable DEBUG APKs, not production-signed releases:"
sha256sum "$ROOT_DIR/public/downloads/scream-runner.apk" "$ROOT_DIR/public/downloads/sams-dashboard.apk"
