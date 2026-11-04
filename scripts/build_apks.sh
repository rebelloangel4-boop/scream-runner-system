#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
: "${ANDROID_HOME:?ANDROID_HOME must point to an installed Android SDK}"
chmod +x android/gradlew
android/gradlew -p android --no-daemon --console=plain :scream-runner:assembleDebug :sams-dashboard:assembleDebug
RUNNER="$ROOT_DIR/android/scream-runner/build/outputs/apk/debug/scream-runner-debug.apk"
DASH="$ROOT_DIR/android/sams-dashboard/build/outputs/apk/debug/sams-dashboard-debug.apk"
test -s "$RUNNER"
test -s "$DASH"
mkdir -p "$ROOT_DIR/public/downloads"
cp "$RUNNER" "$ROOT_DIR/public/downloads/scream-runner.apk"
cp "$DASH" "$ROOT_DIR/public/downloads/sams-dashboard.apk"
echo "Built installable debug APKs (NOT production-signed releases):"
sha256sum "$ROOT_DIR/public/downloads/scream-runner.apk" "$ROOT_DIR/public/downloads/sams-dashboard.apk"
