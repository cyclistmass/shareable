#!/usr/bin/env bash
# Touch play (Mauro, 2026-10-10: "test the game better, I crashed it on move 2"): a real human seat played by
# tapping the screen, in each ruleset. Board moves are tapped at the pixel the app reports for them (the
# WGBG-TURN line's spots=, debug builds), twice (select, then confirm), exactly like a finger (a knight or town opens
# its menu instead: a line, then the check); Roll and
# End turn are tapped by their labels; only moves with no single tap target (discards, card choices, steals)
# go through the agent hook. Fails on any crash or ANR, or if the human seat gets stuck.
# Covers base and Age of Chivalry with 3 players (how Mauro plays: you + 2 bots) and with 4.
# Usage: tools/ci_touchplay.sh [seconds per game] [min human moves per game] [games, e.g. "base:3 chivalry:3"]
# With FULL=1 each game runs until it is won (the app logs WGBG-GAME at game over) or the time runs out,
# and the game must reach its end.
set -uo pipefail
SECS="${1:-150}"
MIN_MOVES="${2:-12}"
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
export MSYS_NO_PATHCONV=1
fail=0

tap() { "$ADB" shell input tap "$1" "$2" </dev/null; } # adb shell eats stdin inside a read loop otherwise
. "$(dirname "$0")/lib/uiread.sh"   # tap_text: the app's own screen read (0.2 s), uiautomator as the fallback
UI_TAP_WAIT=0   # the turn lines say when the tap took
# A failure's screen, screen read, log and the last moves tried land in the working dir (or $OUT), where CI uploads
# *.png, *.xml and *.txt from, as ci_reach.sh does: the 0.3.5-pre2 nightly's stuck games left only a line.
OUT="${OUT:-.}"; TRACE="$OUT/touchplay-trace.txt"; : > "$TRACE"
try() { echo "$(date +%T) $*" >> "$TRACE"; }
evidence() { "$ADB" exec-out screencap -p > "$OUT/touchplay-$1.png" 2>/dev/null; dump > "$OUT/touchplay-$1.xml" 2>/dev/null
  "$ADB" logcat -d -t 3000 > "$OUT/touchplay-$1.logcat.txt" 2>/dev/null; tail -40 "$TRACE" > "$OUT/touchplay-$1.trace.txt"; }
