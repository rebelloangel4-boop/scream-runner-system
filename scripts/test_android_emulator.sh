#!/usr/bin/env bash
set -euo pipefail
mkdir -p test-results
for module in scream-runner sams-dashboard; do
  if [ "$module" = scream-runner ]; then
    app=com.screamrunner.arcade
    runner=com.screamrunner.game.RegressionInstrumentation
  else
    app=com.sams.telemetry.console
    runner=com.samsdashboard.admin.RegressionInstrumentation
  fi
  adb install -r "/tmp/android-build/$module/outputs/apk/debug/$module-debug.apk"
  adb install -r "/tmp/android-build/$module/outputs/apk/androidTest/debug/$module-debug-androidTest.apk"
  adb shell am instrument -w "$app.test/$runner" | tee "test-results/$module.txt"
  rg 'PASS [0-9]+ executed' "test-results/$module.txt"
  if rg 'FAIL|INSTRUMENTATION_FAILED' "test-results/$module.txt"; then exit 1; fi
done
adb logcat -d -s AndroidRuntime > test-results/android-crashes.txt
if rg 'FATAL EXCEPTION' test-results/android-crashes.txt; then exit 1; fi
