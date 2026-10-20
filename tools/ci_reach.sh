#!/usr/bin/env bash
# Every button outside the board can be reached by touch on CI's small screen (640x320 dp), scrolling as a player would:
#   start menu (fresh and with a saved game), game-start screen, Report, Look & art, Join, the host's lobby, and in a
#   game the Menu, the card guide and the Report sheet with its screenshot. With `end`, run on a finished game (after
#   tools/play_to_end.sh chivalry: CI's play-to-end shard), the game-over panel and the dice histogram.
# 0.3.2 cut off the game-start screen's Points, Report and Intro and the start menu's Settings (with a saved game) at
# 320 dp tall, and squashed the in-game Report's Send and Close; none of them scrolled.
# A taller device is switched to CI's screen (320x640 at 160 dpi) for the run and put back after.
# Usage: tools/ci_reach.sh [end]   (the app installed on $ANDROID_SERIAL or the only device)
set -uo pipefail
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
MODE="${1:-screens}"
export MSYS_NO_PATHCONV=1
fail=0

dump() { for _ in 1 2 3; do "$ADB" shell rm -f /sdcard/ui.xml; "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local x; x=$("$ADB" shell cat /sdcard/ui.xml 2>/dev/null); case "$x" in *hierarchy*) echo "$x" | tr '>' '\n'; return;; esac; sleep 1; done; }
bounds() { grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 | tr -c '0-9\n' ' '; }
centre() { bounds | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }
on() { dump | grep -q "text=\"$1\""; }
# The failure screen lands in the working dir (or $OUT), like the other CI scripts: CI uploads *.png from there.
bad() { echo "reach: FAILED: $*"; fail=1; "$ADB" exec-out screencap -p > "${OUT:-.}/reach-fail.png" 2>/dev/null; }

# The screen, as the app is laid out (landscape): width and height in pixels.
read -r PW PH < <("$ADB" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' ')
DENSITY=$("$ADB" shell wm density | tail -1 | grep -o '[0-9]*$')
if [ $(( (PW < PH ? PW : PH) * 160 / DENSITY )) -gt 330 ]; then
  echo "reach: switching this ${PW}x${PH} @${DENSITY} dpi screen to CI's 320x640 @160 for the run"
  "$ADB" shell wm size 320x640; "$ADB" shell wm density 160
  # Put back on any exit; a timeout's TERM becomes an exit too (it left the 160 dpi override behind once).
  trap '"$ADB" shell wm size reset; "$ADB" shell wm density reset' EXIT; trap 'exit 1' INT TERM
  PW=320; PH=640; DENSITY=160
fi
W=$(( PW > PH ? PW : PH )); H=$(( PW < PH ? PW : PH ))
# Text 10 dp tall or more counts as shown; a clipped edge reads as a sliver (0.3.2: "Settings" 5 dp).
MIN=$(( 10 * DENSITY / 160 ))

# Shown in full on screen: the label's text inside the screen and not cut to a sliver. $2 is a dump to read instead of a new one.
fits() { [ -n "$1" ] && echo "$1" | awk -v w=$W -v h=$H -v m=$MIN '{ exit !($1 >= 0 && $2 >= 0 && $3 <= w && $4 <= h && $4 - $2 >= m) }'; }
shown() { fits "$(echo "${2:-$(dump)}" | grep "text=\"$1\"" | bounds)"; }
# Scrolls each scrollable panel on the screen (as the app reports them) to its top, then down to its end, until the
# label is shown in full.
reach() { local now was p l t r b x y d dir panels; now=$(dump); shown "$1" "$now" && return 0
  # Read up front: adb shell inside a `while read` loop would swallow the rest of the list. Largest first: the sheet on
  # top, not the long game log half hidden under it (which took minutes to scroll through over a moving board).
  mapfile -t panels < <(echo "$now" | grep 'scrollable="true"' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -c '0-9\n' ' ' \
    | awk '{ print ($3 - $1) * ($4 - $2), $1, $2, $3, $4 }' | sort -rn | cut -d' ' -f2-)
  # Slow, short swipes (a fling skipped a label from below the screen to above it); each way stops at the panel's end,
  # when a swipe no longer changes the screen.
  for p in "${panels[@]}"; do read -r l t r b <<< "$p"; x=$(( (l + r) / 2 )); y=$(( (t + b) / 2 )); d=$(( (b - t) / 5 ))
    for dir in -$d $d; do for _ in $(seq 1 20); do was=$now
      "$ADB" shell input swipe $x $((y + dir)) $x $((y - dir)) 500; now=$(dump)
      shown "$1" "$now" && return 0; [ "$now" = "$was" ] && break; done; done
  done; return 1; }
# Every label on the screen named $1 can be reached: one look for those already shown (a dump in $DUMP, if given), then
# scrolling for the rest.
all() { local where=$1 l n=0 x; shift; x=${DUMP:-$(dump)}
  for l in "$@"; do { shown "$l" "$x" || reach "$l"; } && n=$((n + 1)) || bad "$where: \"$l\" can't be reached by scrolling"; done
  echo "reach: $where: $n of $# reached (${SECONDS} s)"; }
tap() { reach "$1" || { bad "\"$1\" can't be reached to tap"; return 1; }; local xy; xy=$(dump | grep "text=\"$1\"" | centre); [ -n "$xy" ] && "$ADB" shell input tap $xy; sleep 1; }
# The Leave dialog: "Leave for now" keeps a saved game, "Leave" when there's nothing to keep.
leave() { on "Leave for now" && { tap "Leave for now"; return; }; on "Leave" && tap "Leave"; }
# Taps a button and checks the screen it opens (marked by $2); stops the run if it didn't, since what follows is moot.
go() { tap "$1" && { for _ in 1 2 3 4 5; do on "$2" && return 0; sleep 1; done; }
  bad "\"$1\" didn't open its screen"; echo "reach: FAILED"; exit 1; }

if [ "$MODE" = screens ]; then
  "$ADB" shell am force-stop $PKG
  "$ADB" shell pm clear $PKG >/dev/null
  "$ADB" logcat -b crash -c 2>/dev/null || true
  timeout 60 "$ADB" shell am start -W --activity-clear-task -n $PKG/.MainActivity >/dev/null || { echo "reach: FAILED: the app didn't start"; exit 1; }
  for _ in $(seq 1 15); do on "Tap to skip" || on "New game" && break; sleep 2; done
  # The intro ends on its own too, so it may be gone by the time of the tap: that's not a failure.
  xy=$(dump | grep 'text="Tap to skip"' | centre); [ -n "$xy" ] && { "$ADB" shell input tap $xy; sleep 2; }
  for _ in $(seq 1 10); do on "New game" && break; sleep 1; done

  all "start menu" "New game" "Settings" "ⓘ  About and credits"
  go "Settings" "Close"; all "start menu settings" "Close"; tap "Close"
  go "New game" "Players"
  all "game-start" "Base game" "Age of Chivalry" "Report" "Intro" "Look &amp; art" "Play" "Host a room" "Join a room"
  go "Report" "Add a picture"; all "Report" "Bug" "Idea" "Add a picture" "Send" "Close"; tap "Close"
  go "Look &amp; art" "Done"; all "Look & art" "Done" "Card guide"; tap "Done"
  # Online needs a real name: type one (the name box is the screen's first text box, at the left panel's top).
  box() { fits "$(dump | grep 'class="android.widget.EditText"' | head -1 | bounds)"; }
  box || for _ in 1 2 3; do "$ADB" shell input swipe $((W / 4)) $((H / 4)) $((W / 4)) $((H * 3 / 4)) 200; sleep 0.4; box && break; done
  box || bad "game-start: the name box can't be reached"
  xy=$(dump | grep 'class="android.widget.EditText"' | head -1 | centre); "$ADB" shell input tap $xy; sleep 1
  "$ADB" shell input keyevent KEYCODE_MOVE_END; for _ in 1 2 3 4 5 6 7 8; do "$ADB" shell input keyevent KEYCODE_DEL; done
  "$ADB" shell input text Duo; "$ADB" shell input keyevent KEYCODE_ENTER; sleep 1
  go "Join a room" "Room code"; all "Join" "Join" "Back"; tap "Back"
  on "Players" || go "New game" "Players"
  go "Host a room" "Start game"; all "lobby" "Copy link" "Share…" "Show QR" "Start game" "New game settings…"
  "$ADB" shell input keyevent KEYCODE_BACK; sleep 2; leave
  on "Players" || go "New game" "Players"
  # Age of Chivalry: its eight cards are the card guide's widest row.
  tap "Age of Chivalry"; go "Play" "Menu"; sleep 3
  go "Menu" "Card guide"; all "game menu" "Leave game" "Card guide" "Fit the board" "Settings" "Report a bug or idea"
  go "Card guide" "Cards"; all "card guide" "Close" "Silver"; tap "Close"
  go "Menu" "Card guide"; go "Report a bug or idea" "Include this game"; all "game Report" "Include this game" "Send" "Share" "Close"; tap "Close"
  # Leave the game: it's saved, and the start menu then offers Resume and End too.
  for _ in 1 2 3 4 5; do on "Resume game" && break; "$ADB" shell input keyevent KEYCODE_BACK; sleep 2; leave; done
  all "start menu with a saved game" "Resume game" "End saved game" "New game" "Settings" "ⓘ  About and credits"
else
  # Game over and the dice histogram (with the event die, its tallest form) on a game played to its end. The dice row only
  # shows once someone has rolled.
  for _ in $(seq 1 20); do on "See the board" && break; sleep 3; done
  all "game over" "Play again" "See the board" "Exit"
  tap "See the board"
  xy=$(dump | grep 'content-desc="Dice"' | centre)
  # Both taps from one shell, close enough together to count as a double tap.
  [ -n "$xy" ] && "$ADB" shell "input tap $xy; input tap $xy"; sleep 1.5
  # One look serves both: over the moving board a dump takes about 15 s.
  x=$(dump); echo "$x" | grep -q 'text="Event die"' || bad "double-tapping the dice didn't open the histogram"
  DUMP=$x all "dice histogram" "Event die" "The line on each bar is what fair dice would give."
fi

[ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" = 0 ] || bad "the app crashed"
[ $fail = 0 ] && echo "reach: PASSED"
exit $fail
