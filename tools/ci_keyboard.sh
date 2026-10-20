#!/usr/bin/env bash
# Back and the keyboard on every text box outside a game (#121 and its 0.3.2 regression), by real taps and keys:
#   keyboard up:     Back only puts the keyboard away and the screen stays; the next Back leaves (#146).
#   typed + Enter:   a one-line box closes the keyboard and Back leaves; a note (several lines) gets a new line, then
#                    Back only closes the keyboard and the next Back leaves.
# 0.3.2 before e57e1f7 failed "typed + Enter" on the Join screen: the keyboard came back and Back did nothing.
# 0.3.2 on Android 14 needed one extra Back to leave the Report note after a new line (#146).
# Boxes: game-start name, Join name and room code, Report note. (The card tester's note sits in a game; check by hand.)
# Usage: tools/ci_keyboard.sh   (the app installed on $ANDROID_SERIAL or the only device)
set -uo pipefail
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
export MSYS_NO_PATHCONV=1
fail=0

dump() { for _ in 1 2 3; do "$ADB" shell rm -f /sdcard/ui.xml; "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local x; x=$("$ADB" shell cat /sdcard/ui.xml 2>/dev/null); case "$x" in *hierarchy*) echo "$x" | tr '>' '\n'; return;; esac; sleep 1; done; }
centre() { grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 | tr -c '0-9\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }
tap_text() { local xy; xy=$(dump | grep "text=\"$1\"" | centre); [ -n "$xy" ] || return 1; "$ADB" shell input tap $xy; sleep 1.5; }
box() { dump | grep 'class="android.widget.EditText"' | sed -n "${1}p" | centre; }
on() { dump | grep -q "text=\"$1\""; }
kb() { "$ADB" shell dumpsys input_method | grep -q "mInputShown=true"; }
key() { "$ADB" shell input keyevent "KEYCODE_$1"; sleep 2; }
bad() { echo "keyboard: FAILED: $*"; fail=1; "$ADB" exec-out screencap -p > "${OUT:-.}/keyboard-fail.png" 2>/dev/null; }
open_box() { local xy; xy=$(box "$1"); [ -n "$xy" ] || { bad "$2: no text box $1"; return 1; }; "$ADB" shell input tap $xy; sleep 1.5
  kb || { bad "$2: tapping box $1 didn't bring up the keyboard"; return 1; }; }

# Keyboard up, Back: the keyboard goes, the screen (marked by $2) stays; the next Back leaves it.
up_back() { open_box "$1" "$3" || return 1; key BACK
  kb && { bad "$3: Back left the keyboard up"; return 1; }
  on "$2" || { bad "$3: Back with the keyboard up left the screen"; return 1; }
  key BACK; kb && { bad "$3: the Back after brought the keyboard back"; return 1; }
  on "$2" && { bad "$3: with the keyboard put away, Back didn't leave the screen (#146)"; return 1; }; echo "keyboard: $3, keyboard up + Back + Back: ok"; }
# Typed + Enter, then Back (twice for a note): the screen marked by $2 is left.
enter_back() { open_box "$1" "$3" || return 1
  "$ADB" shell input keyevent KEYCODE_MOVE_END; for _ in 1 2 3 4 5 6 7 8; do "$ADB" shell input keyevent KEYCODE_DEL; done
  "$ADB" shell input text "$4"; sleep 0.6; key ENTER
  if [ "$5" = note ]; then
    kb || { bad "$3: Enter in a note closed the keyboard"; return 1; }
    key BACK; kb && { bad "$3: Back left the keyboard up"; return 1; }
    on "$2" || { bad "$3: Back with the keyboard up left the screen"; return 1; }
  else
    kb && { bad "$3: Enter left the keyboard up"; return 1; }
  fi
  key BACK; kb && { bad "$3: after Enter, Back brought back or kept the keyboard"; return 1; }
  # #146: on Android 14, after a new line in a note, leaving took one extra Back. No extra Back is allowed.
  on "$2" && { bad "$3: after Enter, Back didn't leave the screen (#146)"; return 1; }; echo "keyboard: $3, typed + Enter + Back: ok"; }

"$ADB" shell am force-stop $PKG
"$ADB" shell pm clear $PKG >/dev/null
"$ADB" logcat -b crash -c 2>/dev/null || true
timeout 60 "$ADB" shell am start -W --activity-clear-task -n $PKG/.MainActivity >/dev/null || { echo "keyboard: FAILED: the app didn't start"; exit 1; }
for _ in $(seq 1 15); do on "Tap to skip" || on "New game" && break; sleep 2; done
on "Tap to skip" && { tap_text "Tap to skip"; sleep 2; }
for _ in $(seq 1 10); do on "New game" && break; sleep 1; done

# Opens a screen by its button and checks it really opened (marked by $2): otherwise every check after it is moot.
go() { tap_text "$1" && on "$2" || { bad "\"$1\" didn't open its screen"; [ $fail = 0 ] || { echo "keyboard: FAILED"; exit 1; }; }; }
go "New game" "Base game"
# Game-start screen (marked by its Base game choice); leaving goes back to the start screen.
up_back 1 "Base game" "game-start name"
go "New game" "Base game"
enter_back 1 "Base game" "game-start name" "Duo" line
# Online needs a real name ("You" keeps Join a room off): type one and stay.
go "New game" "Base game"
open_box 1 "game-start name" && { "$ADB" shell input keyevent KEYCODE_MOVE_END; for _ in 1 2 3 4 5 6 7 8; do "$ADB" shell input keyevent KEYCODE_DEL; done
  "$ADB" shell input text Duo; key ENTER; }
# Join screen (marked by Room code): name, then code; each leave goes back to the game-start screen.
go "Join a room" "Room code"
up_back 1 "Room code" "join name"
go "Join a room" "Room code"
up_back 2 "Room code" "join code"
go "Join a room" "Room code"
enter_back 2 "Room code" "join code" "ROVER-42" line
go "Join a room" "Room code"
enter_back 1 "Room code" "join name" "Duo" line
# Report (marked by Add a picture): the note has several lines.
go "Report" "Add a picture"
up_back 1 "Add a picture" "report note"
go "Report" "Add a picture"
enter_back 1 "Add a picture" "report note" "Test" note

[ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" = 0 ] || bad "the app crashed"
[ $fail = 0 ] && echo "keyboard: PASSED"
exit $fail
