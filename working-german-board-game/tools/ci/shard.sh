#!/usr/bin/env bash
# One device shard on a CI runner: the per-push CI, the nightly full playtest and the remote release check all run their
# shards through here, so a shard means the same thing in each. The emulator is up and the debug APK is in apk/
# (the instrumented APK too for online); online and friends need the Firebase emulators on 8080/9099.
# Automated checks only: none of this counts as user testing (AGENTS.md).
# Usage (project root): tools/ci/shard.sh <shard>      shards: see the case below; `tools/ci/shard.sh list` prints them.
set -euo pipefail
SHARD="${1:?shard name}"
APK=apk/app-debug.apk
ADB="${ADB:-adb}"
# Seeds that change every night but are printed, so a failing monkey run can be replayed with the same seed.
RUN="${GITHUB_RUN_NUMBER:-$RANDOM}"

if [ "$SHARD" = list ]; then grep -oE '^  [a-z0-9|-]+\)' "$0" | tr -d ' )' | tr '|' '\n'; exit 0; fi
# Everything also lands in shard.log (uploaded with the shard), with the time each step started.
exec > >(tee -a "${SHARD_LOG:-shard.log}") 2>&1
echo "== $SHARD on $("$ADB" shell getprop ro.build.version.release | tr -d '\r'), $(date -u +%H:%M:%S)"

# Long shards read the log back many times: the default ring buffer drops a long run's early game-over and ANR lines.
"$ADB" logcat -G 32M >/dev/null 2>&1 || true
# The APK carries the Firebase config, as players get it: every launch (monkey's too) talks to the emulators instead
# of the live project, so a random tap on Send report or Host can't write there. With no emulators up it just fails.
"$ADB" shell setprop debug.wgbg.firebase emu
"$ADB" install -r "$APK"

monkey_long() {   # three seeds of 20,000 events, starting in a game of the ruleset
  local start="$1" i
  for i in 1 2 3; do SETTLE=1 START="$start" bash tools/ci_monkey.sh "$APK" 20000 $((RUN * 10 + i)); done
}

case "$SHARD" in
  # ---- per push (minutes each) ----
  monkey)
    SETTLE=10 bash tools/ci_monkey.sh "$APK" 4000
    SETTLE=1 START=chivalry bash tools/ci_monkey.sh "$APK" 3000 ;;
  selfplay) bash tools/ci_selfplay.sh 120 3 ;;
  touch-base) bash tools/ci_touchplay.sh 100 10 "base:3 base:4" ;;
  touch-chivalry) bash tools/ci_touchplay.sh 100 10 "chivalry:3 chivalry:4" ;;
  play-to-end)
    STALL=60 bash tools/play_to_end.sh chivalry 4 900
    bash tools/ci_reach.sh end ;;
  trade)
    bash tools/ci_trade.sh
    bash tools/ci_keyboard.sh ;;
  withdraw) bash tools/ci_withdraw.sh 300 "base chivalry" ;;
  reach) bash tools/ci_reach.sh ;;
  online) bash tools/ci_online.sh apk/app-debug-androidTest.apk demo-wgbg ;;
  friends|friends-base) bash tools/ci_friends.sh "$APK" base ;;
  friends-chivalry) bash tools/ci_friends.sh "$APK" chivalry ;;
  layout-base) MODES=base CUTOUTS="" bash tools/ci_layout.sh ;;
  layout-chivalry) MODES=chivalry CUTOUTS="" bash tools/ci_layout.sh ;;
  layout-cutout) FONTS="" bash tools/ci_layout.sh ;;
  # OUT=.: their screenshots land where the upload step looks.
  names) OUT=. bash tools/names_check.sh "English queens" "Elizabeth I,Charlotte,Catherine" ;;
  colours) OUT=. SMALL=1 bash tools/colours_check.sh ;;
  # The latest release, a game saved on it, this APK installed over it: the game resumes with the same names and colours.
  update) bash tools/ci_update.sh "$APK" ;;

  # ---- nightly: whole games, as long as they take ----
  # A human seat tapped like a finger until someone wins (FULL=1: the game must reach its end screen).
  touch-full-base-3) FULL=1 bash tools/ci_touchplay.sh 3600 10 "base:3" ;;
  touch-full-base-4) FULL=1 bash tools/ci_touchplay.sh 3600 10 "base:4" ;;
  touch-full-chivalry-3) FULL=1 bash tools/ci_touchplay.sh 5400 10 "chivalry:3" ;;
  touch-full-chivalry-4) FULL=1 bash tools/ci_touchplay.sh 5400 10 "chivalry:4" ;;
  # Bots in every seat to the end screen, then the game-over panel and the dice histogram by touch.
  end-base) STALL=120 bash tools/play_to_end.sh base 3 2400; bash tools/ci_reach.sh end ;;
  end-chivalry) STALL=120 bash tools/play_to_end.sh chivalry 4 3600; bash tools/ci_reach.sh end ;;
  # 50 bot games back to back at Max speed.
  selfplay-base) RULESETS=base bash tools/ci_selfplay.sh 12600 50 ;;
  selfplay-chivalry) RULESETS=chivalry bash tools/ci_selfplay.sh 14400 50 ;;
  monkey-base) monkey_long base ;;
  monkey-chivalry) monkey_long chivalry ;;
  layout) bash tools/ci_layout.sh ;;
  *) echo "unknown shard: $SHARD (tools/ci/shard.sh list)"; exit 2 ;;
esac
