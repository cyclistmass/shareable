#!/usr/bin/env bash
# Two-phone online game against the Firebase emulators (#7, #35): lane 1 hosts, lane 2 joins by room code,
# both mark ready, the host starts, and a bot plays every seat (debug "autoplay"). Each phone logs a
# fingerprint when the game ends; the script fails unless both phones report the same one.
#
# Needs: both emulator lanes up (tools/emulator.ps1 -Lane 1 / -Lane 2), the debug APK installed on both, and
#   firebase emulators:start --only firestore,auth --project demo-wgbg   (Java 21+ on PATH; the debug build needs no
#   google-services.json for this: --es firebase emu sets up the demo project in code)
# Usage (Git Bash, project root): [FIREBASE=live] [REOPEN_AT=moves] [EXTRA="--ez ..."] tools/online_sim.sh [ruleset: base|chivalry] [timeout seconds]
#   REOPEN_AT=150: once the joiner has seen that many moves, close its app for good and open it again; it must rejoin,
#   catch up and end on the same fingerprint (the 0.3.4 check).
# Under pipefail a grep that finds nothing fails its whole pipeline: every one here is guarded (|| true, if, &&).
set -eEuo pipefail
trap 'echo "FAILED at line $LINENO: $BASH_COMMAND"' ERR
RULESET="${1:-base}"
# Age of Chivalry games between bots run long (HAVEN-428: 826 moves, just over 15 minutes), so it gets twice the time.
LIMIT="${2:-$([ "${1:-base}" = chivalry ] && echo 1800 || echo 900)}"
# FIREBASE=live plays on the real project (needs google-services.json in the build and deployed rules).
FB_EXTRA="--es firebase emu"; [ "${FIREBASE:-emu}" = live ] && FB_EXTRA=""
# EXTRA: more launch extras for both phones, e.g. "--ez fakegoogle true" (tools/friends_sim.sh).
EXTRA="${EXTRA:-}"
HOST="${HOST_SERIAL:-emulator-5554}"
GUEST="${GUEST_SERIAL:-emulator-5556}"
A="${ADB:-$(command -v adb || echo "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")}"
export MSYS_NO_PATHCONV=1

