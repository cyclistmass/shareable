#!/usr/bin/env bash
# Play one local game to its end screen, a bot in every seat (yours too, `--ez autoplay true`) at test speed, and
# print the WGBG-GAME line. A base game takes about 2-3 min on a desktop emulator. Automated checks only: never
# counts as user testing. Watchdog: no new turn line for STALL s (default 30) prints why (device? app alive? last
# line) with a screenshot and exits 2, so nobody waits on a stuck game.
# Usage: tools/play_to_end.sh [base|chivalry] [players] [secs]   (ANDROID_SERIAL=device, default emulator-5554; ADB=adb binary)
export MSYS_NO_PATHCONV=1; A="${ADB:-adb} -s ${ANDROID_SERIAL:-emulator-5554}"; end=$((SECONDS+${3:-600})); stall=${STALL:-30}
$A logcat -c
$A shell am start --activity-clear-task -n com.mauro.wgbg/.MainActivity --es start "${1:-base}" --ei players "${2:-3}" \
  --ez autoplay true --ez quickbots true >/dev/null
exec 3< <(exec $A logcat -v brief -s WGBG-TURN:I WGBG-GAME:I 2>/dev/null); LOGCAT=$!
trap 'kill $LOGCAT 2>/dev/null' EXIT # never leave the log stream running (it held the caller's pipe open)
last=""; moved=$SECONDS; start=$SECONDS
while [ $SECONDS -lt $end ]; do
  if IFS= read -r -t 5 -u 3 line; then
    case "$line" in *WGBG-GAME*over*) echo "${line#*: } in $((SECONDS-start)) s"; exit 0 ;; esac
    [[ $line == *WGBG-TURN* && $line != "$last" ]] && { last=$line; moved=$SECONDS; }
  fi
  if [ $((SECONDS-moved)) -ge $stall ]; then
    shot="${OUT:-.}/play_to_end_stuck.png" # the working dir, like turnstream.sh: CI uploads *.png from there
    echo "STUCK: no new turn for ${stall}s. device=$($A get-state 2>&1) app_pid=$($A shell pidof com.mauro.wgbg)"
    echo "last line: ${last:0:300}"
    $A shell screencap -p /sdcard/stuck.png && $A pull /sdcard/stuck.png "$shot" >/dev/null && echo "screenshot: $shot"
    exit 2
  fi
done; echo "TIMEOUT after $((SECONDS-start)) s; last line: ${last:0:300}"; exit 1