# The tap-menu lines (TapTargets.kt) the legal moves allow: a knight's Supply, Promote, Move, Chase; a town's three
# improvements (by track: 0 trade, 1 politics, 2 science) and Ramparts.
menu_tags() { local m; for m in $legal; do case "${m%%:*}" in
  ACTIVATE_KNIGHT) echo tap-supply;; PROMOTE_KNIGHT) echo tap-promote;; MOVE_KNIGHT|DISPLACE_KNIGHT) echo tap-move;;
  CHASE_ROBBER) echo tap-chase;; BUILD_CITY_WALL) echo tap-ramparts;;
  IMPROVE_CITY) case $(( ${m#*:} & 255 )) in 0) echo tap-guilds;; 1) echo tap-crown;; 2) echo tap-academy;; esac;;
  esac; done | sort -u; }
crashed() { [ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" != 0 ]; }

GAMES="${3:-base:3 chivalry:3 base:4 chivalry:4}"
FULL="${FULL:-0}"
for game in $GAMES; do
  ruleset=${game%:*}; players=${game#*:}
  "$ADB" shell am force-stop $PKG
  "$ADB" shell pm clear $PKG >/dev/null
  "$ADB" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  "$ADB" logcat -c; "$ADB" logcat -b crash -c 2>/dev/null || true
  timeout 60 "$ADB" shell am start -W --activity-clear-task -n $PKG/.MainActivity --es start "$ruleset" --ei players "$players" --ez quickbots true >/dev/null
  sleep 4
  moves=0; taps=0; menus=0; hooks=0; last=""; prev=""; same=0; start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$SECS" ]; do
    if crashed; then echo "touchplay $ruleset/${players}p: CRASH after $moves moves"; fail=1; break; fi
    over=$("$ADB" logcat -d -s WGBG-GAME:I | grep -o 'over.*' | tail -1)
    [ -n "$over" ] && { echo "touchplay $ruleset/${players}p: game over after $moves human moves: $over"; break; }
    line=$("$ADB" logcat -d -s WGBG-TURN:I | tail -1)
    if [ -z "$line" ] || [ "$line" = "$last" ]; then
      same=$((same + 1))
      # The bots are playing, or a move is animating; a human prompt that never changes means we're stuck.
      [ "$same" -gt 60 ] && { echo "touchplay $ruleset/${players}p: stuck at: ${line:0:600}"; evidence "stuck-$ruleset-$players"; fail=1; break; }
      # My move that didn't take (a drifting cloud caught the tap, or a move was still animating): a player taps
      # again, so try another move every 6 s.
      [ -n "$line" ] && [ $((same % 6)) = 5 ] && last=""
      sleep 1; continue
    fi
    [ "$line" = "$prev" ] || same=0; last="$line"; prev="$line"
    legal=$(echo "$line" | sed -n 's/.* legal=\([^ ]*\).*/\1/p' | tr ',' '\n' | grep -v -E '^(OFFER_TRADE|OFFER_TRADE_CK|COUNTER_TRADE)')
    spots=$(echo "$line" | sed -n 's/.* spots=\([^ ]*\).*/\1/p' | tr ';' '\n')
    # Prefer what a person does most: build on the board, roll, end the turn.
    if [ -n "$spots" ] && [ $((RANDOM % 4)) != 0 ]; then
      pick=$(echo "$spots" | shuf -n 1); xy=${pick#*@}; try "spot $pick"
      tap "${xy%,*}" "${xy#*,}"; sleep 0.6
      # A knight or a town opens its menu (0.3.5): pick a line it can do, then the check; Move picks the knight, and
      # the next spot tapped is where it goes. Anything else takes the second tap that confirms it, as before.
      scr=$(dump)
      if grep -q 'resource-id="tap-menu-' <<< "$scr"; then
        # Only a line a legal move stands for: the screen read marks the engraved (disabled) lines clickable too, and a
        # knight that can only be supplied got Promote, Move or Chase picked over and over (0.3.5-pre2 nightly, stuck).
        entry=$(grep 'clickable="true"' <<< "$scr" | grep -o 'resource-id="tap-[a-z]*"' | cut -d'"' -f2 | grep -x -F -f <(menu_tags) | shuf -n 1)
        try "menu: $entry"
        if [ -n "$entry" ] && tap_id "$entry"; then menus=$((menus + 1)); tap_id tap-confirm || true; fi
      else
        tap "${xy%,*}" "${xy#*,}"
      fi
      taps=$((taps + 1))
    elif grep -q '^ROLL:' <<< "$legal" && tap_text "Roll"; then taps=$((taps + 1)); try "tap Roll"
    elif grep -q '^END_TURN:' <<< "$legal" && [ $((RANDOM % 3)) != 0 ] && tap_text "End turn"; then taps=$((taps + 1)); try "tap End turn"
    else
      mv=$(echo "$legal" | shuf -n 1)
      try "hook $mv"
      [ -n "$mv" ] && "$ADB" shell am broadcast -a $PKG.MOVE -p $PKG --es move "$mv" >/dev/null && hooks=$((hooks + 1))
    fi
    moves=$((moves + 1))
    sleep 1.2
  done
  anr=$("$ADB" logcat -d | grep -c "ANR in $PKG" || true)
  alive=$("$ADB" shell pidof $PKG | tr -d '\r')
  echo "touchplay $ruleset/${players}p: $moves human moves ($taps by touch, $menus through a tap menu, $hooks by hook) in ${SECS}s, anr=$anr pid=${alive:-none}"
  ended=$("$ADB" logcat -d -s WGBG-GAME:I | grep -c 'over' || true)
  if crashed || [ "$anr" != 0 ] || [ -z "$alive" ] || [ "$moves" -lt "$MIN_MOVES" ] || { [ "$FULL" = 1 ] && [ "$ended" = 0 ]; }; then
    echo "TOUCHPLAY FAILED in $ruleset with $players players"
    "$ADB" logcat -d -b crash | grep -A30 "FATAL EXCEPTION" | head -45
    fail=1
  fi
done
exit $fail
