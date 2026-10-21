#!/usr/bin/env bash
# Self-play in the real app (#46): bots play back-to-back games at Max speed in each ruleset. Fails on any app
# crash or ANR, or if fewer than MIN_GAMES games finish in SECS seconds. Runs after tools/ci_monkey.sh in CI.
# Usage: tools/ci_selfplay.sh [max seconds per ruleset] [min games per ruleset]
# Stops a ruleset as soon as MIN_GAMES games have finished (it used to sleep the whole SECS).
set -euo pipefail
SECS="${1:-90}"
MIN_GAMES="${2:-1}"
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
export MSYS_NO_PATHCONV=1
fail=0
"$ADB" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
for ruleset in base chivalry; do
  "$ADB" shell am force-stop "$PKG"
  "$ADB" logcat -c
  "$ADB" logcat -b crash -c 2>/dev/null || true
  timeout 60 "$ADB" shell am start -W --activity-clear-task -n "$PKG/.MainActivity" --es start "$ruleset" --ez watch true >/dev/null
  if [ "${LIVE_BOARD:-0}" = 1 ]; then
    # The first activity frame can precede registration of the game's receiver.
    live_ready=0
    for attempt in $(seq 1 15); do
      "$ADB" shell am broadcast -a com.mauro.wgbg.FILAMENT_VIEW -p "$PKG" --ez live true >/dev/null
      if "$ADB" logcat -d -s WGBG-3D-VIEW:I | grep -q 'live=true'; then live_ready=1; break; fi
      sleep 1
    done
    if [ "$live_ready" != 1 ]; then echo "Live board receiver did not start"; exit 1; fi
  fi
  start=$(date +%s); games=0
  while [ $(( $(date +%s) - start )) -lt "$SECS" ]; do
    sleep 5
    games=$("$ADB" logcat -d -s WGBG-GAME:I | grep -c "over ruleset=$ruleset" || true)
    [ "$games" -ge "$MIN_GAMES" ] && break
    [ "$("$ADB" logcat -d -b crash | grep -c "FATAL EXCEPTION" || true)" != 0 ] && break
  done
  crash=$("$ADB" logcat -d -b crash | grep -A8 "FATAL EXCEPTION" | grep -c "$PKG" || true)
  anr=$("$ADB" logcat -d | grep -c "ANR in $PKG" || true)
  alive=$("$ADB" shell pidof "$PKG" | tr -d '\r' || true)
  echo "selfplay $ruleset: $games games in $(( $(date +%s) - start ))s (max ${SECS}s), crash=$crash anr=$anr pid=${alive:-none}"
  "$ADB" logcat -d -s WGBG-GAME:I | grep -o 'over.*' | head -5 || true
  if [ "$crash" != 0 ] || [ "$anr" != 0 ] || [ -z "$alive" ] || [ "$games" -lt "$MIN_GAMES" ]; then
    echo "SELFPLAY FAILED in $ruleset"
    "$ADB" logcat -d -b crash | grep -A20 "FATAL EXCEPTION" | head -40 || true
    fail=1
  fi
done
exit $fail
