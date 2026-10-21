#!/usr/bin/env bash
# The player list never overlaps itself or the dice (0.3.4): at every screen size and font scale, in the base game and
# Age of Chivalry with 4 players, with the list at its fullest (the longest names, double-digit counts, every badge on
# one player, the Raiders warning), with an open trade offer and with the discard sheet open. Each step takes a
# uiautomator dump and runs tools/layout_check.py on it: no two text or icon nodes inside the Players panel may
# intersect, and none may cross the panel's edge or the dice row. 0.3.3 put the dice over White's row at 640x320.
# Every step also asserts "4p: last player's row fully visible" (White's name, points, badges and counts all there,
# full height, the list not scrolled) and that no text, icon or dice node sits under the camera cutout (0.3.4: the
# lens sat on the dice on Mauro's phone). The cutout pass turns on each emulated cutout and turns the phone both ways.
# Debug launch extras and broadcasts set the game up (an automated test, not user testing). Screen size, density,
# font scale, rotation and the cutout overlay are put back on any exit, and the scenery setting goes back to its default.
# On a failure the screen and the dumps land in $OUT (default: the working dir).
# Usage: tools/ci_layout.sh   (the debug app installed on $ANDROID_SERIAL or the only device; Python 3: python3, python or py -3,
#        whichever runs; or PY="py -3.12")
set -uo pipefail
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
OUT="${OUT:-.}"
# The first Python that really runs: on Windows "python3" can be the Microsoft Store stub, which only prints a hint.
if [ -z "${PY:-}" ]; then for c in python3 python "py -3"; do $c -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2>/dev/null && { PY=$c; break; }; done; fi
[ -n "${PY:-}" ] || { echo "layout: FAILED: no Python 3 found (set PY)"; exit 1; }
read -ra PY <<< "$PY"
HERE="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1
fail=0; steps=0; t0=$SECONDS
"$ADB" logcat -b crash -c

# Portrait size and density for wm; the app turns them to landscape: 640x320 dp (CI's), 640x360 dp, 914x411 dp (a
# phone), and two common phone shapes at their real densities (1080x2340, 1440x3200).
SIZES="${SIZES:-320x640:160 720x1280:320 1080x2400:420 1080x2340:440 1440x3200:560}"
FONTS="${FONTS-1.0 1.3}"
MODES="${MODES:-base chivalry}"
# The cutout pass: each emulated camera cutout, the phone turned both ways, at the smallest and largest phone, font 1.3.
CUTOUTS="${CUTOUTS-hole tall corner double}"
CUTOUT_SIZES="${CUTOUT_SIZES:-320x640:160 1440x3200:560}"
CUTOUT_FONT="${CUTOUT_FONT:-1.3}"
# Run part of it (a slow emulator, a time limit per call): FONTS="" for the cutout pass alone, CUTOUTS="" without it.

setting() { "$ADB" shell settings get system "$1" | tr -d '\r'; }
FONT0=$(setting font_scale); case "$FONT0" in ''|null) FONT0=1.0;; esac
ROT0=$(setting user_rotation); AUTO0=$(setting accelerometer_rotation)
FIXED0=$("$ADB" shell wm fixed-to-user-rotation | tr -d '' | tr 'A-Z' 'a-z')
OVL=com.android.internal.display.cutout.emulation
overlays_on() { "$ADB" shell cmd overlay list | tr -d '\r' | sed -n "s/^\[x\] \($OVL\..*\)/\1/p"; }
CUT0=$(overlays_on)
# Turns every emulated cutout off, then $1 (if given) on.
cutout() { local o; for o in $(overlays_on); do "$ADB" shell cmd overlay disable "$o"; done
  if [ -n "${1:-}" ]; then "$ADB" shell cmd overlay enable "$1"; fi; }
