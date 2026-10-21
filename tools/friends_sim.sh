#!/usr/bin/env bash
# Friends, invites and rematch (#72) on two phones against the Firebase emulators. Both phones launch with the debug
# extra `--ez fakegoogle true` (debug builds on the emulators only), which counts them as signed in with Google: that is
# the only part faked. Real Google accounts on two phones stay Mauro's release check.
#   1. tools/online_sim.sh plays a whole game: host on lane 1, guest joins by code, a bot plays every seat.
#   2. Rematch: the host taps Play again on the results; the guest's results then offer Join rematch; both meet in the
#      new room (a new code, the same ruleset).
#   3. Both apps are closed and opened again. The host's start screen offers Quick game with friends, lists the guest,
#      stars them, picks them and taps Start; the guest's start screen shows the invite, Join fills in the code, and
#      both meet in that room.
# Needs what online_sim.sh needs (both lanes up, the debug APK on both, the emulators running).
# Usage (Git Bash, project root): tools/friends_sim.sh [ruleset: base|chivalry] [timeout seconds for the game]
set -eEuo pipefail
trap 'echo "FAILED at line $LINENO: $BASH_COMMAND"' ERR
RULESET="${1:-base}"
# Age of Chivalry games between bots run long (HAVEN-428: 826 moves, just over 15 minutes), so it gets twice the time.
LIMIT="${2:-$([ "${1:-base}" = chivalry ] && echo 1800 || echo 900)}"
# PORTS: the emulators' Firestore and Auth ports when not 8080/9099, e.g. PORTS="--ei fsport 8185 --ei authport 9199".
PORTS="${PORTS:-}"
EXTRAS="--es firebase emu --ez fakegoogle true --ez autoplay true --ez quickbots true $PORTS"
HOST="${HOST_SERIAL:-emulator-5554}"
GUEST="${GUEST_SERIAL:-emulator-5556}"
A="${ADB:-$(command -v adb || echo "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")}"
export MSYS_NO_PATHCONV=1
LABEL="Base game"; [ "$RULESET" = chivalry ] && LABEL="Age of Chivalry"

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
# On any failure, each phone's screen, screen dump and log beside the shard log (the device workflow uploads *.png,
# *.xml and *.logcat.txt), so a CI failure can be read from its artifact alone.
evidence() {
  local d f
  for d in $HOST $GUEST; do
    f="${OUT:-.}/friends-fail-$d"
    "$A" -s "$d" exec-out screencap -p > "$f.png" 2>/dev/null || true
    dump "$d" > "$f.xml" 2>/dev/null || true
    "$A" -s "$d" logcat -d > "$f.logcat.txt" 2>/dev/null || true
  done
  echo "  screens, dumps and logs: ${OUT:-.}/friends-fail-*"
}
trap 'rc=$?; trap - EXIT; [ $rc = 0 ] || evidence; exit $rc' EXIT
centre() { grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' '; }
tap_text() {
  local b; b=$(dump "$1" | grep "\(text\|content-desc\)=\"$2\"" | sed -n "${3:-1}p" | centre) || true
  [ -n "$b" ] || { echo "[$1] '$2' not on screen"; return 1; }
  set -- "$1" $b; "$A" -s "$1" shell input tap $(( ($2 + $4) / 2 )) $(( ($3 + $5) / 2 )); sleep 0.8
}
texts() { dump "$1" | grep -o 'text="[^"]\+"' | sed 's/text=//' | tr '\n' ' '; }
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
room_code() { texts "$1" | grep -o 'Room [A-Z]*-[0-9]*' | head -1 | cut -d' ' -f2 || true; }
crashed() {
  local bad=0
  for d in $HOST $GUEST; do
    if "$A" -s $d logcat -d | grep -A8 "FATAL EXCEPTION" | grep -m1 -q "com.mauro.wgbg"; then
      echo "CRASH on $d:"; "$A" -s $d logcat -d | grep -A12 "FATAL EXCEPTION" | head -14; bad=1
    fi
  done
  return $bad
}

# 1. A whole game (online_sim clears both apps first, so neither starts with friends).
EXTRA="--ez fakegoogle true $PORTS" bash tools/online_sim.sh "$RULESET" "$LIMIT"
FIRST=$(texts $HOST | grep -o 'Room [A-Z]*-[0-9]*' | head -1 || true)

