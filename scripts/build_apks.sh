#!/usr/bin/env bash
set -e

echo "=== Building Both Android APKs via Official Gradle Toolchain (AGP 8.1.4 / SDK 34 Android 14 Release v2.6.0) ==="

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# 0. Ensure JDK 17, zip, and unzip are available
if ! command -v javac >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1 || ! command -v zip >/dev/null 2>&1; then
    echo "Installing OpenJDK 17, zip, and unzip..."
    apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends openjdk-17-jdk-headless unzip zip
fi

# 1. Resolve or bootstrap Android SDK 34 (Android 14) & Build-Tools 34.0.0
export ANDROID_HOME="${ANDROID_HOME:-/tmp/android-sdk}"
mkdir -p "$ANDROID_HOME/licenses" "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"

# Accept standard Android SDK licenses
printf "\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n" > "$ANDROID_HOME/licenses/android-sdk-license"

if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
    echo "Bootstrapping Android SDK Platform 34 (Android 14)..."
    curl -sL https://dl.google.com/android/repository/platform-34-ext7_r03.zip -o /tmp/platform-34.zip
    unzip -q /tmp/platform-34.zip -d "$ANDROID_HOME/platforms/"
    mv "$ANDROID_HOME/platforms/android-"*34* "$ANDROID_HOME/platforms/android-34" 2>/dev/null || true
    rm -f /tmp/platform-34.zip
fi

# Ensure AGP 8.1.4 treats android-34 as base SDK 34
if [ -f "$ANDROID_HOME/platforms/android-34/source.properties" ]; then
    sed -i '/AndroidVersion.ExtensionLevel/d' "$ANDROID_HOME/platforms/android-34/source.properties"
fi