restore() { view --ef scenery -1; "$ADB" shell wm size reset; "$ADB" shell wm density reset; "$ADB" shell settings put system font_scale "$FONT0"
  cutout "$CUT0"
  case "$ROT0" in ''|null) ;; *) "$ADB" shell settings put system user_rotation "$ROT0";; esac
  case "$AUTO0" in ''|null) ;; *) "$ADB" shell settings put system accelerometer_rotation "$AUTO0";; esac
  case "$FIXED0" in default|enabled|disabled) "$ADB" shell wm fixed-to-user-rotation "$FIXED0";; esac; }
trap restore EXIT; trap 'exit 1' INT TERM

turn() { "$ADB" logcat -d -s WGBG-TURN:I | grep 'WGBG-TURN' | tail -1; }
move() { "$ADB" shell am broadcast -a com.mauro.wgbg.MOVE -p $PKG --es move "$1" >/dev/null; }
view() { "$ADB" shell am broadcast -a com.mauro.wgbg.FILAMENT_VIEW -p $PKG "$@" >/dev/null; }

# Waits (up to $2 s) for player 0's turn with a legal move matching $1; prints the turn line.
wait_for() { local l; for _ in $(seq 1 $(( ${2:-60} * 2 ))); do l=$(turn)
  echo "$l" | grep -q 'player=0 ' && echo "$l" | grep -q "legal=.*$1" && { echo "$l"; return 0; }; sleep 0.5; done; return 1; }

