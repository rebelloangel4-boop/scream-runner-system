#!/usr/bin/env bash
set -e

echo "=== Building Production Android APKs with Build-Tools 33.0.2 & Libraries ==="

ANDROID_JAR="/usr/lib/android-sdk/platforms/android-33/android.jar"
BUILD_TOOLS="/usr/lib/android-sdk/build-tools/33.0.2"
LIBS="/usr/share/android-libs"
KEYSTORE="/tmp/debug.keystore"

mkdir -p public/downloads

if [ ! -f "$KEYSTORE" ]; then
    echo "Generating debug keystore..."
    keytool -genkey -v -keystore "$KEYSTORE" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

# 1. Build Scream Runner APK
echo "--- Building Scream Runner (com.screamrunner.game) ---"
rm -rf /tmp/build_runner && mkdir -p /tmp/build_runner/{compiled_res,gen,classes,dex}

# aapt2 compile resources
$BUILD_TOOLS/aapt2 compile --dir android/scream-runner/src/main/res -o /tmp/build_runner/compiled_res.zip

# aapt2 link to produce R.java and packaged resources APK
$BUILD_TOOLS/aapt2 link -I "$ANDROID_JAR" \
    --manifest android/scream-runner/AndroidManifest.xml \
    --java /tmp/build_runner/gen \
    -o /tmp/build_runner/base.apk \
    /tmp/build_runner/compiled_res.zip \
    --auto-add-overlay

# Compile Java source code and R.java with OkHttp & Gson dependencies
javac -source 8 -target 8 \
    -cp "$ANDROID_JAR:$LIBS/*" \
    -d /tmp/build_runner/classes \
    $(find android/scream-runner/src/main/java /tmp/build_runner/gen -name "*.java")

# Dex compilation with d8 incorporating dependencies (OkHttp, Okio, Kotlin-stdlib, Gson)
$BUILD_TOOLS/d8 --min-api 26 \
    --output /tmp/build_runner/dex \
    $LIBS/*.jar \
    $(find /tmp/build_runner/classes -name "*.class")

# Package DEX into APK
cp /tmp/build_runner/base.apk /tmp/build_runner/unaligned.apk
(cd /tmp/build_runner/dex && zip -u /tmp/build_runner/unaligned.apk classes*.dex)

# 4-byte page align
$BUILD_TOOLS/zipalign -f -p 4 /tmp/build_runner/unaligned.apk /tmp/build_runner/aligned.apk

# Sign with apksigner (v2 and v3 signatures)
$BUILD_TOOLS/apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out public/downloads/scream-runner.apk /tmp/build_runner/aligned.apk

echo "Verified scream-runner.apk:"
$BUILD_TOOLS/apksigner verify -v public/downloads/scream-runner.apk
$BUILD_TOOLS/aapt dump badging public/downloads/scream-runner.apk | head -n 8

# 2. Build Sam's Dashboard APK
echo "--- Building Sam's Dashboard (com.samsdashboard.admin) ---"
rm -rf /tmp/build_dashboard && mkdir -p /tmp/build_dashboard/{compiled_res,gen,classes,dex}

# aapt2 compile resources
$BUILD_TOOLS/aapt2 compile --dir android/sams-dashboard/src/main/res -o /tmp/build_dashboard/compiled_res.zip

# aapt2 link to produce R.java and base APK
$BUILD_TOOLS/aapt2 link -I "$ANDROID_JAR" \
    --manifest android/sams-dashboard/AndroidManifest.xml \
    --java /tmp/build_dashboard/gen \
    -o /tmp/build_dashboard/base.apk \
    /tmp/build_dashboard/compiled_res.zip \
    --auto-add-overlay

# Compile Java source code and R.java
javac -source 8 -target 8 \
    -cp "$ANDROID_JAR:$LIBS/*" \
    -d /tmp/build_dashboard/classes \
    $(find android/sams-dashboard/src/main/java /tmp/build_dashboard/gen -name "*.java")

# Dex compilation with d8
$BUILD_TOOLS/d8 --min-api 26 \
    --output /tmp/build_dashboard/dex \
    $LIBS/*.jar \
    $(find /tmp/build_dashboard/classes -name "*.class")

# Package DEX into APK
cp /tmp/build_dashboard/base.apk /tmp/build_dashboard/unaligned.apk
(cd /tmp/build_dashboard/dex && zip -u /tmp/build_dashboard/unaligned.apk classes*.dex)

# Align & Sign
$BUILD_TOOLS/zipalign -f -p 4 /tmp/build_dashboard/unaligned.apk /tmp/build_dashboard/aligned.apk
$BUILD_TOOLS/apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out public/downloads/sams-dashboard.apk /tmp/build_dashboard/aligned.apk

echo "Verified sams-dashboard.apk:"
$BUILD_TOOLS/apksigner verify -v public/downloads/sams-dashboard.apk
$BUILD_TOOLS/aapt dump badging public/downloads/sams-dashboard.apk | head -n 8

echo "=== BUILD COMPLETE ==="
ls -lh public/downloads/*.apk
sha256sum public/downloads/*.apk
