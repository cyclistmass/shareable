#!/usr/bin/env bash
# Drive seat 0 by hooks (rolls, roads, up to 3 bank trades a turn) until a corner offers both a homestead and a knight;
# prints the spot. Event-driven (#139): reads the turn lines as they come and answers at once; resends a move that
# did not take after 6 s (a hook can be dropped mid-animation); the watchdog exits 2 with the reason and a screenshot
# when nothing moves for TS_STALL s (default 45). Starts a new game when one ends and builds towns too, for production.
# Usage: tools/seek_spot.sh [secs]   (ANDROID_SERIAL=device, default emulator-5554; ADB=adb binary)
export MSYS_NO_PATHCONV=1 ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}; A="${ADB:-adb}"
. "$(dirname "$0")/lib/turnstream.sh"
end=$((SECONDS+${1:-480})); trades=0; sent=""; sentAt=0
start_game() { ts_open; timeout 60 $A shell am start --activity-clear-task -n com.mauro.wgbg/.MainActivity --es start chivalry \
  --ei players 3 --ez autosetup true --ez quickbots true >/dev/null || { echo "the app didn't start in 60 s"; exit 2; }; }
start_game
while [ $SECONDS -lt $end ]; do
  if ! ts_wait 6; then
    # Quiet for 6 s: resend the last move once in case the hook dropped it, then let the watchdog judge.
    [ -n "$sent" ] && [ $((SECONDS - sentAt)) -ge 6 ] && { $A shell am broadcast -a com.mauro.wgbg.MOVE -p com.mauro.wgbg --es move "$sent" >/dev/null; sentAt=$SECONDS; }
    ts_check_stall seek_spot; continue
  fi
  [ $TS_OVER = 1 ] && { echo "game over, new game"; start_game; continue; }
  line=$TS_LINE
  legal=$(echo "$line" | sed 's/.*legal=//; s/ spots=.*//' | tr ',' '\n')
  both=$(comm -12 <(echo "$legal" | sed -n 's/^BUILD_SETTLEMENT://p' | sort) <(echo "$legal" | sed -n 's/^BUILD_KNIGHT://p' | sort) | head -1)
  if [ -n "$both" ]; then echo "FOUND vertex $both"; echo "$line" | grep -o "spots=.*" | tr ' ,' '\n\n' | grep -E "(SETTLEMENT|KNIGHT):$both@" ; exit 0; fi
  grep -q "ROLL:" <<< "$line" && trades=0
  pick=$(echo "$legal" | grep -m1 -E '^(ROLL|BUILD_CITY|BUILD_ROAD):')
  [ -z "$pick" ] && [ $trades -lt 3 ] && pick=$(echo "$legal" | grep '^MARITIME_TRADE:' | shuf -n1) && [ -n "$pick" ] && trades=$((trades+1))
  [ -z "$pick" ] && pick=$(echo "$legal" | grep -m1 '^END_TURN:')
  [ -z "$pick" ] && pick=$(echo "$legal" | head -1)
  [ -z "$pick" ] && continue
  sent=$pick; sentAt=$SECONDS; $A shell am broadcast -a com.mauro.wgbg.MOVE -p com.mauro.wgbg --es move "$pick" >/dev/null
done; echo TIMEOUT; exit 1
