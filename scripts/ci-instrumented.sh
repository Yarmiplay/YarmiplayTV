#!/usr/bin/env bash
# CI helpers for the instrumented tests on the booted emulator, straight through adb with the APKs the
# workflow already built (no second Gradle configure + install).
#
#   scripts/ci-instrumented.sh snapshot        settle a freshly booted emulator before its snapshot is saved
#   scripts/ci-instrumented.sh playback|ui     run one shard of the tests
#
# Output and, on failure, logcat go to build/ci-instrumented/.
set -uo pipefail

mode="${1:?usage: ci-instrumented.sh snapshot|playback|ui}"
runner="com.syncplaytv.test/androidx.test.runner.AndroidJUnitRunner"
out="build/ci-instrumented"
mkdir -p "$out"

cpu_sample() { adb shell head -n 1 /proc/stat | awk '{ idle = $5 + $6; total = 0; for (i = 2; i <= NF; i++) total += $i; print total, idle }'; }

# Right after boot the system is still optimizing and scanning packages; tests started then see the whole
# device stall for seconds at a time.
wait_until_idle() {
  local start=$SECONDS quiet=0 busy=100 t1 i1 t2 i2
  read -r t1 i1 < <(cpu_sample)
  while (( SECONDS - start < 120 )); do
    sleep 3
    read -r t2 i2 < <(cpu_sample)
    (( t2 > t1 )) && busy=$(( 100 * ((t2 - t1) - (i2 - i1)) / (t2 - t1) ))
    t1=$t2; i1=$i2
    if (( busy < 25 )); then quiet=$((quiet + 1)); else quiet=0; fi
    if (( quiet >= 2 )); then echo "Emulator idle after $((SECONDS - start)) s"; return; fi
  done
  echo "Emulator still ${busy}% busy after 120 s; going ahead"
}

# The workflow builds the APKs in the background while the emulator is set up.
wait_for_apks() {
  [ -f "$out/apks.started" ] || return 0
  local start=$SECONDS
  while [ ! -f "$out/apks.exit" ]; do sleep 1; done
  echo "Waited $((SECONDS - start)) s for the APK build"
  if [ "$(cat "$out/apks.exit")" != 0 ]; then
    tail -80 "$out/apks.log"
    echo "::error::Building the APKs failed"
    exit 1
  fi
}

safety_net="com.syncplaytv.screenshots.MobileScreenshotTest,com.syncplaytv.synccheck.SyncCheckTest,com.syncplaytv.tv.TvNavigationTest,com.syncplaytv.tv.TvScreenshotTest"
playback="com.syncplaytv.LocalPlaybackTest"
case "$mode" in
  snapshot)
    # The idle wait measures the emulator's own load; a build sharing the runner's CPU would skew it.
    wait_for_apks
    start=$SECONDS
    adb shell cmd package bg-dexopt-job > /dev/null
    echo "Background dexopt done in $((SECONDS - start)) s"
    wait_until_idle
    exit 0 ;;
  playback) filter=(-e class "$playback") ;;
  # Everything else, so a new test class can't be left out of CI. The safety-net classes (reference
  # screenshots, sync check, TV) only run from scripts/android-safety-net.ps1.
  ui) filter=(-e notClass "$safety_net,$playback") ;;
  *) echo "unknown mode: $mode" >&2; exit 2 ;;
esac

wait_for_apks
# A copy signed with another runner's debug key would refuse the update.
adb uninstall com.syncplaytv > /dev/null 2>&1
adb uninstall com.syncplaytv.test > /dev/null 2>&1
adb install -r -t app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
wait_until_idle
adb logcat -c

adb shell am instrument -w -r "${filter[@]}" "$runner" 2>&1 | tee "$out/$mode.txt"

# am instrument exits 0 even when tests fail; only a final "OK (N tests)" means they all passed.
if grep -Eq '^OK \([0-9]+ tests?\)' "$out/$mode.txt"; then
  grep -E '^OK \(' "$out/$mode.txt"
  exit 0
fi
adb logcat -d > "$out/$mode-logcat.txt"
echo "::error::Instrumented tests failed in the $mode shard"
grep -E '^(INSTRUMENTATION_STATUS: (test|stack)=|There w|Tests run:|[0-9]+\) )' "$out/$mode.txt" | head -60
exit 1
