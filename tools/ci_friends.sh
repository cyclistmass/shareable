#!/usr/bin/env bash
# Two phones on one CI runner for tools/friends_sim.sh (#72: a friends game, its rematch, then invites after a restart).
# The workflow boots the first emulator (emulator-5554) read-only from the cached snapshot; this boots a second copy
# of the same AVD, also read-only, as emulator-5556, installs the debug APK on it, plays friends_sim, and stops the
# second phone on any exit. The Firebase emulators must already run on the runner (8080/9099).
# Usage (CI, project root): tools/ci_friends.sh <apk> [ruleset: base|chivalry] [timeout seconds for the game]
set -eEuo pipefail
trap 'echo "FAILED at line $LINENO: $BASH_COMMAND"' ERR
APK="$1"; RULESET="${2:-base}"; LIMIT="${3:-}"
AVD="${AVD:-$(adb -s emulator-5554 emu avd name | head -1 | tr -d '\r')}"
"$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port 5556 -read-only -no-window -gpu swiftshader_indirect -noaudio \
  -no-boot-anim -camera-back none -no-snapshot-save > emu2.log 2>&1 &
EMU2=$!
trap 'adb -s emulator-5556 emu kill >/dev/null 2>&1 || kill $EMU2 2>/dev/null || true' EXIT
# The snapshot boot takes ~30 s on a runner; a cold boot up to ~3 min. Give up after 5 with the emulator's log.
t0=$SECONDS
until [ "$(adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
  kill -0 $EMU2 2>/dev/null || { echo "second emulator exited"; tail -20 emu2.log; exit 1; }
  [ $((SECONDS - t0)) -lt 300 ] || { echo "second emulator not booted after 300 s"; tail -20 emu2.log; exit 1; }
  sleep 3
done
echo "second emulator booted in $((SECONDS - t0)) s"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb -s emulator-5556 shell settings put global $k 0; done
adb -s emulator-5556 install -r "$APK"
HOST_SERIAL=emulator-5554 GUEST_SERIAL=emulator-5556 bash tools/friends_sim.sh "$RULESET" $LIMIT
