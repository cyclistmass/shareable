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
if grep -Eq "^OK \([0-9]+ tests?\)" <<< "$out" && grep -q "OnlineTwoClientTest" <<< "$out"; then echo "online two-client test: PASS"; else echo "online two-client test: FAIL"; exit 1; fi
