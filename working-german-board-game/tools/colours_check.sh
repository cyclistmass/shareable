#!/usr/bin/env bash
# Seat colours on a device (0.3.4, Mauro: "random or I can pick the color when I make the game"), all by taps:
#   1. a fresh start, New game: the default colours (Red, Blue, Purple, White), then picks made in the colour picker
#      (screenshots of the picker and of the clash warning a clashing pick shows), ending on a set that doesn't clash;
#   2. Play: the player list names the bots by their picked colours (screenshot); closed mid-game and resumed from
#      the start screen, still the same;
#   3. closed again and New game opened: the picks are remembered;
#   4. Random: the seats show "?", and two games in a row each play a set of different colours (screenshot);
#   5. monkey on the New game screen (the picker and Random included), failing on a crash.
# Debug extras: none. The game's log is only read to know the game has started.
# Usage: ANDROID_SERIAL=emulator-5554 tools/colours_check.sh [monkey events=2000]; SMALL=1 for CI's 640x320 screen.
set -uo pipefail
EVENTS="${1:-2000}"
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device to drive}"
export ANDROID_SERIAL MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"; PKG=com.mauro.wgbg
OUT="${OUT:-${TEMP:-${TMPDIR:-/tmp}}/wgbg-colours/$ANDROID_SERIAL}"; mkdir -p "$OUT"
TAG=colours

if [ "${SMALL:-0}" = 1 ]; then
  "$ADB" shell wm size 320x640; "$ADB" shell wm density 160
  trap '"$ADB" shell wm size reset; "$ADB" shell wm density reset' EXIT
fi
read -r W H < <("$ADB" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' '); [ "$W" -lt "$H" ] && { t=$W; W=$H; H=$t; }