# 2. Rematch from the results.
echo "Rematch..."
wait_scroll $HOST "Play again" 30
tap_text $HOST "Play again"
wait_for $HOST "Room [A-Z]*-[0-9]*" 30
R2=$(room_code $HOST)
[ -n "$R2" ] || { echo "FAIL: no rematch room on the host"; exit 1; }
echo "rematch room $R2"
wait_scroll $GUEST "Join rematch" 30
tap_text $GUEST "Join rematch"
wait_for $GUEST "Erin" 30
wait_for $HOST "Erin" 30
[ "$(room_code $GUEST)" = "$R2" ] || { echo "FAIL: the guest is in '$(room_code $GUEST)', not $R2"; exit 1; }
texts $HOST | grep -q "$LABEL" || { echo "FAIL: the rematch isn't $LABEL"; texts $HOST; exit 1; }
crashed || { echo "FAIL: the app crashed"; exit 1; }
echo "PASS: rematch $R2 (after ${FIRST:-the first room})"

# 3. Close both apps and open them again: each goes back into the rematch room, as after a crash; leave it from there.
for d in $HOST $GUEST; do
  "$A" -s $d logcat -c
  "$A" -s $d shell am force-stop com.mauro.wgbg
  "$A" -s $d shell am start --activity-clear-task -n com.mauro.wgbg/.MainActivity $EXTRAS >/dev/null
done
sleep 5
wait_for $GUEST "Room $R2" 30
wait_for $HOST "Room $R2" 30
wait_for $HOST "Erin" 15
tap_text $GUEST "Leave room"              # the guest's own Leave button (#72), then its confirm
tap_text $GUEST "Leave room" 2
wait_for $GUEST "New game" 15             # straight back to the start screen
gone=; for _ in $(seq 1 15); do texts $HOST | grep -q "Erin" || { gone=1; break; }; sleep 1; done
[ -n "$gone" ] || { echo "FAIL: the guest left but still holds a seat"; texts $HOST; exit 1; }
echo "PASS: the guest left $R2 by its Leave button and the seat opened"
tap_text $HOST "Leave room"
tap_text $HOST "Leave room" 2             # the confirm box's button, over the lobby's own
"$A" -s $HOST shell input keyevent KEYCODE_BACK    # Leave room opens the setup screen; Back to the start
wait_scroll $HOST "Quick game with friends" 30
tap_text $HOST "Quick game with friends"
wait_for $HOST "Erin" 15
tap_text $HOST "☆" 1                    # star the guest: the star must stick
wait_for $HOST "★" 10
tap_text $HOST "Erin"                   # pick them
choosers=$("$A" -s $HOST logcat -d -s ActivityTaskManager:I 2>/dev/null | grep -c 'act=android.intent.action.CHOOSER' || true)
tap_text $HOST "Start"
# Start sends the invite link through Android's share sheet. CI's aosp_atd image may show no sheet (and never our text),
# so what is checked is that the app asked for one: a CHOOSER start in the log. A sheet that does show is closed with Back.
chooser() { "$A" -s $HOST logcat -d -s ActivityTaskManager:I 2>/dev/null | grep -c 'act=android.intent.action.CHOOSER' || true; }
shared=; for _ in $(seq 1 30); do [ "$(chooser)" -gt "$choosers" ] && { shared=1; break; }; sleep 1; done
[ -n "$shared" ] || { echo "FAIL: Start sent no invite (no share sheet asked for in 30 s)"; texts $HOST; exit 1; }
sleep 2
top=$("$A" -s $HOST shell dumpsys activity activities | tr -d '\r' | grep 'topResumedActivity' || true)
case "$top" in ""|*com.mauro.wgbg/*) ;; *) echo "share sheet on top, closed with Back: ${top##*u0 }"; "$A" -s $HOST shell input keyevent KEYCODE_BACK;; esac
wait_for $HOST "Room [A-Z]*-[0-9]*" 15
R3=$(room_code $HOST)
[ -n "$R3" ] && [ "$R3" != "$R2" ] || { echo "FAIL: no new room for the quick game ('$R3')"; texts $HOST; exit 1; }
echo "quick game room $R3"
wait_for $GUEST "Mauro invited you: $LABEL" 30
tap_text $GUEST "Join"                  # the invite's Join: opens the Join screen with the code filled in
wait_for $GUEST "Room $R3" 15
tap_text $GUEST "Join"
wait_for $GUEST "Erin" 30
wait_for $HOST "Erin" 30
crashed || { echo "FAIL: the app crashed"; exit 1; }
echo "PASS: rematch, friends list, star, invite and join"
