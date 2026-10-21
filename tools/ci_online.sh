#!/usr/bin/env bash
# Online play end to end in CI (#35): runs OnlineTwoClientTest on the emulator against the Firebase emulators on
# the host (10.0.2.2): two clients host and join a room and play a whole game; both must end in the same state.
# Needs the app APK installed (tools/ci_monkey.sh does that) and the emulators running:
#   firebase emulators:start --only firestore,auth --project demo-wgbg
# Usage: tools/ci_online.sh path/to/app-debug-androidTest.apk [firebase project]
set -euo pipefail
TEST_APK="$1"
PROJECT="${2:-demo-wgbg}"
ADB="${ADB:-adb}"
export MSYS_NO_PATHCONV=1
"$ADB" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
"$ADB" install -r "$TEST_APK" >/dev/null
out=$("$ADB" shell am instrument -w -e firebaseProject "$PROJECT" -e firebaseHost 10.0.2.2 \
  com.mauro.wgbg.test/androidx.test.runner.AndroidJUnitRunner 2>&1 || true)
echo "$out" | tail -25
# Every instrumented test must pass (the live-board test runs here too), and the online game must be among them.
if echo "$out" | grep -Eq "^OK \([0-9]+ tests?\)" && echo "$out" | grep -q "OnlineTwoClientTest"; then echo "online two-client test: PASS"; else
  # Evidence beside the shard log (the device workflow uploads *.png, *.xml and *.logcat.txt): every log buffer (the
  # events one has each activity's create and destroy), the screen and its dump. Run 39675784727 had only the assert.
  f="${OUT:-.}/online-fail"
  "$ADB" logcat -d -b all > "$f.logcat.txt" 2>/dev/null || true
  "$ADB" exec-out screencap -p > "$f.png" 2>/dev/null || true
  "$ADB" exec-out uiautomator dump /dev/tty > "$f.xml" 2>/dev/null || true
  echo "online two-client test: FAIL (log, screen and dump: $f.*)"; exit 1; fi