# The screen of device $1 as one element per line. A busy host makes uiautomator answer "null root node" now and then:
# up to 6 tries, then a note on stderr and empty output (status 0, as before, so callers under pipefail are unchanged).
dump() {
  local x
  for _ in 1 2 3 4 5 6; do
    "$A" -s "$1" shell rm -f /sdcard/ui.xml; "$A" -s "$1" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    x=$("$A" -s "$1" shell cat /sdcard/ui.xml 2>/dev/null) || true
    case "$x" in *hierarchy*) printf '%s\n' "$x" | tr '>' '\n'; return 0;; esac
    sleep 1
  done
  echo "[$1] uiautomator dump failed 6 times (busy device?)" >&2
  return 0
}
centre() { grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' '; }
# Taps the Nth node whose text is exactly $2 on device $1.
tap_text() {
  local b; b=$(dump "$1" | grep "\(text\|content-desc\)=\"$2\"" | sed -n "${3:-1}p" | centre) || true
  [ -n "$b" ] || { echo "[$1] '$2' not on screen"; return 1; }
  set -- "$1" $b; "$A" -s "$1" shell input tap $(( ($2 + $4) / 2 )) $(( ($3 + $5) / 2 )); sleep 0.8
}
# Taps $2 on device $1, scrolling the left (Players) panel to it as a player would: the game-start screen is taller
# than a phone (0.3.4's Colours rows put the Game row below the fold). Scrolls back to the top afterwards.
scroll_tap() {
  local w h i; read -r w h < <("$A" -s "$1" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' '); [ "$w" -lt "$h" ] && { i=$w; w=$h; h=$i; }
  for i in 1 2 3 4 5 6; do
    tap_text "$1" "$2" 2>/dev/null >/dev/null && { for _ in 1 2 3 4 5 6; do "$A" -s "$1" shell input swipe $((w / 4)) $((h / 3)) $((w / 4)) $((h * 3 / 4)) 300; done; return 0; }
    "$A" -s "$1" shell input swipe $((w / 4)) $((h * 3 / 4)) $((w / 4)) $((h / 3)) 400; sleep 0.6
  done
  echo "[$1] '$2' not on screen, even scrolled"; return 1
}
# Replaces the text of the Nth text box on device $1 with $3.
fill() {
  local b; b=$(dump "$1" | grep 'class="android.widget.EditText"' | sed -n "$2p" | centre) || true
  [ -n "$b" ] || { echo "[$1] no text box $2"; return 1; }
  local text="$3"; set -- "$1" $b
  "$A" -s "$1" shell input tap $(( ($2 + $4) / 2 )) $(( ($3 + $5) / 2 )); sleep 0.6
  # End, then Delete well past the box's length: Ctrl+A doesn't always select on these images ("You" came out "YoErin").
  "$A" -s "$1" shell input keyevent KEYCODE_MOVE_END $(printf 'KEYCODE_DEL %.0s' $(seq 1 24))
  "$A" -s "$1" shell input text "$text"; "$A" -s "$1" shell input keyevent KEYCODE_ENTER; sleep 0.6  # Enter (IME Done) hides the keyboard; Back would leave the screen when it is already hidden
  # On a box with a Next key, Enter only moves on and the keyboard stays up over the next box: then Back closes it.
  "$A" -s "$1" shell dumpsys input_method | grep -q "mInputShown=true" && { "$A" -s "$1" shell input keyevent KEYCODE_BACK; sleep 0.6; }
  return 0
}
texts() { dump "$1" | grep -o 'text="[^"]\+"' | sed 's/text=//' | tr '\n' ' '; }
# Waits up to $3 s for text matching $2 on device $1.
wait_for() { for _ in $(seq 1 "$3"); do texts "$1" | grep -q "$2" && return 0; sleep 1; done; echo "[$1] timed out waiting for '$2'"; texts "$1"; return 1; }
# A finger's swipe up inside each scrolling panel on device $1 (a wide screen shows two side by side; a swipe between
# them moves nothing).
swipe_panels() {
  local l t r b
  { dump "$1" | grep 'scrollable="true"' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]\+' | paste -d' ' - - - - || true; } |
    while read -r l t r b; do "$A" -s "$1" shell input swipe $(((l + r) / 2)) $((t + (b - t) * 7 / 10)) $(((l + r) / 2)) $((t + (b - t) * 3 / 10)) 400 </dev/null; done
}
# Waits for the button or label whose whole text is $2 (what tap_text taps), scrolling to it as a person would, a swipe
# every third second it's missing: on CI's 640x320 screen Host and Join sit below Play in New game's right panel, which
# uiautomator doesn't list until it's scrolled to. Whole text: a substring match took the Friend seat's hint ("Kept open
# for a friend when you Host a room...") for the button and never scrolled (release check run 39657882276).
wait_scroll() {
  local i
  for i in $(seq 1 "$3"); do
    [ "$(dump "$1" | grep -cF "text=\"$2\"")" != 0 ] && return 0
    [ $((i % 3)) != 0 ] || swipe_panels "$1"
    sleep 1
  done
  echo "[$1] timed out waiting for '$2', even scrolled"; texts "$1"; return 1
}

for d in $HOST $GUEST; do
  [ "$("$A" -s $d shell settings get global airplane_mode_on | tr -d '\r')" = 1 ] && "$A" -s $d shell cmd connectivity airplane-mode disable
  # Gboard's "Try out your stylus" demo swallows adb typing on newer images: no stylus handwriting.
  "$A" -s $d shell settings put secure stylus_handwriting_enabled 0
  "$A" -s $d logcat -c
  "$A" -s $d shell am force-stop com.mauro.wgbg
  "$A" -s $d shell pm clear com.mauro.wgbg >/dev/null   # no remembered room to rejoin, no stale sign-in
  "$A" -s $d shell am start --activity-clear-task -n com.mauro.wgbg/.MainActivity $FB_EXTRA --ez autoplay true --ez quickbots true $EXTRA >/dev/null
done
sleep 5
# Android's one-time "Viewing full screen" hint, on whichever phone is fresh (a new AVD shows it on the host too).
for d in $HOST $GUEST; do tap_text $d "Got it" 2>/dev/null || true; done

