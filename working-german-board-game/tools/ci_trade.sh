#!/usr/bin/env bash
# Trade by touch (#122, Mauro 2026-10-13: "how were those not found by catanatron? add to our ci"). Catanatron
# checks the rules engine only; these bugs lived in the trade chest's taps. Per ruleset (you + 2 bots), the game
# is played up to a turn where you can pay the bank (setup, rolls and other turns by the agent hook), then
# everything in the chest is done by tapping the cards on screen and checked by reading their "have N" labels:
#   A. a bank trade: ask a card (top half), pay a lot (bottom half), Trade; counts move by the rate;
#   B. the cards used in it still answer taps (they went dead after a trade before 0.2.10);
#   C. several lots in one go: two lots paid for two cards, one Trade (needs a turn holding two lots);
#   D. an open ask by taps on the Players tab (#135: ask a card, offer nothing); the bots counter it; a taker's
#      button shows their terms and Trade takes them (Cancel withdraws when nobody answered).
# Fails on a crash, a step that doesn't take, or no turn with two lots within the time.
# Usage: [DICE="3,3 4,4 ..."] tools/ci_trade.sh [seconds per ruleset] [rulesets, default "base chivalry"]
set -uo pipefail
SECS="${1:-420}"
RULESETS="${2:-base chivalry}"
# The dice, in order and over again (debug --es dice, 0.3.5): every number but 7 once a round, so every hex you touch
# pays each round and no 7 ever halves your hand. Two lots then always come (random dice missed them within 420 s on
# CI, run 39656897375). DICE="" plays real dice, C included; a build before 0.3.5 ignores the extra and skips C.
DICE="${DICE-3,3 4,4 2,3 4,5 1,3 4,6 1,2 5,6 1,1 6,6}"
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
export MSYS_NO_PATHCONV=1
fail=0
. "$(dirname "$0")/lib/turnstream.sh" # turn lines as they come + stall watchdog (#139)

. "$(dirname "$0")/lib/uiread.sh"   # dump, at, tap_text, tap_id: the app's own screen read (0.2 s), uiautomator as the fallback
# A Settings row below the fold (CI's 320 dp tall screen) is scrolled to in the open panel, as a player would.
# wm size gives the portrait size; the app is landscape, so the long side is the width (a swipe from below the screen does nothing).
panel_tap() { tap_text "$1" && return 0; local w h; read -r w h < <("$ADB" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' ')
  [ "$w" -lt "$h" ] && read -r w h <<< "$h $w"
  for _ in 1 2 3 4; do "$ADB" shell input swipe $((w * 7 / 10)) $((h * 4 / 5)) $((w * 7 / 10)) $((h * 3 / 10)) 400; sleep 0.8; tap_text "$1" && return 0; done; return 1; }
