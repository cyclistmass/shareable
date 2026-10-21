#!/usr/bin/env bash
# The update a player gets: the latest release installed fresh, a game started from the start screen by taps and a
# homestead and road placed, then this build installed over it. The start screen must offer Resume, the game must come
# back with the same players under the same names (bots are named by their colour, so a seat whose colour changed shows
# here: 0.3.4 turned a 0.3.3 save's Orange into Purple) and play must go on. Screenshots before and after land in $OUT.
# Board taps use the WGBG-TURN line's spots= (debug builds, which the releases are); everything else is a tap on a label.
# Usage: tools/ci_update.sh <new apk> [old apk, default: the latest release from GitHub]   ($ANDROID_SERIAL or the only device)
set -uo pipefail
NEW="${1:?new apk}"; OLD="${2:-}"
ADB="${ADB:-adb}"; PKG=com.mauro.wgbg
OUT="${OUT:-.}"; mkdir -p "$OUT"
export MSYS_NO_PATHCONV=1
# adb.exe on Windows needs Windows paths for the APKs it installs.
native() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else printf '%s\n' "$1"; fi; }

. "$(dirname "$0")/lib/uiread.sh"   # dump: the app answers in 0.2 s; the old release (no WGBG-UI) falls back to uiautomator
fail() {
  echo "UPDATE FAILED: $*"
  "$ADB" exec-out screencap -p > "$OUT/update-fail.png" 2>/dev/null; dump > "$OUT/update-fail.xml"
  echo "  screen: $OUT/update-fail.png  dump: $OUT/update-fail.xml"; exit 1
}
centre() { grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 | grep -o '[0-9]\+' | tr '\n' ' ' | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'; }
# The screen as the app is drawn (the root node's bounds): `wm size` says portrait while the game runs landscape.
screen_size() { dump | grep -o 'bounds="\[0,0\]\[[0-9]*,[0-9]*\]' | head -1 | grep -o '[0-9]\+' | tail -2 | paste -d' ' - -; }
# scroll: a finger's swipe up inside every scrolling panel on the screen (a wide screen shows two side by side; a swipe
# between them moves nothing), or the middle of the screen when none says it scrolls.
scroll() {
  local boxes b
  boxes=$(dump | grep 'scrollable="true"' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | grep -o '[0-9]\+' | paste -d' ' - - - -)
  [ -n "$boxes" ] || boxes="0 0 $(screen_size)"
  # </dev/null: adb would otherwise eat the rest of the list, and only the first panel would scroll.
  while read -r b; do set -- $b
    "$ADB" shell input swipe $((($1 + $3) / 2)) $(($2 + ($4 - $2) * 7 / 10)) $((($1 + $3) / 2)) $(($2 + ($4 - $2) * 3 / 10)) 400 </dev/null
  done <<< "$boxes"
  sleep 0.8
}
# tap_text <label> [scrolls]: taps the label, scrolling the screen up (a finger's swipe) up to [scrolls] times to find it.
tap_text() {
  local xy i
  for i in $(seq 0 "${2:-4}"); do
    xy=$(dump | grep -F "text=\"$1\"" | centre)
    [ -n "$xy" ] && { echo "  tap '$1'"; "$ADB" shell input tap $xy; sleep 1.5; return 0; }
    scroll
  done
  return 1
}
on_screen() { dump | grep -qF "text=\"$1\""; }
# The players' names, in order: the text in the player list's area (the left 40% of the screen, under the heading),
# without the counts and the prompts. Both 0.3.3 (no test tags) and later builds lay the list out there.
names() {
  local W; read -r W _ < <(screen_size)
  dump | grep 'text="[^"]' | while read -r l; do
    t=$(echo "$l" | sed -n 's/.*text="\([^"]*\)".*/\1/p'); x=$(echo "$l" | sed -n 's/.*bounds="\[\([0-9]*\),.*/\1/p')
    [ -n "$x" ] && [ "$x" -lt $((W * 4 / 10)) ] || continue
    case "$t" in ''|*[!A-Za-z\ \']*|Menu|"Card test"*|Turn*) continue;; esac
    # The event feed shares that corner ("White built a road") and needn't survive a resume: a name has no
    # lowercase word of four letters or more, a feed line always does.
    echo " $t " | grep -qE ' [a-z]{4,} ' && continue
    printf '%s ' "$t"
  done
}
turn_line() { "$ADB" logcat -d -s WGBG-TURN:I | tail -1; }
# my_move: waits (up to 120 s) for a prompt with board spots for player 0 and taps one twice (select, then confirm).
my_move() {
  local line spots xy
  for _ in $(seq 120); do
    line=$(turn_line)
    case "$line" in *" player=0 "*spots=*)
      spots=$(echo "$line" | sed -n 's/.* spots=\([^ ]*\).*/\1/p' | tr ';' '\n' | head -1); xy=${spots#*@}
      [ -n "$xy" ] && { "$ADB" shell input tap "${xy%,*}" "${xy#*,}"; sleep 0.6; "$ADB" shell input tap "${xy%,*}" "${xy#*,}"; sleep 2; return 0; };;
    esac
    sleep 1
  done
  return 1
}
version() { "$ADB" shell dumpsys package $PKG | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1; }

if [ -z "$OLD" ]; then
  OLD="$OUT/latest-release.apk"
  curl -sSfL -m 300 -o "$OLD" https://github.com/cyclistmass/shareable/releases/latest/download/working-german-board-game.apk \
    || fail "could not download the latest release"
fi

# 1. The release, as a player has it: installed fresh, a game started from the start screen.
"$ADB" uninstall $PKG >/dev/null 2>&1 || true
"$ADB" install "$(native "$OLD")" | tail -1 | grep -q Success || fail "the old APK did not install"
from=$(version); echo "installed $from"
"$ADB" logcat -c
timeout 60 "$ADB" shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 3
on_screen "Tap to skip" && tap_text "Tap to skip" 0
tap_text "New game" || fail "no New game on the start screen of $from"
tap_text "Play" 6 || fail "no Play on the game-start screen of $from"
my_move || fail "never got a homestead to place in $from"
my_move || fail "never got a road to place in $from"
sleep 2
before=$(names); echo "players in $from: $before"
[ -n "$before" ] || fail "no player names on the screen in $from"
"$ADB" exec-out screencap -p > "$OUT/update-1-before.png"
"$ADB" shell am force-stop $PKG

# 2. This build over it, as the update arrives: the saved game must come back unchanged and go on.
"$ADB" install -r "$(native "$NEW")" | tail -1 | grep -q Success || fail "the new APK did not install over $from (signing key?)"
to=$(version); echo "updated $from -> $to"
"$ADB" logcat -c
timeout 60 "$ADB" shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 3
on_screen "Tap to skip" && tap_text "Tap to skip" 0
tap_text "Resume game" || fail "no Resume game after updating $from -> $to"
sleep 3
after=$(names); echo "players in $to:  $after"
"$ADB" exec-out screencap -p > "$OUT/update-2-after.png"
[ "$before" = "$after" ] || fail "the players changed with the update: '$before' became '$after'"
line=$(turn_line)
my_move || {
  # Not a board move for me yet (a bot's turn, or a roll): a roll by its label is a move too.
  tap_text "Roll" 0 || fail "play did not go on after the update (last turn line: ${line:0:300})"; }
sleep 3
[ "$(turn_line)" != "$line" ] || fail "nothing happened after a move in the resumed game"
crash=$("$ADB" logcat -d -b crash | grep -c "FATAL EXCEPTION" || true)
[ "$crash" = 0 ] || fail "the app crashed after the update"
echo "update $from -> $to: ok (Resume, same players: $after, play goes on)"