if [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
    echo "Bootstrapping Android SDK Build-Tools 34.0.0..."
    curl -sL https://dl.google.com/android/repository/build-tools_r34-linux.zip -o /tmp/build-tools-34.zip
    unzip -q /tmp/build-tools-34.zip -d "$ANDROID_HOME/build-tools/"
    mv "$ANDROID_HOME/build-tools/android-14" "$ANDROID_HOME/build-tools/34.0.0" 2>/dev/null || true
    rm -f /tmp/build-tools-34.zip
fi

if [ ! -d "$ANDROID_HOME/platform-tools" ]; then
    echo "Bootstrapping Android SDK Platform-Tools..."
    curl -sL https://dl.google.com/android/repository/platform-tools-latest-linux.zip -o /tmp/platform-tools.zip
    unzip -q /tmp/platform-tools.zip -d "$ANDROID_HOME/"
    rm -f /tmp/platform-tools.zip
fi

echo "Using ANDROID_HOME=$ANDROID_HOME"

# 2. Generate Two Separate Production Release Keystores (2048-bit RSA)
RUNNER_KEYSTORE="/tmp/scream-runner-v26-release.jks"
if [ ! -f "$RUNNER_KEYSTORE" ]; then
    echo "Generating Scream Runner v2.6.0 Release Keystore..."
    keytool -genkeypair -v \
        -keystore "$RUNNER_KEYSTORE" \
        -alias runner_v26 \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass runnerrelease2026 \
        -keypass runnerrelease2026 \
        -dname "CN=ScreamRunner Arcade Studio, OU=Interactive Gaming, O=ScreamRunner Entertainment, L=Austin, ST=TX, C=US"
fi

DASH_KEYSTORE="/tmp/sams-console-v26-release.jks"
if [ ! -f "$DASH_KEYSTORE" ]; then
    echo "Generating Sam's Dashboard v2.6.0 Release Keystore..."
    keytool -genkeypair -v \
        -keystore "$DASH_KEYSTORE" \
        -alias console_v26 \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass dashboardrelease2026 \
        -keypass dashboardrelease2026 \
        -dname "CN=Sams Acoustic Telemetry, OU=Audio Engineering, O=Sams Telemetry Lab, L=Seattle, ST=WA, C=US"
fi

# 3. Run Gradle Release Build with isolated /tmp caches
chmod +x "$ROOT_DIR/android/gradlew"
rm -rf "$ROOT_DIR/android/.gradle" "$ROOT_DIR/android/build" "$ROOT_DIR/android/scream-runner/build" "$ROOT_DIR/android/sams-dashboard/build" "$ROOT_DIR/android/local.properties" /tmp/android-build

echo "--- Running Gradle :scream-runner:assembleRelease :sams-dashboard:assembleRelease ---"
"$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" \
    --project-cache-dir /tmp/gradle-project-cache \
    :scream-runner:assembleRelease \
    :sams-dashboard:assembleRelease \
    --no-daemon --console=plain

# Remove any residual .gradle or local.properties so workspace stays clean
rm -rf "$ROOT_DIR/android/.gradle" "$ROOT_DIR/android/build" "$ROOT_DIR/android/scream-runner/build" "$ROOT_DIR/android/sams-dashboard/build" "$ROOT_DIR/android/local.properties"

# 4. Strip unprotected AGP META-INF metadata, Align (4-byte page-aligned), and Sign ONCE with v1 + v2 + v3
mkdir -p "$ROOT_DIR/public/downloads"

RUNNER_UNSIGNED="/tmp/android-build/scream-runner/outputs/apk/release/scream-runner-release-unsigned.apk"
DASH_UNSIGNED="/tmp/android-build/sams-dashboard/outputs/apk/release/sams-dashboard-release-unsigned.apk"

BUILD_TOOLS_DIR="$ANDROID_HOME/build-tools/34.0.0"
if [ ! -d "$BUILD_TOOLS_DIR" ]; then
    BUILD_TOOLS_DIR="$(ls -d "$ANDROID_HOME/build-tools/"* | tail -n 1)"
fi
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
AAPT="$BUILD_TOOLS_DIR/aapt"

# Remove unprotected META-INF/com/android/build/gradle/app-metadata.properties before signing
zip -q -d "$RUNNER_UNSIGNED" "META-INF/*" 2>/dev/null || true
zip -q -d "$DASH_UNSIGNED" "META-INF/*" 2>/dev/null || true

"$ZIPALIGN" -p -f 4 "$RUNNER_UNSIGNED" "/tmp/scream-runner-aligned.apk"
"$APKSIGNER" sign \
    --ks "$RUNNER_KEYSTORE" \
    --ks-key-alias runner_v26 \
    --ks-pass pass:runnerrelease2026 \
    --key-pass pass:runnerrelease2026 \
    --min-sdk-version 21 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "$ROOT_DIR/public/downloads/scream-runner.apk" \
    "/tmp/scream-runner-aligned.apk"

"$ZIPALIGN" -p -f 4 "$DASH_UNSIGNED" "/tmp/sams-dashboard-aligned.apk"
"$APKSIGNER" sign \
    --ks "$DASH_KEYSTORE" \
    --ks-key-alias console_v26 \
    --ks-pass pass:dashboardrelease2026 \
    --key-pass pass:dashboardrelease2026 \
    --min-sdk-version 21 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "$ROOT_DIR/public/downloads/sams-dashboard.apk" \
    "/tmp/sams-dashboard-aligned.apk"

rm -f /tmp/scream-runner-aligned.apk /tmp/sams-dashboard-aligned.apk "$ROOT_DIR/public/downloads/"*.idsig

# 5. Verify Zipalign, APK Signatures (v1 + v2 + v3 with zero warnings), and Badging
echo "--- Verifying 4-Byte Zipalign ---"
"$ZIPALIGN" -c 4 "$ROOT_DIR/public/downloads/scream-runner.apk" && echo "scream-runner.apk zipalign: OK"
"$ZIPALIGN" -c 4 "$ROOT_DIR/public/downloads/sams-dashboard.apk" && echo "sams-dashboard.apk zipalign: OK"

echo "--- Verifying APK Signatures (v1 + v2 + v3) ---"
"$APKSIGNER" verify -v "$ROOT_DIR/public/downloads/scream-runner.apk"
"$APKSIGNER" verify -v "$ROOT_DIR/public/downloads/sams-dashboard.apk"

echo "--- Inspecting Scream Runner Release Badging ---"
"$AAPT" dump badging "$ROOT_DIR/public/downloads/scream-runner.apk" | grep -E "package:|sdkVersion:|targetSdkVersion:|uses-permission:|application-label:|launchable-activity:"

echo "--- Inspecting Sam's Dashboard Release Badging ---"
"$AAPT" dump badging "$ROOT_DIR/public/downloads/sams-dashboard.apk" | grep -E "package:|sdkVersion:|targetSdkVersion:|uses-permission:|application-label:|launchable-activity:"

echo "=== GRADLE RELEASE BUILD COMPLETE ==="
ls -lh "$ROOT_DIR/public/downloads/"*.apk
sha256sum "$ROOT_DIR/public/downloads/"*.apk
