#!/usr/bin/env bash
# Withdraw by touch (#114): you offer a card to the table, then tap Withdraw while the bots are still answering.
# Bots at their normal pace (no quickbots) and AI pace 5 s, so a counter holds long enough to beat. Setup, rolls and other
# moves go through the agent hook; the offer and the Withdraw are taps. Passes when the log shows the offer
# withdrawn (CANCEL_TRADE by you) before every bot answered, and the game is back on your turn.
# Usage: tools/ci_withdraw.sh [seconds per ruleset] [rulesets, default "base chivalry"]
set -uo pipefail
SECS="${1:-300}"
RULESETS="${2:-base chivalry}"
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
export MSYS_NO_PATHCONV=1
fail=0; rfail=0
. "$(dirname "$0")/lib/turnstream.sh" # turn lines as they come + stall watchdog (#139)

dump() { for _ in 1 2 3; do "$ADB" shell rm -f /sdcard/ui.xml; "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local x; x=$("$ADB" shell cat /sdcard/ui.xml 2>/dev/null); case "$x" in *hierarchy*) echo "$x" | tr '>' '\n'; return;; esac; sleep 1; done; }
centre() { grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 | tr -c '0-9\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }
at() { dump | grep "\(text\|content-desc\)=\"$1\"" | centre; }
tap_text() { local xy; xy=$(at "$1"); [ -n "$xy" ] || return 1; "$ADB" shell input tap $xy; sleep 0.8; }
# A Settings row below the fold (CI's 320 dp tall screen) is scrolled to in the open panel, as a player would.
# wm size gives the portrait size; the app is landscape, so the long side is the width (a swipe from below the screen does nothing).
panel_tap() { tap_text "$1" && return 0; local w h; read -r w h < <("$ADB" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' ')
  [ "$w" -lt "$h" ] && read -r w h <<< "$h $w"
  for _ in 1 2 3 4; do "$ADB" shell input swipe $((w * 7 / 10)) $((h * 4 / 5)) $((w * 7 / 10)) $((h * 3 / 10)) 400; sleep 0.8; tap_text "$1" && return 0; done; return 1; }
crashed() { [ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" != 0 ]; }
turn_line() { "$ADB" logcat -d -s WGBG-TURN:I | tail -1; }
move() { LASTMOVE=$1; "$ADB" shell am broadcast -a $PKG.MOVE -p $PKG --es move "$1" >/dev/null; }
# Steps are kept and printed when a ruleset fails, so a CI log says why without a rerun.
TRAIL=(); say() { TRAIL+=("$(date +%T) $*"); [ ${#TRAIL[@]} -gt 25 ] && TRAIL=("${TRAIL[@]:1}"); [ -n "${VERBOSE:-}" ] && echo "  · $*"; return 0; }
bad() { echo "withdraw $ruleset: FAILED: $*"; fail=1; rfail=1; [ -n "${VERBOSE:-}" ] || printf "  · %s\n" "${TRAIL[@]}"; TRAIL=()
  "$ADB" exec-out screencap -p > "${OUT:-.}/withdraw-fail-$ruleset.png" 2>/dev/null && echo "  screen: ${OUT:-.}/withdraw-fail-$ruleset.png"; }

for ruleset in $RULESETS; do
  rfail=0; TRAIL=()
  "$ADB" shell am force-stop $PKG
  "$ADB" shell pm clear $PKG >/dev/null
  ts_open; "$ADB" logcat -b crash -c 2>/dev/null || true
  timeout 60 "$ADB" shell am start -W --activity-clear-task -n $PKG/.MainActivity --es start "$ruleset" --ei players 3 >/dev/null || { bad "the app didn't start in 60 s (window manager wedged? reboot the emulator)"; continue; }
  sleep 4
  # Bots say no at once; only a counter holds for the AI pace. Slider to 5 s (Menu › Settings › Play, by touch) so
  # there is time to tap Withdraw after a counter.
  if tap_text "Menu" && tap_text "Settings" && panel_tap "Play"; then
    dpi=$("$ADB" shell wm density | grep -o '[0-9]*' | tail -1)
    xy=$(dump | grep 'text="[0-9.]* s"' | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | tr -c '0-9\n' ' ' | awk -v d="$dpi" '{print $3 + int(214 * d / 160), int(($2+$4)/2)}')
    [ -n "$xy" ] && "$ADB" shell input tap $xy; sleep 0.6
    say "AI pace now: $(dump | grep -o 'text="[0-9.]* s"' | head -1)"
    tap_text "Hide" || "$ADB" shell input keyevent BACK
  else say "couldn't reach the AI pace slider"; tap_text "Hide" || true   # never leave the menu over the trade button
  fi
  # On CI the slider tap didn't stick (every answer came back within a second); set it through the debug receiver too.
  "$ADB" shell am broadcast -a $PKG.FILAMENT_VIEW -p $PKG --ef aiPace 5 >/dev/null
  say "AI pace set to 5 s: $("$ADB" logcat -d -s WGBG-3D-VIEW:I | tail -1 | cut -c1-60)"
  done_w=0; again=0; lost=0; start=$(date +%s); ts_alive
  while [ $(( $(date +%s) - start )) -lt "$SECS" ] && [ $done_w = 0 ] && [ $rfail = 0 ]; do
    crashed && { bad "crash"; break; }
    if [ $again = 1 ]; then again=0; line=$(turn_line)
    elif ts_wait 6; then line=$TS_LINE
    else ts_check_stall "withdraw-$ruleset"; [ -n "${LASTMOVE:-}" ] && move "$LASTMOVE"; continue; fi   # quiet 6 s: resend a hook move that may have been dropped (the app drops it if no longer legal)
    [ $TS_OVER = 1 ] && { TS_OVER=0; say "game over, playing again"; tap_text "Play again" || { bad "no Play again"; break; }; continue; }
    echo "$line" | grep -q 'player=0' || { say "not ours: ${line:0:90}"; continue; }
    phase=$(echo "$line" | sed -n 's/.* phase=\([^ ]*\).*/\1/p')
    legal=$(echo "$line" | sed -n 's/.* legal=\([^ ]*\).*/\1/p' | tr ',' '\n')
    [ -z "$legal" ] && continue
    r=$(echo "$legal" | grep '^ROLL:' | head -1); [ -n "$r" ] && { move "$r"; continue; }
    if [ "$phase" != PLAY_TURN ] || ! echo "$legal" | grep -q '^END_TURN:'; then move "$(echo "$legal" | grep -v -E '^(OFFER_TRADE|OFFER_TRADE_CK|COUNTER_TRADE)' | shuf -n 1)"; continue; fi
    # Our turn with cards: Trade, Players, offer the first card we hold for nothing, Offer, then Withdraw at once.
    tap_text "Trade" || { tap_text "Close"; tap_text "Trade"; } || { say "no Trade button (the moment passed?), end turn"; move END_TURN:0; continue; }
    say "trade chest open"
    tap_text "Players" || { bad "no Players tab"; break; }
    card=$(dump | grep 'text="have [1-9]' | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | tr -c '0-9\n' ' ' | awk '{print int(($1+$3)/2), $2}')
    if [ -z "$card" ]; then say "no cards, end turn"; tap_text "Close" || true; move END_TURN:0; continue; fi
    # The label sits under the card; the card's bottom half (just above the label) offers it.
    # 23 dp above the label: 60 px was right at 420 dpi but hit the card's top (ask) half at CI's 160 dpi.
    [ -n "${dpi:-}" ] || dpi=$("$ADB" shell wm density | grep -o "[0-9]*" | tail -1)
    read -r cx cy <<<"$card"; "$ADB" shell input tap "$cx" $(( cy - 23 * dpi / 160 )); sleep 0.6
    # Screen reads take seconds (the sea never idles), longer than a bot's answer: read both buttons first, then tap
    # Offer and, a beat later, the red button (Clear turns to Withdraw while the bots answer) without reading again.
    d=$(dump); off=$(echo "$d" | grep 'text="Offer"' | centre); red=$(echo "$d" | grep 'text="Clear"' | centre)
    [ -n "$off" ] && [ -n "$red" ] || { bad "no Offer/Clear after picking a card"; break; }
    # The hook logs only our own moments, so a new line before the tap means the bots had all answered.
    lines0=$("$ADB" logcat -d -s WGBG-TURN:I | wc -l)
    "$ADB" shell input tap $off; sleep 1
    if [ "$("$ADB" logcat -d -s WGBG-TURN:I | wc -l)" -gt "$lines0" ]; then
      say "the bots had all answered already: $(turn_line | cut -c1-140)"; tap_text "Close" || tap_text "Cancel" || true; ts_alive; again=1; continue
    fi
    "$ADB" shell input tap $red; sleep 2
    l=$(turn_line)
    echo "$l" | grep -q 'phase=PLAY_TURN' && echo "$l" | grep -q 'player=0' || { bad "not back on our turn after Withdraw: ${l:0:160}"; break; }
    tap_text "Close" || true   # the game log shows with the chest shut
    # Read the visible log lines; scroll down through the log, then back up, the way a reader would (CI's 320 dp shows
    # three lines and the turn's group can be longer in Age of Chivalry, with counters in it).
    read -r H W < <("$ADB" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' ' | awk '{ print ($1 < $2 ? $1 " " $2 : $2 " " $1) }')  # landscape: the longer side across
    seen=0; for dir in up up up up up up down down down down down down down down down down down down; do
      d=$(dump); echo "$d" | grep -q 'text="You:* withdrew the trade offer"' && { seen=1; break; }
      say "log shows: $(echo "$d" | grep -o 'text="[^"]*\(offer\|trade\|counter\|withdrew\)[^"]*"' | head -4 | tr '\n' ' ')"
      if [ $dir = up ]; then "$ADB" shell input swipe $((W / 5)) $((H * 90 / 100)) $((W / 5)) $((H * 62 / 100)) 300
      else "$ADB" shell input swipe $((W / 5)) $((H * 62 / 100)) $((W / 5)) $((H * 90 / 100)) 300; fi; sleep 0.8; done
    # Every bot saying no adds nothing to the log, so a log with the offer and no withdrew line means they all answered
    # between the hook check and the tap (CI's adb is slow): the red button had turned back to Clear. Try again.
    if [ $seen = 0 ]; then lost=$((lost + 1)); [ $lost -ge 3 ] && { bad "no 'You withdrew the trade offer' in the game log ($lost tries)"; break; }
      say "no withdrew line: the bots answered before the tap (try $lost), offering again"; again=1; continue; fi
    echo "withdraw $ruleset: ok (offer withdrawn by touch while the bots were answering)"
    done_w=1
  done
  crashed && bad "crash"
  [ $rfail = 0 ] && [ $done_w = 0 ] && bad "no withdraw done in ${SECS}s"
done
exit $fail
