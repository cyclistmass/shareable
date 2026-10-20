#!/usr/bin/env bash
# Monkey test: installs the APK on a running emulator, fires random input at the app and fails
# on any crash or ANR. Used by CI (wgbg-ci.yml, "monkey" job) and runnable locally.
# Usage: tools/ci_monkey.sh path/to/app-debug.apk [events] [seed]
set -euo pipefail
APK="$1"
EVENTS="${2:-5000}"
SEED="${3:-$RANDOM}"
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
OUT_DIR="${OUTPUT_DIR:-.}"
mkdir -p "$OUT_DIR"
MONKEY_LOG="$OUT_DIR/monkey.txt"
CRASH_LOG="$OUT_DIR/crash.txt"

"$ADB" wait-for-device
# A fresh CI emulator is busy for a while after boot; let it settle so its own load isn't read as our ANR.
until [ "$("$ADB" shell getprop sys.boot_completed | tr -dc '0-9')" = "1" ]; do sleep 2; done
sleep "${SETTLE:-45}"
"$ADB" install -r "$APK"
timeout 60 "$ADB" shell am start -W --activity-clear-task -n "$PKG/.MainActivity" ${START:+--es start "$START" --ez quickbots true}   # -W waits for the first frame; START=chivalry|base jumps into a game
sleep 10
"$ADB" logcat -c
if [ "${LIVE_BOARD:-0}" = 1 ]; then
  live_ready=0
  for attempt in $(seq 1 15); do
    "$ADB" shell am broadcast -a com.mauro.wgbg.FILAMENT_VIEW -p "$PKG" --ez live true >/dev/null
    if "$ADB" logcat -d -s WGBG-3D-VIEW:I | grep -q 'live=true'; then live_ready=1; break; fi
    sleep 1
  done
  if [ "$live_ready" != 1 ]; then echo "Live board receiver did not start"; exit 1; fi
  sleep 2
fi
echo "monkey: $EVENTS events, seed $SEED"
set +e
"$ADB" shell monkey -p "$PKG" --throttle 30 --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 \
  -s "$SEED" -v "$EVENTS" > "$MONKEY_LOG" 2>&1
code=$?
set -e
tail -5 "$MONKEY_LOG"
# Random swipes can pull down the quick-settings shade and tap its tiles: undo what that can leave behind
# (airplane mode left on once cut a later online test off the network).
"$ADB" shell cmd connectivity airplane-mode disable || true
"$ADB" shell svc wifi enable || true
"$ADB" shell cmd statusbar collapse || true
"$ADB" logcat -d -b crash > "$CRASH_LOG" || true
if [ $code -ne 0 ] || grep -qE "// CRASH|// NOT RESPONDING|Monkey aborted" "$MONKEY_LOG" || grep -q "FATAL EXCEPTION" "$CRASH_LOG"; then
  echo "Monkey found a crash (seed $SEED):"
  grep -A30 -E "// CRASH|NOT RESPONDING" "$MONKEY_LOG" | head -60 || true
  cat "$CRASH_LOG" | head -60
  exit 1
fi
echo "monkey: no crash"
