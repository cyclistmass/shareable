#!/usr/bin/env bash
# Bot names on a device (0.3.4, Mauro: "I would also like to name my AI"), all by taps on CI's small screen (640x320 dp):
#   1. a fresh start, New game, the theme, then each seat's name picked from the picker (screenshots of both);
#   2. Play: the player list shows the names (screenshot); then closed mid-game and resumed from the start screen, the
#      names are still there;
#   3. closed again and New game opened: it remembers the names from the last game.
#   Between 1 and 2, the app is closed before Play: the game-start screen saves every change, so the names come back.
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
  for _ in 1 2 3 4 5 6; do
    "$ADB" shell rm -f /sdcard/ui.xml; "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    x=$("$ADB" shell cat /sdcard/ui.xml 2>/dev/null); case "$x" in *hierarchy*) echo "$x" | tr '>' '\n'; return;; esac
    "$ADB" shell pkill -f uiautomator 2>/dev/null; sleep 1
  done
  echo "uiautomator dump failed 6 times (busy device?)" >&2
}
# Centre of the first element whose text matches the regex $1 (whole text), in dump $2 or a fresh one.
find_re() {
  local d="${2:-$(dump)}"
  echo "$d" | grep -o "text=\"$1\"[^/]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1 |
    grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr '\n' ' ' | awk '{split($1,a,",");split($2,b,",");print int((a[1]+b[1])/2), int((a[2]+b[2])/2)}'
}
tap() { [ $# -ge 2 ] || return 1; "$ADB" shell input tap "$1" "$2" </dev/null; sleep 0.8; }   # no coordinates (nothing found): fail, so `|| fail` runs
shot() { "$ADB" exec-out screencap -p > "$OUT/$TAG-$1.png"; }
fail() { echo "NAMES $TAG FAILED: $1"; shot fail; "$ADB" logcat -d -b crash | grep -A30 "FATAL EXCEPTION" | head -40; exit 1; }
# Find a regex on screen, scrolling the panel under x=$2 (the left one by default) a little at a time, as a player would.
reach() {
  local x="${2:-$((W / 4))}" xy d
  # Down 4 swipes, then up 9: back past the start: a fling on Android 14 goes less far than on 16, and 4 up missed Seat 2 there.
  for i in $(seq 0 13); do
    d=$(dump); [ -n "$d" ] || d=$(dump)   # no dump (a busy device) says nothing about the screen: look again first
    xy=$(find_re "$1" "$d"); [ -n "$xy" ] && { echo "$xy"; return 0; }
    if [ "$i" -lt 4 ]; then "$ADB" shell input swipe "$x" $((H * 3 / 4)) "$x" $((H / 3)) 400; else "$ADB" shell input swipe "$x" $((H / 3)) "$x" $((H * 3 / 4)) 400; fi
    sleep 0.6
  done
  return 1
}
re() { printf '%s' "$1" | sed 's/[][\.*^$]/\\&/g'; }   # a name as a literal in a regex

# A slow emulator (a busy host) can stall a system app until Android asks to close it, and the dialog covers ours.
# Another app's "isn't responding" gets Close app (Wait loops when it has hung for good); ours is a failure.
system_anr() {
  local d="$1" xy
  if echo "$d" | grep -q 'text="Live Caption"'; then   # the system's own first-run popup, not ours
    xy=$(find_re "Got it" "$d"); [ -n "$xy" ] && { echo "NAMES $TAG: closed the system's Live Caption popup"; tap $xy; return 0; }
  fi
  echo "$d" | grep -q 'text="[^"]* isn.t responding"' || return 1
  echo "$d" | grep -q 'text="Working German Board Game isn.t responding"' && fail "the game isn't responding (ANR)"
  echo "NAMES $TAG: closed a system app that wasn't responding: $(echo "$d" | grep -o 'text="[^"]* isn.t responding"' | head -1)"
  xy=$(find_re "Close app" "$d"); [ -n "$xy" ] && tap $xy
  return 0
}
launch() {
  "$ADB" shell cmd statusbar collapse   # monkey's swipes from the top edge can leave the notification shade down over the game
  for _ in 1 2 3; do
    "$ADB" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
    [ -n "$("$ADB" shell pidof $PKG | tr -d '\r\n')" ] && break
  done
  local d
  for _ in $(seq 1 20); do d=$(dump); system_anr "$d" && continue; echo "$d" | grep -q 'text="Tap to skip"\|text="New game"' && break; sleep 2; done
  if echo "$d" | grep -q 'text="Tap to skip"'; then tap $((W / 2)) $((H / 2)); sleep 2; fi
  for _ in $(seq 1 15); do d=$(dump); system_anr "$d" && continue; echo "$d" | grep -q 'text="New game"' && return 0; sleep 1; done
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

# Closed before Play: every change was saved as it was made.
sleep 1; "$ADB" shell am force-stop $PKG; sleep 1; launch; new_game
for name in "${PICKS[@]}"; do reach "$(re "$name") ▾" >/dev/null || fail "$name was lost when the app closed before Play"; done
xy=$(reach "$(re "$THEME")") || fail "no \"$THEME\" theme after reopening"
echo "NAMES $TAG: kept after closing the app before Play"

# 2. Play: the player list shows them; close mid-game and resume, still there.
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

# 3. Closed again, New game: the last game's names are still picked.
"$ADB" shell am force-stop $PKG; sleep 1; launch; new_game
for name in "${PICKS[@]}"; do reach "$(re "$name") ▾" >/dev/null || fail "$name was lost when the app closed"; done
echo "NAMES $TAG: New game remembers them after closing the app"

# 4. Monkey on the game-start screen (taps, swipes and the picker), no system keys.
"$ADB" logcat -b crash -c 2>/dev/null || true
# Monkey stops on any app's ANR; on a busy host the launcher or System UI can stall, so one of those gets a second try
# (after the system's own dialog is closed). The game's own ANR or crash fails at once.
for try in 1 2; do
  "$ADB" shell monkey -p $PKG -s $((33 + try)) --pct-syskeys 0 --pct-appswitch 0 --throttle 30 -v "$EVENTS" > "$OUT/$TAG-monkey.txt" 2>&1
  grep -q "Monkey finished" "$OUT/$TAG-monkey.txt" && break
  grep -q "ANR in $PKG\|CRASH: $PKG" "$OUT/$TAG-monkey.txt" && fail "monkey: $(grep -m2 "ANR in $PKG\|CRASH: $PKG" "$OUT/$TAG-monkey.txt")"
  [ "$try" = 2 ] && fail "monkey stopped early twice: $(grep -m3 'CRASH\|ANR\|aborted' "$OUT/$TAG-monkey.txt")"
  echo "NAMES $TAG: monkey stopped on a system app's ANR ($(grep -m1 -o 'ANR in [^ ]*' "$OUT/$TAG-monkey.txt")); again"
  sleep 5; system_anr "$(dump)"; "$ADB" shell am force-stop $PKG; sleep 1; launch; new_game
done
[ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" = 0 ] || fail "a crash during monkey"
echo "NAMES $TAG: monkey $EVENTS events, no crash"
echo "NAMES $TAG PASSED (screenshots in $OUT)"