crashed() { [ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" != 0 ]; }
turn_line() { "$ADB" logcat -d -s WGBG-TURN:I | tail -1; }
# Poll the screen up to $2 s for a pattern (a fixed sleep then one look raced slow CI emulators).
seen() { for _ in $(seq 1 "$2"); do dump | grep -q "$1" && return 0; sleep 1; done; return 1; }
move() { LASTMOVE=$1; "$ADB" shell am broadcast -a $PKG.MOVE -p $PKG --es move "$1" >/dev/null; }
# The chest's cards, left to right: "x held rate" (rate 0 on the Players tab), from the labels under them.
cards() { dump | grep 'text="have [0-9]*' | sed 's/.*text="have \([0-9]*\)\(&#10;\([0-9]\):1\)\{0,1\}".*bounds="\[\([0-9]*\),[0-9]*\]\[\([0-9]*\),[0-9]*\]".*/\4 \5 \1 \3/' |
  awk '{print int(($1+$2)/2), $3, ($4 == "" ? 0 : $4)}' | sort -n; }
# The chest's card art, top and bottom edge (every card in the row is the same size; Timber is always drawn): the
# Timber image standing over the first label, not another Timber elsewhere on screen (the log, a card sheet).
card_rows() { dump | grep 'content-desc="Timber"' | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -c '0-9\n' ' ' |
  awk -v x="$1" '{d = ($1 + $3) / 2 - x; if (d < 0) d = -d; if (best == "" || d < best) { best = d; row = $2 " " $4 } } END { print row }'; }
CARDX=(); HELD=(); RATE=(); TOP=0; BOT=0
read_cards() { local cx ch cr; CARDX=(); HELD=(); RATE=(); while read -r cx ch cr; do CARDX+=("$cx"); HELD+=("$ch"); RATE+=("$cr"); done < <(cards)
  read -r TOP BOT < <(card_rows "${CARDX[0]:-0}"); TOP=${TOP:-0}; BOT=${BOT:-0}; say "cards x=${CARDX[*]} held=${HELD[*]} rate=${RATE[*]} rows=$TOP..$BOT"; }
ask() { "$ADB" shell input tap "${CARDX[$1]}" $(( TOP + (BOT - TOP) / 4 )); sleep 0.6; }   # top half asks
pay() { "$ADB" shell input tap "${CARDX[$1]}" $(( BOT - (BOT - TOP) / 4 )); sleep 0.6; }   # bottom half pays / offers
say() { [ "${VERBOSE:-0}" = 1 ] && echo "  · $*"; return 0; }
bad() { echo "trade $ruleset: FAILED: $*"; echo "  cards x=${CARDX[*]} held=${HELD[*]} rate=${RATE[*]} rows=$TOP..$BOT"; fail=1
  "$ADB" exec-out screencap -p > "${OUT:-.}/trade-fail-$ruleset.png" 2>/dev/null && echo "  screen: ${OUT:-.}/trade-fail-$ruleset.png"
  # The screen as uiautomator saw it, so a tap that went astray (whose bounds, which element first) can be traced.
  dump > "${OUT:-.}/trade-fail-$ruleset.xml" 2>/dev/null && echo "  dump: ${OUT:-.}/trade-fail-$ruleset.xml"; }

for ruleset in $RULESETS; do
  "$ADB" shell am force-stop $PKG
  "$ADB" shell pm clear $PKG >/dev/null
  ts_open; "$ADB" logcat -b crash -c 2>/dev/null || true
  timeout 60 "$ADB" shell am start -W --activity-clear-task -n $PKG/.MainActivity --es start "$ruleset" --ei players 3 --ez quickbots true ${DICE:+--es dice "'$DICE'"} >/dev/null || { bad "the app didn't start in 60 s (window manager wedged? reboot the emulator)"; continue; }
  sleep 4
  # Bots at full speed (Menu › Settings › This game, the speed button cycles 2× 4× 8× Max, by touch), so a turn with two lots comes up sooner.
  tap_text "Menu" && tap_text "Settings" && panel_tap "This game" && panel_tap "2×" && panel_tap "4×" && panel_tap "8×" ||
    say "couldn't set full speed in Settings"; tap_text "Hide" || true
  done_ab=0; done_c=0; done_d=0; again=0; games=1; start=$(date +%s); ts_alive
  # Step C needs the fixed dice (0.3.5's debug --es dice; the app logs WGBG-DICE when it takes them). A build without
  # them plays random dice, where two lots may never come: C is left out there, said plainly, not left to a coin toss.
  if [ -n "$DICE" ] && ! "$ADB" logcat -d -s WGBG-DICE:I | grep -q 'fixed dice'; then
    echo "trade $ruleset: C skipped: this build takes no --es dice (before 0.3.5), and on random dice two lots may never come"; done_c=1; fi
  while [ $(( $(date +%s) - start )) -lt "$SECS" ] && { [ $done_c = 0 ] || [ $done_d = 0 ]; } && [ $fail = 0 ]; do
    crashed && { bad "crash"; break; }
    # The bots won (this seat never builds): play again, by touch.
    # Our next moment as it comes (again=1: act on the current one once more, e.g. after closing the chest).
    if [ $again = 1 ]; then again=0; line=$(turn_line)
    elif ts_wait 6; then line=$TS_LINE
    else ts_check_stall "trade-$ruleset"; [ -n "${LASTMOVE:-}" ] && move "$LASTMOVE"; continue; fi   # quiet 6 s: resend a hook move that may have been dropped (the app drops it if no longer legal)
    if [ $TS_OVER = 1 ]; then
      TS_OVER=0; say "game over, playing again"; sleep 1; tap_text "Play again" || { bad "no Play again after the game"; break; }
      games=$((games + 1)); sleep 2; continue
    fi
    [ -z "$line" ] && continue
    phase=$(echo "$line" | sed -n 's/.* phase=\([^ ]*\).*/\1/p')
    legal=$(echo "$line" | sed -n 's/.* legal=\([^ ]*\).*/\1/p' | tr ',' '\n')
    [ -z "$legal" ] && continue
    r=$(echo "$legal" | grep '^ROLL:' | head -1); [ -n "$r" ] && { move "$r"; continue; }
    if [ "$phase" != PLAY_TURN ] || ! grep -q '^END_TURN:' <<< "$legal"; then move "$(echo "$legal" | grep -v -E '^(OFFER_TRADE|OFFER_TRADE_CK|COUNTER_TRADE)' | shuf -n 1)"; continue; fi
    if ! grep -q '^MARITIME_TRADE:' <<< "$legal"; then move END_TURN:0; continue; fi
    # Only C left: open the chest only when two different cards can each pay a lot (screen reads are slow).
    gives=$(echo "$legal" | grep '^MARITIME_TRADE:' | cut -d: -f2 | awk '{print $1 % 256}' | sort -u | wc -l)
    if [ $done_ab = 1 ] && [ $done_d = 1 ] && [ "$gives" -lt 2 ]; then move END_TURN:0; continue; fi

    # A bank trade is possible: open the chest on the Harbour tab, by touch.
    tap_text "Trade" || { bad "no Trade button"; break; }
    tap_text "Harbour" || { bad "no Harbour tab"; break; }
    read_cards
    n=${#CARDX[@]}; lots=0; g=-1
    for ((r = 0; r < n; r++)); do [ "${RATE[$r]}" -gt 0 ] && { l=$(( HELD[r] / RATE[r] )); lots=$((lots + l)); [ $l -gt 0 ] && [ $g -lt 0 ] && g=$r; }; done
    [ "$n" -ge 5 ] && [ "$TOP" -gt 0 ] || { bad "chest cards not found (n=$n)"; break; }
    if [ $done_ab = 0 ] && [ $g -ge 0 ]; then
      w=$(( (g + 1) % n )); before_g=${HELD[$g]}; before_w=${HELD[$w]}; rate=${RATE[$g]}
      ask $w; pay $g
      tap_text "Trade" || { bad "A: no Trade button in the chest"; break; }
      sleep 1.5; read_cards
      [ "${HELD[$g]}" = $((before_g - rate)) ] && [ "${HELD[$w]}" = $((before_w + 1)) ] ||
        { bad "A: bank trade didn't take (card $g: $before_g -> ${HELD[$g]}, card $w: $before_w -> ${HELD[$w]})"; break; }
      # #125: the new counts are shown after the trade ("Now: 4 Grain (+1) · 0 Stone (−3)").
      seen 'text="Now: ' 8; now=$(dump | grep -o 'text="Now: [^"]*"' | head -1)
      grep -q "(+1)" <<< "$now" && grep -q "(−$rate)" <<< "$now" ||
        { bad "A: no new-counts line after the trade (#125): '${now:-none}'"; break; }
      # B: both cards just used must still answer: asking each shows +1.
      ask $g; ask $w
      plus=$(dump | grep -c 'text="+1"')
      [ "$plus" -ge 2 ] || { bad "B: cards used in a trade stopped answering taps (+1 marks: $plus)"; break; }
      tap_text "Clear"
      echo "trade $ruleset: A bank trade by touch ok (card $g -$rate, card $w +1); B used cards still answer"
      done_ab=1
      read_cards
      lots=0; for ((r = 0; r < n; r++)); do [ "${RATE[$r]}" -gt 0 ] && lots=$((lots + HELD[r] / RATE[r])); done
    fi
    if [ $done_ab = 1 ] && [ $lots -ge 2 ]; then
      # C: two lots (from one card or two) for two different cards, in one Trade.
      paid=(); for ((r = 0; r < n && ${#paid[@]} < 2; r++)); do
        [ "${RATE[$r]}" -gt 0 ] || continue
        for ((k = 0; k < HELD[r] / RATE[r] && ${#paid[@]} < 2; k++)); do paid+=("$r"); done
      done
      want=(); for ((r = 0; r < n && ${#want[@]} < 2; r++)); do [ "$r" != "${paid[0]}" ] && [ "$r" != "${paid[1]}" ] && want+=("$r"); done
      before=("${HELD[@]}")
      for r in "${paid[@]}"; do pay "$r"; done
      for r in "${want[@]}"; do ask "$r"; done
      tap_text "Trade" || { bad "C: no Trade button"; break; }
      sleep 2; read_cards
      ok=1
      for ((r = 0; r < n; r++)); do
        exp=${before[$r]}
        for p in "${paid[@]}"; do [ "$p" = $r ] && exp=$((exp - RATE[r])); done
        for q in "${want[@]}"; do [ "$q" = $r ] && exp=$((exp + 1)); done
        [ "${HELD[$r]}" = "$exp" ] || { ok=0; echo "  card $r: ${before[$r]} -> ${HELD[$r]}, expected $exp"; }
      done
      [ $ok = 1 ] || { bad "C: two-lot bank trade didn't add up (paid ${paid[*]}, asked ${want[*]})"; break; }
      echo "trade $ruleset: C two lots in one trade ok (paid cards ${paid[*]}, got ${want[*]})"
      done_c=1
    fi
    if [ $done_ab = 1 ] && [ $done_d = 0 ]; then
      # D: a player offer by touch right after the bank trades.
      say "D: Players tab"; tap_id tab-Players || { bad "D: no Players tab"; break; }
      read_cards
      o=-1; for ((r = 0; r < n; r++)); do [ "${HELD[$r]}" -gt 0 ] && { o=$r; break; }; done
      took=0
      # #135: first ask only ("who has this?"), offering nothing; if nobody takes it, offer a card for nothing
      # (bots counter a gift with their spare card), so a taker's button and Trade get tapped too.
      for kind in ask give; do
      [ $o -ge 0 ] && [ $took = 0 ] || break
      [ $kind = give ] && { tap_id tab-Players || true; read_cards; }
      if [ $kind = ask ]; then c=$(( (o + 1) % n )); ask $c; else c=$o; pay $c; fi
      if true; then
        lines0=$("$ADB" logcat -d -s WGBG-TURN:I | wc -l)
        say "D: $kind card $c, nothing in return"; tap_text "Offer" || { bad "D: no Offer button"; break; }
        # The bots answer at once: either we pick a partner, or all said no and it's our turn again.
        for _ in $(seq 1 20); do
          sleep 1
          [ "$("$ADB" logcat -d -s WGBG-TURN:I | wc -l)" -gt "$lines0" ] || continue
          turn_line | grep -q 'phase=PLAY_TURN\|phase=DECIDE_ACCEPTEES' && break
        done
        [ "$("$ADB" logcat -d -s WGBG-TURN:I | wc -l)" -gt "$lines0" ] || { bad "D: the offer never left (Offer did nothing)"; break; }
        l=$(turn_line); say "D: after Offer: ${l:0:160}"
        if grep -q 'phase=DECIDE_ACCEPTEES' <<< "$l"; then
          # One button per taker, "Name ⇄" (countered) or "Name ✓": tap one to see its terms, then Trade.
          partner=$(dump | grep -o 'text="[A-Za-z]* [⇄✓]"' | head -1 | sed 's/text="\(.*\)"/\1/')
          if [ -n "$partner" ]; then
            tap_text "$partner" || bad "D: couldn't tap $partner"
            tap_text "Trade" || bad "D: no Trade button after picking $partner"
            seen 'text="Now: ' 8 || bad "D: no new counts after trading with $partner"
            turn_line | grep -q 'phase=PLAY_TURN' || bad "D: not back to the turn after Trade"
            echo "trade $ruleset: D took $partner's answer to an open $kind"; took=1
          else tap_text "Cancel" || bad "D: no way out of the answers"; fi
          sleep 1
        fi
        echo "trade $ruleset: D open $kind by touch ok ($(turn_line | sed -n 's/.* phase=\([^ ]*\).*/\1/p'))"
        done_d=1
      fi
      done
    fi
    tap_text "Close" || true
    # End the turn and wait for a newer log line (re-reading this one raced the bots: "no Trade button").
    l=$(turn_line); ts_alive
    if grep -q 'phase=PLAY_TURN' <<< "$l" && grep -q 'END_TURN:' <<< "$l"; then move END_TURN:0; else again=1; fi
  done
  crashed && bad "crash"
  [ $fail = 0 ] && [ $done_ab = 0 ] && bad "no bank trade came up in ${SECS}s"
  [ $fail = 0 ] && [ $done_c = 0 ] && bad "no turn with two lots to pay came up in ${SECS}s"
  [ $fail = 0 ] && [ $done_d = 0 ] && bad "the player offer step never ran"
  [ $fail = 0 ] && echo "trade $ruleset: ok ($games game(s), $(( $(date +%s) - start ))s)" || { "$ADB" logcat -d -b crash | grep -A30 "FATAL EXCEPTION" | head -45; break; }
done
exit $fail
