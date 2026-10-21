#!/usr/bin/env bash
# Bot names on a device (0.3.4, Mauro: "I would also like to name my AI"), all by taps on CI's small screen (640x320 dp):
#   1. a fresh start, New game, the theme, then each seat's name picked from the picker (screenshots of both);
#   2. the app closed and opened again: New game still shows the three names;
#   3. Play: the player list shows the names (screenshot); then closed mid-game and resumed from the start screen, the
#      names are still there;
#   4. monkey on the New game screen, failing on a crash.
# Debug extras: none. The game's log is only read to know the game has started.
# Usage: ANDROID_SERIAL=emulator-5554 tools/names_check.sh "English queens" "Elizabeth I,Charlotte,Catherine" [monkey events=2000]
set -uo pipefail
THEME="$1"; IFS=, read -r -a PICKS <<< "$2"; EVENTS="${3:-2000}"
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device to drive}"
export ANDROID_SERIAL MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"; PKG=com.mauro.wgbg
OUT="${OUT:-$TEMP/wgbg-names/$ANDROID_SERIAL}"; mkdir -p "$OUT"
TAG=$(echo "$THEME" | tr ' ' '-' | tr 'A-Z' 'a-z')

# The small screen, put back however the run ends.
"$ADB" shell wm size 320x640; "$ADB" shell wm density 160
trap '"$ADB" shell wm size reset; "$ADB" shell wm density reset' EXIT
read -r W H < <("$ADB" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' '); [ "$W" -lt "$H" ] && { t=$W; W=$H; H=$t; }

dump() {
  local x
  for _ in 1 2 3; do
    "$ADB" shell rm -f /sdcard/ui.xml; "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    x=$("$ADB" shell cat /sdcard/ui.xml 2>/dev/null); case "$x" in *hierarchy*) echo "$x" | tr '>' '\n'; return;; esac
    "$ADB" shell pkill -f uiautomator 2>/dev/null; sleep 1
  done
}
# Centre of the first element whose text matches the regex $1 (whole text), in dump $2 or a fresh one.
find_re() {
  local d="${2:-$(dump)}"
  echo "$d" | grep -o "text=\"$1\"[^/]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1 |
    grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr '\n' ' ' | awk '{split($1,a,",");split($2,b,",");print int((a[1]+b[1])/2), int((a[2]+b[2])/2)}'
}
tap() { "$ADB" shell input tap "$1" "$2" </dev/null; sleep 0.8; }
shot() { "$ADB" exec-out screencap -p > "$OUT/$TAG-$1.png"; }
fail() { echo "NAMES $TAG FAILED: $1"; shot fail; "$ADB" logcat -d -b crash | grep -A30 "FATAL EXCEPTION" | head -40; exit 1; }
# Find a regex on screen, scrolling the panel under x=$2 (the left one by default) a little at a time, as a player would.
reach() {
  local x="${2:-$((W / 4))}" xy d
  for i in $(seq 0 8); do
    d=$(dump); xy=$(find_re "$1" "$d"); [ -n "$xy" ] && { echo "$xy"; return 0; }
    if [ "$i" -lt 4 ]; then "$ADB" shell input swipe "$x" $((H * 3 / 4)) "$x" $((H / 3)) 400; else "$ADB" shell input swipe "$x" $((H / 3)) "$x" $((H * 3 / 4)) 400; fi
    sleep 0.6
  done
  return 1
}
re() { printf '%s' "$1" | sed 's/[][\.*^$]/\\&/g'; }   # a name as a literal in a regex

launch() {
  for _ in 1 2 3; do
    "$ADB" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
    [ -n "$("$ADB" shell pidof $PKG | tr -d '\r\n')" ] && break
  done
  local d
  for _ in $(seq 1 15); do d=$(dump); echo "$d" | grep -q 'text="Tap to skip"\|text="New game"' && break; sleep 2; done
  if echo "$d" | grep -q 'text="Tap to skip"'; then tap $((W / 2)) $((H / 2)); sleep 2; fi
  for _ in $(seq 1 10); do d=$(dump); echo "$d" | grep -q 'text="New game"' && return 0; sleep 1; done
  fail "the start screen never appeared"
}
new_game() { tap $(find_re "New game") || fail "no New game"; for _ in $(seq 1 10); do dump | grep -q 'text="Play"' && return 0; sleep 1; done; fail "no game-start screen"; }