# Dumps the screen and checks it; $1 names the step. The checker reads the camera cutout, in the current rotation,
# from `dumpsys window`.
check() { local name=$1 f="$OUT/layout-$1.xml" w="$OUT/layout-$1.window.txt"; steps=$((steps + 1))
  # uiautomator sometimes finds no window (just after a resize or a font change) or its last run still registered: retry.
  for _ in 1 2 3 4 5 6; do "$ADB" shell rm -f /sdcard/ui.xml; timeout 30 "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" exec-out cat /sdcard/ui.xml > "$f" 2>/dev/null; grep -q '</hierarchy>' "$f" && break; sleep 2; done
  "$ADB" shell dumpsys window | grep -m1 'mDisplayCutout=' > "$w"
  # env -u: Windows Python needs MSYS to turn /c/... paths into Windows paths; only adb wants it off.
  local r rc; r=$(env -u MSYS_NO_PATHCONV "${PY[@]}" -I "$HERE/layout_check.py" "$f" "$name" --cutout "$w" --players 4); rc=$?
  # The fullest list, not a game the activity restarted (White's stress name, Wilhelmina Moore, possibly cut short).
  ! grep -q '</hierarchy>' "$f" || grep -q 'Wilhelmin' "$f" || { r="$r
layout $name: the stress list is not on screen (the game restarted?)"; rc=1; }
  echo "$r" | grep -v ': OK$'
  if [ $rc != 0 ]; then
    fail=1; "$ADB" exec-out screencap -p > "$OUT/layout-$name.png" 2>/dev/null; echo "layout: FAILED at $name (dump and screen in $OUT)"
  else rm -f "$f" "$w"; fi; }
screen() { "$ADB" shell wm size "${1%%:*}"; "$ADB" shell wm density "${1##*:}"; sleep 2; }

# No first turn: say what the phone was doing (the last turn line, any crash or ANR, the activity on top) and keep its
# screen, dump and log beside the other failures, so a CI run shows why without a device.
noturn() { local f; f="$OUT/layout-noturn-$1-font$(setting font_scale)"
  echo "layout: FAILED: no first turn ($1, font $(setting font_scale), screen $("$ADB" shell wm size | tr -d '\r' | tail -1 | sed 's/.*: //'))"
  echo "  last turn line: $(turn | sed 's/.*WGBG-TURN//' | cut -c1-160)"
  echo "  on top: $("$ADB" shell dumpsys activity activities | tr -d '\r' | grep -m1 'topResumedActivity\|mResumedActivity' | sed 's/^ *//')"
  "$ADB" logcat -d > "$f.logcat.txt" 2>/dev/null
  grep -E 'FATAL EXCEPTION|ANR in|AndroidRuntime' -A6 "$f.logcat.txt" | head -20 | sed 's/^/  /'
  "$ADB" exec-out screencap -p > "$f.png" 2>/dev/null
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" exec-out cat /sdcard/ui.xml > "$f.xml" 2>/dev/null
  fail=1; }
# A fresh 4-player game in mode $1 at player 0's first turn, rolled (the dice row shows, the roll's mini cards sit in
# the list) and played to End turn, then the list at its fullest.
fresh() { local l; "$ADB" logcat -c
  "$ADB" shell am start -n $PKG/.MainActivity --activity-clear-task --es start "$1" --ei players 4 \
    --ez autosetup true --ez quickbots true >/dev/null
  wait_for 'ROLL' 90 >/dev/null || { noturn "$1"; return 1; }
  # The scenery still (sea, birds, boats), or uiautomator waits for an idle screen that never comes on a slow emulator.
  view --ef scenery 0
  move ROLL:0
  for _ in $(seq 1 20); do l=$(wait_for '' 10) || break; echo "$l" | grep -q 'legal=.*END_TURN' && break
    move "$(echo "$l" | sed 's/.*legal=//; s/ spots=.*//' | tr ',' '\n' | head -1)"; sleep 0.5; done
  view --es stress full; }

for font in $FONTS; do
  "$ADB" shell settings put system font_scale "$font"
  for mode in $MODES; do
    fresh "$mode" || continue
    for size in $SIZES; do screen "$size"; check "$mode-${size%%:*}-font$font-full"; done
    # An open trade offer (one Timber for one Clay), then the discard sheet, at the smallest and largest screens.
    if [ "$mode" = chivalry ]; then move OFFER_TRADE_CK:$(( 1 | 1 << 27 )); else move OFFER_TRADE:$(( 1 | 1 << 24 )); fi
    for size in ${SIZES%% *} ${SIZES##* }; do screen "$size"; check "$mode-${size%%:*}-font$font-offer"; done
    view --es stress discard
    for size in ${SIZES%% *} ${SIZES##* }; do screen "$size"; check "$mode-${size%%:*}-font$font-discard"; done
  done
done

# The cutout pass, at the larger font: each emulated cutout, the camera on either side (user_rotation 1 and 3).
[ -n "$CUTOUTS" ] && "$ADB" shell settings put system font_scale "$CUTOUT_FONT"
[ -n "$CUTOUTS" ] && "$ADB" shell settings put system accelerometer_rotation 0
# The app asks for sensorLandscape, which keeps the screen as it is: only a fixed user rotation turns it over (0.3.4: user_rotation
# alone left the camera on the right in every step).
[ -n "$CUTOUTS" ] && "$ADB" shell wm fixed-to-user-rotation enabled
# A cutout or a turn recreates the activity, and the debug launch extras then start a new game: set the list up again.
[ -n "$CUTOUTS" ] && for mode in $MODES; do
  for cut in $CUTOUTS; do
    if ! "$ADB" shell cmd overlay list | tr -d '\r' | grep -q "$OVL\.$cut\$"; then echo "layout: no $cut cutout on this device, skipped"; continue; fi
    cutout "$OVL.$cut"
    for rot in 1 3; do
      "$ADB" shell settings put system user_rotation $rot
      for _ in 1 2 3 4 5 6 7 8 9 10; do "$ADB" shell dumpsys window displays | grep -q "mRotation=$rot" && break; sleep 1; done
      "$ADB" shell dumpsys window displays | grep -q "mRotation=$rot" || { echo "layout: FAILED: the screen won't turn to rotation $rot"; fail=1; continue; }
      fresh "$mode" || continue
      for size in $CUTOUT_SIZES; do screen "$size"; check "$mode-${size%%:*}-$cut-rot$rot"; done
    done
  done
  cutout "$CUT0"
done

[ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" = 0 ] || { echo "layout: FAILED: the app crashed"; fail=1; }
echo "layout: $steps screens checked in $((SECONDS - t0)) s"
[ $fail = 0 ] && echo "layout: PASSED"
exit $fail