. "$(dirname "$0")/lib/uiread.sh"   # dump: the app's own screen read (0.2 s), uiautomator as the fallback
# Centre of the first element whose text matches the regex $1 (whole text), in dump $2 or a fresh one.
find_re() {
  local d="${2:-$(dump)}"
  echo "$d" | grep -o "text=\"$1\"[^/]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1 |
    grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr '\n' ' ' | awk '{split($1,a,",");split($2,b,",");print int((a[1]+b[1])/2), int((a[2]+b[2])/2)}'
}
tap() { [ $# -ge 2 ] || return 1; "$ADB" shell input tap "$1" "$2" </dev/null; sleep 0.8; }   # no coordinates (nothing found): fail, so `|| fail` runs
shot() { "$ADB" exec-out screencap -p > "$OUT/$TAG-$1.png"; }
fail() { echo "COLOURS FAILED: $1"; shot fail; "$ADB" logcat -d -b crash | grep -A30 "FATAL EXCEPTION" | head -40; exit 1; }
# Find a regex on screen, scrolling the panel under x=$2 (the left one by default, at its left edge) a little at a time, as a player would.
reach() {
  local x="${2:-}" xy d
  # Down 4 swipes, then up 9: back past the start: a fling on Android 14 goes less far than on 16, and 4 up missed Seat 2 there.
  for i in $(seq 0 13); do
    d=$(dump); [ -n "$d" ] || d=$(dump)
    system_anr "$d" && { sleep 1; d=$(dump); }   # a system dialog (an ANR, Live Caption) over the panel hides what we look for
    xy=$(find_re "$1" "$d"); [ -n "$xy" ] && { echo "$xy"; return 0; }
    [ -n "${2:-}" ] || x=$(ui_scroll_x "$d" $((W / 4)))   # off the clasps
    if [ "$i" -lt 4 ]; then "$ADB" shell input swipe "$x" $((H * 3 / 4)) "$x" $((H / 3)) 400; else "$ADB" shell input swipe "$x" $((H / 3)) "$x" $((H * 3 / 4)) 400; fi
    sleep 0.6
  done
  return 1
}

# A slow emulator can stall a system app until Android asks to close it; ours not responding is a failure.
system_anr() {
  local d="$1" xy
  if grep -q 'text="Live Caption"' <<< "$d"; then
    xy=$(find_re "Got it" "$d"); [ -n "$xy" ] && { echo "COLOURS: closed the system's Live Caption popup"; tap $xy; return 0; }
  fi
  grep -q 'text="[^"]* \(isn.t responding\|keeps stopping\)"' <<< "$d" || return 1
  grep -q 'text="Working German Board Game \(isn.t responding\|keeps stopping\)"' <<< "$d" && fail "the game isn't responding or keeps stopping"
  echo "COLOURS: closed a system app that wasn't responding"
  xy=$(find_re "Close app" "$d"); [ -n "$xy" ] && tap $xy
  return 0
}
launch() {
  "$ADB" shell cmd statusbar collapse
  for _ in 1 2 3; do
    "$ADB" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
    [ -n "$("$ADB" shell pidof $PKG | tr -d '\r\n')" ] && break
  done
  local d
  for _ in $(seq 1 20); do d=$(dump); system_anr "$d" && continue; grep -q 'text="Tap to skip"\|text="New game"' <<< "$d" && break; sleep 2; done
  if grep -q 'text="Tap to skip"' <<< "$d"; then tap $((W / 2)) $((H / 2)); sleep 2; fi
  for _ in $(seq 1 15); do d=$(dump); system_anr "$d" && continue; grep -q 'text="New game"' <<< "$d" && return 0; sleep 1; done
  fail "the start screen never appeared"
}
# The game-start screen, known by its Players heading: Play can sit below the fold. On CI's 640x320 a build with the
# Firebase config adds "Playing as a guest · Sign in with Google" over it (main's per-push run 39682685361).
# New game is tapped again while the start screen stays: right after a relaunch a busy device can drop the first tap.
new_game() {
  local d xy
  for _ in $(seq 1 12); do
    d=$(dump); system_anr "$d" && continue
    grep -q 'text="Players"' <<< "$d" && return 0
    xy=$(find_re "New game" "$d"); [ -n "$xy" ] && tap $xy || sleep 1
  done
  fail "no game-start screen"
}

# A seat's colour button reads "You: Red" or "Seat 3: Purple", with the box clasp beside it (0.3.5).
who() { [ "$1" = 0 ] && echo "You" || echo "Seat $(( $1 + 1 ))"; }
expect_colour() { reach "$(who "$1"): $2" >/dev/null || fail "$(who "$1") isn't $2"; }
# Opens seat $1's picker and taps colour $2 (its row may say who it swaps with, or what it looks like).
pick() {
  local xy; xy=$(reach "$(who "$1"): [A-Za-z?]*") || fail "no colour button for $(who "$1")"
  tap $xy; sleep 0.6
  grep -q "text=\"\(Your colour\|Seat $(( $1 + 1 ))'s colour\)\"" <<< "$(dump)" || fail "$(who "$1")'s colour picker didn't open"
  [ -n "${3:-}" ] && shot "$3"
  xy=$(reach "$2\\(  (.*)\\)\\?" $((W / 2))) || fail "no $2 in the picker"
  tap $xy; sleep 0.6
  expect_colour "$1" "$2"
}
# The names in the game's player list that are colour names, in screen order.
colour_names() { dump | grep -o 'text="\(Red\|Blue\|Purple\|White\|Yellow\|Orange\|Black\)"' | cut -d'"' -f2; }
play() {
  local xy; "$ADB" logcat -c
  xy=$(reach 'Play' $((W * 3 / 4))) || fail "no Play"; tap $xy
  for _ in $(seq 1 60); do grep -q 'phase=' <<< "$("$ADB" logcat -d -s WGBG-TURN:I)" && break; sleep 1; done
  grep -q 'phase=' <<< "$("$ADB" logcat -d -s WGBG-TURN:I)" || fail "the game never started"
  sleep 6
}

# 1. Fresh, in through the front door.
"$ADB" shell am force-stop $PKG; "$ADB" shell pm clear $PKG >/dev/null
"$ADB" logcat -c; "$ADB" logcat -b crash -c 2>/dev/null || true
sleep 2; launch; new_game
for s in "0 Red" "1 Blue" "2 Purple" "3 White"; do expect_colour $s; done
grep -q 'text="[^"]*look alike[^"]*"' <<< "$(dump)" && fail "the default warns of a clash"
shot default
echo "COLOURS: the default is Red, Blue, Purple, White, no warning"
pick 0 Black picker                                     # Black next to Purple: allowed, with a warning
reach "Black and Purple look alike.*" >/dev/null || fail "no warning for Black next to Purple"
shot clash
echo "COLOURS: a clashing pick shows the warning"
pick 2 Red                                              # Red is free since you left it: Purple goes
pick 1 Yellow
pick 3 Blue                                             # nobody has Blue now
for s in "0 Black" "1 Yellow" "2 Red" "3 Blue"; do expect_colour $s; done
grep -q 'text="[^"]*look alike[^"]*"' <<< "$(dump)" && fail "a clean set still warns"
pick 3 Yellow                                           # Seat 2 has Yellow: the two swap
expect_colour 3 Yellow; expect_colour 1 Blue
shot picked
echo "COLOURS: picked You Black, Seat 2 Blue, Seat 3 Red, Seat 4 Yellow (swap included)"

# 2. Play: the bots go by their colours; close mid-game and resume.
play; shot game
[ "$(colour_names | sort -u | tr '\n' ' ')" = "Blue Red Yellow " ] || fail "the player list shows $(colour_names | tr '\n' ' ')"
echo "COLOURS: in the game, the bots are Blue, Red and Yellow"
"$ADB" shell am force-stop $PKG; sleep 1; launch
tap $(find_re "Resume game") || fail "no Resume game after closing mid-game"
sleep 6; shot resumed
[ "$(colour_names | sort -u | tr '\n' ' ')" = "Blue Red Yellow " ] || fail "after resuming: $(colour_names | tr '\n' ' ')"
echo "COLOURS: kept in the resumed game"

# 3. Closed again, New game: the picks are remembered.
"$ADB" shell am force-stop $PKG; sleep 1; launch; new_game
for s in "0 Black" "1 Blue" "2 Red" "3 Yellow"; do expect_colour $s; done
echo "COLOURS: New game remembers the picks after closing the app"

# 4. Random: "?" on every seat, and each game a set of different colours.
tap $(reach "Random") || fail "no Random"
for i in 0 1 2 3; do expect_colour $i "?"; done
shot random
for g in 1 2; do
  play; shot "random-game-$g"
  n=$(colour_names | sort -u | wc -l)
  [ "$n" = 3 ] || fail "random game $g shows $(colour_names | tr '\n' ' ') (want 3 different bot colours)"
  echo "COLOURS: random game $g: $(colour_names | sort -u | tr '\n' ' ')"
  "$ADB" shell am force-stop $PKG; sleep 1; launch; new_game
  expect_colour 0 "?"
done

# 5. Monkey on the game-start screen, no system keys.
"$ADB" logcat -b crash -c 2>/dev/null || true
for try in 1 2; do
  "$ADB" shell monkey -p $PKG -s $((51 + try)) --pct-syskeys 0 --pct-appswitch 0 --throttle 30 -v "$EVENTS" > "$OUT/$TAG-monkey.txt" 2>&1
  grep -q "Monkey finished" "$OUT/$TAG-monkey.txt" && break
  grep -q "ANR in $PKG\|CRASH: $PKG" "$OUT/$TAG-monkey.txt" && fail "monkey: $(grep -m2 "ANR in $PKG\|CRASH: $PKG" "$OUT/$TAG-monkey.txt")"
  [ "$try" = 2 ] && fail "monkey stopped early twice: $(grep -m3 'CRASH\|ANR\|aborted' "$OUT/$TAG-monkey.txt")"
  echo "COLOURS: monkey stopped on a system app's ANR; again"
  sleep 5; system_anr "$(dump)"; "$ADB" shell am force-stop $PKG; sleep 1; launch; new_game
done
[ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" = 0 ] || fail "a crash during monkey"
echo "COLOURS: monkey $EVENTS events, no crash"
echo "COLOURS PASSED (screenshots in $OUT)"