# 1. Fresh, in through the front door, pick the theme and the names.
"$ADB" shell am force-stop $PKG; "$ADB" shell pm clear $PKG >/dev/null
"$ADB" logcat -c; "$ADB" logcat -b crash -c 2>/dev/null || true
sleep 2; launch; new_game
xy=$(reach "$(re "$THEME")") || fail "no \"$THEME\" theme"; tap $xy
shot theme
for k in "${!PICKS[@]}"; do
  seat=$((k + 2)); name="${PICKS[$k]}"
  # A seat's name button ("<name> ▾") is the nearest one below its "Seat N" title.
  below() { local d ty; d=$(dump); ty=$(find_re "Seat $seat" "$d" | cut -d' ' -f2); [ -n "$ty" ] || return 1
    echo "$d" | grep -o 'text="[^"]* ▾"[^/]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | grep -o 'bounds=.*' | tr -c '0-9\n' ' ' |
      awk -v ty="$ty" '{y=int(($2+$4)/2); if (y > ty) print int(($1+$3)/2), y}' | sort -n -k2,2 | head -1; }
  reach "Seat $seat" >/dev/null || fail "no Seat $seat"
  xy=$(below); [ -n "$xy" ] || { "$ADB" shell input swipe $((W / 4)) $((H * 2 / 3)) $((W / 4)) $((H / 3)) 400; sleep 0.6; xy=$(below); }
  [ -n "$xy" ] || fail "seat $seat's name button isn't on screen"
  tap $xy; sleep 0.6
  dump | grep -q "text=\"Seat $seat: $THEME\"" || fail "seat $seat's picker didn't open"
  [ "$k" = 0 ] && shot picker
  xy=$(reach "$(re "$name")\\( *(Seat [0-9]: swaps)\\)\\?" $((W / 2))) || fail "no $name in the picker"
  tap $xy; sleep 0.6
  dump | grep -q "text=\"$name ▾\"" || reach "$(re "$name") ▾" >/dev/null || fail "seat $seat didn't take $name"
done
shot picked
echo "NAMES $TAG: picked ${PICKS[*]}"

# 2. Closed and opened again: the start screen remembers them.
"$ADB" shell am force-stop $PKG; sleep 1; launch; new_game
for name in "${PICKS[@]}"; do reach "$(re "$name") ▾" >/dev/null || fail "$name was lost when the app closed"; done
echo "NAMES $TAG: kept after closing the app"

# 3. Play: the player list shows them; close mid-game and resume, still there.
xy=$(reach 'Play' $((W * 3 / 4))) || fail "no Play"; tap $xy
for _ in $(seq 1 60); do "$ADB" logcat -d -s WGBG-TURN:I | grep -q 'phase=' && break; sleep 1; done
"$ADB" logcat -d -s WGBG-TURN:I | grep -q 'phase=' || fail "the game never started"
sleep 6; shot game
d=$(dump); for name in "${PICKS[@]}"; do echo "$d" | grep -q "text=\"$name\"" || fail "$name isn't in the player list"; done
echo "NAMES $TAG: in the game's player list"
"$ADB" shell am force-stop $PKG; sleep 1; launch
tap $(find_re "Resume game") || fail "no Resume game after closing mid-game"
sleep 6; shot resumed
d=$(dump); for name in "${PICKS[@]}"; do echo "$d" | grep -q "text=\"$name\"" || fail "$name is gone after resuming"; done
echo "NAMES $TAG: kept in the resumed game"

# 4. Monkey on the game-start screen (taps, swipes and the picker), no system keys.
"$ADB" shell am force-stop $PKG; sleep 1; launch; new_game
"$ADB" logcat -b crash -c 2>/dev/null || true
"$ADB" shell monkey -p $PKG -s 34 --pct-syskeys 0 --pct-appswitch 0 --throttle 30 -v "$EVENTS" > "$OUT/$TAG-monkey.txt" 2>&1
grep -q "Monkey finished" "$OUT/$TAG-monkey.txt" || fail "monkey stopped early: $(grep -m3 'CRASH\|ANR\|aborted' "$OUT/$TAG-monkey.txt")"
[ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" = 0 ] || fail "a crash during monkey"
echo "NAMES $TAG: monkey $EVENTS events, no crash"
echo "NAMES $TAG PASSED (screenshots in $OUT)"
