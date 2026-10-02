#!/usr/bin/env bash
# Runs one CI shard of the instrumented tests on the booted emulator, straight through adb with the APKs the
# workflow already built (no second Gradle configure + install).
#
#   scripts/ci-instrumented.sh playback|ui
#
# Output and, on failure, logcat go to build/ci-instrumented/.
set -uo pipefail

shard="${1:?usage: ci-instrumented.sh playback|ui}"
runner="com.syncplaytv.test/androidx.test.runner.AndroidJUnitRunner"
out="build/ci-instrumented"
mkdir -p "$out"

# The safety-net classes (reference screenshots, sync check, TV) only run from scripts/android-safety-net.ps1.
safety_net="com.syncplaytv.screenshots.MobileScreenshotTest,com.syncplaytv.synccheck.SyncCheckTest,com.syncplaytv.tv.TvNavigationTest,com.syncplaytv.tv.TvScreenshotTest"
playback="com.syncplaytv.LocalPlaybackTest"
case "$shard" in
  playback) filter=(-e class "$playback") ;;
  # Everything else, so a new test class can't be left out of CI.
  ui) filter=(-e notClass "$safety_net,$playback") ;;
  *) echo "unknown shard: $shard" >&2; exit 2 ;;
esac

# A copy signed with another runner's debug key would refuse the update.
adb uninstall com.syncplaytv > /dev/null 2>&1
adb uninstall com.syncplaytv.test > /dev/null 2>&1
adb install -r -t app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb logcat -c

adb shell am instrument -w -r "${filter[@]}" "$runner" 2>&1 | tee "$out/$shard.txt"

# am instrument exits 0 even when tests fail; only a final "OK (N tests)" means they all passed.
if grep -Eq '^OK \([0-9]+ tests?\)' "$out/$shard.txt"; then
  grep -E '^OK \(' "$out/$shard.txt"
  exit 0
fi
adb logcat -d > "$out/$shard-logcat.txt"
echo "::error::Instrumented tests failed in the $shard shard"
grep -E '^(INSTRUMENTATION_STATUS: (test|stack)=|There w|Tests run:|[0-9]+\) )' "$out/$shard.txt" | head -60
exit 1