# The splash (#132) first: New game opens the game-start screen with Host and Join.
for d in $HOST $GUEST; do wait_scroll $d "New game" 30 && tap_text $d "New game"; done

# Host: name, a Friend seat for the guest, the ruleset, then open the room.
wait_for $HOST "Your name" 20
fill $HOST 1 Mauro
tap_text $HOST "Friend" 1
[ "$RULESET" = chivalry ] && scroll_tap $HOST "Age of Chivalry"
wait_scroll $HOST "Host a room" 20
tap_text $HOST "Host a room"
wait_for $HOST "Room [A-Z]*-[0-9]*" 20
CODE=$(texts $HOST | grep -o 'Room [A-Z]*-[0-9]*' | head -1 | cut -d' ' -f2) || true
[ -n "$CODE" ] || { echo "no room code on the host"; exit 1; }
echo "Room $CODE"

# Guest: join by code (typed the way people do: lower case, no dash).
fill $GUEST 1 Erin                  # online needs a real name: Join stays off for "You"
wait_scroll $GUEST "Join a room" 20
tap_text $GUEST "Join a room"
wait_for $GUEST "Room code" 10
fill $GUEST 2 "$(echo "$CODE" | tr -d - | tr '[:upper:]' '[:lower:]')"
tap_text $GUEST "Join"
wait_for $GUEST "Erin" 20
wait_for $HOST "Erin" 20
texts $HOST | grep -q '"[^" ]\+Erin"' && { echo "FAIL: the guest's name came out wrong"; texts $HOST; exit 1; }

# Both ready; the host starts.
tap_text $HOST "Not ready" 1
sleep 2
tap_text $GUEST "Not ready" 1
wait_scroll $HOST "Start game" 20
sleep 2
tap_text $HOST "Start game"

# Play out: wait for both fingerprints.
echo "Playing (up to ${LIMIT}s)..."
t0=$(date +%s)
moves() { "$A" -s "$1" logcat -d -s WGBG-MOVES:I | grep -c ' #' || true; }
if [ -n "${REOPEN_AT:-}" ]; then
  for _ in $(seq 1 200); do n=$(moves $GUEST); [ "$n" -ge "$REOPEN_AT" ] && break; sleep 3; done
  echo "joiner saw $n moves; closing its app at $(date +%T)"
  "$A" -s $GUEST shell am force-stop com.mauro.wgbg; sleep 5
  "$A" -s $GUEST shell am start -n com.mauro.wgbg/.MainActivity $FB_EXTRA --ez autoplay true --ez quickbots true $EXTRA >/dev/null
  for _ in $(seq 1 60); do m=$(moves $GUEST); [ "$m" -gt "$n" ] && break; sleep 2; done
  [ "$m" -gt "$n" ] || { echo "FAIL: the joiner didn't catch up after reopening ($m move lines)"; exit 1; }
  echo "joiner back at $(date +%T): $m move lines, pid $("$A" -s $GUEST shell pidof com.mauro.wgbg || true)"
fi
while :; do
  H=$("$A" -s $HOST logcat -d -s WGBG-ONLINE:I | grep -o 'game over.*' | tail -1 || true)
  G=$("$A" -s $GUEST logcat -d -s WGBG-ONLINE:I | grep -o 'game over.*' | tail -1 || true)
  [ -n "$H" ] && [ -n "$G" ] && break
  if (( $(date +%s) - t0 > LIMIT )); then echo "TIMEOUT: host='$H' guest='$G'"; exit 1; fi
  sleep 10
done
echo "host:  $H"
echo "guest: $G"
CRASH=0
for d in $HOST $GUEST; do
  if "$A" -s $d logcat -d | grep -A8 "FATAL EXCEPTION" | grep -m1 -q "com.mauro.wgbg"; then
    echo "CRASH on $d:"; "$A" -s $d logcat -d | grep -A12 "FATAL EXCEPTION" | head -14; CRASH=1
  fi
done
[ $CRASH = 0 ] || { echo "FAIL: the app crashed"; exit 1; }
if [ "$H" = "$G" ]; then echo "PASS: both phones agree ($(( $(date +%s) - t0 )) s)"; else echo "FAIL: the phones disagree"; exit 1; fi
