#!/usr/bin/env bash
# Records the showcase media: two 1x bot-play clips (Default theme base game; Pirate theme Age of Chivalry
# late game), tilted and zoomed so the pieces read, with the emulator-only Zoom/Tilt bars hidden.
# Writes docs/gameplay-1x.gif and docs/gameplay-chivalry.gif, and the combined MP4 to $OUT (default: temp).
# Run after tools/emulator.ps1 and installing the latest debug build.
# Usage (Git Bash, from the project root): tools/record_showcase.sh [seconds per clip]
set -euo pipefail
SECS="${1:-30}"
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
PKG=com.mauro.wgbg
export MSYS_NO_PATHCONV=1
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
OUT="${OUT:-$TEMP/wgbg-showcase}"
mkdir -p "$OUT" docs

# Fresh app with these saved settings (debug builds let run-as write the prefs file).
settings() {
  "$A" shell am force-stop $PKG
  "$A" shell pm clear $PKG >/dev/null
  local f="$OUT/prefs.xml"
  { echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"; echo "<map>"
    for kv in "$@"; do echo "    <float name=\"${kv%=*}\" value=\"${kv#*=}\" />"; done
    echo "</map>"; } > "$f"
  "$A" push "$(cygpath -w "$f")" /data/local/tmp/wgbg-prefs.xml >/dev/null
  "$A" shell chmod 644 /data/local/tmp/wgbg-prefs.xml
  "$A" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/wgbg-prefs.xml shared_prefs/wgbg-settings.xml'"
}

# Taps the on-screen element whose text is $1 (found with uiautomator).
tap_text() {
  local B="" try
  # Retry for up to 10 s: a cold start after pm clear can take longer than the sleep before the tap.
  for try in 1 2 3 4 5 6 7 8 9 10; do
    "$A" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    B=$("$A" shell cat /sdcard/ui.xml | tr '>' '\n' | grep -o "\(text\|content-desc\)=\"$1\"[^/]*bounds=\"\[[0-9]*,[0-9]*\]" | grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | head -1 || true)
    [ -n "$B" ] && break; sleep 1
  done
  [ -n "$B" ] || { echo "'$1' not found on screen"; exit 1; }
  "$A" shell input tap $(( ${B%,*} + 20 )) $(( ${B#*,} + 20 ))
  sleep 0.8
}

# Like tap_text, but fine if it's gone: at Max speed a game can end and the next one starts with the menu closed.
tap_text_opt() { ( tap_text "$1" ) >/dev/null || true; }


# Watch bots in ruleset $1 at the given tilt/zoom; then set the speed by tapping the speed button $2 times
# (Max -> 1x -> 2x -> 4x -> 8x). With $5=keep, Settings stays open on This game (the bots play on behind it).
watch() {
  "$A" shell am start --activity-clear-task -n $PKG/.MainActivity --es start "$1" --ez watch true --ef tilt "$3" --ef zoom "$4" >/dev/null
  sleep 3
  tap_text "Menu"; tap_text "Settings"; tap_text "This game"   # the speed button is under the gear, in This game
  for _ in $(seq 1 "$2"); do "$A" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    local label; label=$("$A" shell cat /sdcard/ui.xml | grep -o 'text="\(Max\|[0-9]*×\)"' | head -1 | cut -d'"' -f2 || true)
    [ -n "$label" ] && tap_text_opt "$label"; done
  [ "${5:-}" = keep ] || tap_text_opt "Hide"
}

clip() { # record $SECS s to $OUT/$1.mp4
  "$A" shell screenrecord --size 1200x540 --bit-rate 8000000 --time-limit "$SECS" /sdcard/clip.mp4
  "$A" pull /sdcard/clip.mp4 "$(cygpath -w "$OUT/$1.mp4")" >/dev/null
}

gif() { # first 20 s of $1.mp4 -> docs/$2 (about 3 MB, light enough for the README)
  ffmpeg -loglevel error -y -t 20 -i "$(cygpath -w "$OUT/$1.mp4")" \
    -vf "fps=6,scale=640:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=96:stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
    "docs/$2"
}

# 1. Default theme, base game, 1x.
settings "view-bars=0.0"
watch base 1 0.55 1.5
sleep 4
clip base

# 2. Pirate theme, Age of Chivalry: 8x for 90 s so walls, Capitals and knights are out, then 1x.
# Below Max a finished game stays on screen, so a game that ends before the clip leaves a still board
# (0.3.3 re-record): if one ends in the warm-up, start again and warm up a little less.
# Settings stays open on This game through the warm-up, so pausing is one tap: opening the menus at 8x
# took long enough for a nearly finished game to end.
warm=90
for try in 1 2 3 4; do
  settings "view-bars=0.0" "theme-pirate=1.0" "event-die-style=3.0"
  watch chivalry 4 0.55 1.5 keep
  "$A" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  pause=$("$A" shell cat /sdcard/ui.xml | tr '>' '\n' | grep -o 'text="Pause"[^/]*bounds="\[[0-9]*,[0-9]*\]' | grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | head -1 || true)
  [ -n "$pause" ] || { echo "no Pause button under This game"; exit 1; }
  # Only a game that ends at 8x counts: watch starts at Max, where whole games finish while the speed is tapped down.
  "$A" logcat -c
  over=""
  for _ in $(seq 1 $((warm / 5))); do sleep 5; "$A" logcat -d -s WGBG-GAME:I | grep -q " over " && { over=1; break; }; done
  [ -z "$over" ] && { "$A" shell input tap $(( ${pause%,*} + 20 )) $(( ${pause#*,} + 20 )); sleep 0.8
    "$A" logcat -d -s WGBG-GAME:I | grep -q " over " || break; over=1; }
  echo "the warm-up game ended (try $try); starting again"; warm=$((warm * 2 / 3))
done
[ -z "$over" ] || { echo "every warm-up game ended before the clip"; exit 1; }
# Paused now. 8x to 1x goes through Max, where a whole game plays out in a second (a finished board again), so step
# the speed down while paused, then the same button (now Play; the Settings group of that name is further down)
# starts it again.
for _ in 1 2 3 4; do "$A" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  label=$("$A" shell cat /sdcard/ui.xml | grep -o 'text="\(Max\|[0-9]*×\)"' | head -1 | cut -d'"' -f2 || true)
  [ "$label" = "1×" ] && break; [ -n "$label" ] && tap_text_opt "$label"; done
"$A" shell input tap $(( ${pause%,*} + 20 )) $(( ${pause#*,} + 20 )); sleep 0.8
tap_text_opt "Hide"
"$A" logcat -d -s WGBG-GAME:I | grep -q " over " && { echo "the game ended before the clip"; exit 1; }
sleep 2
clip chivalry

gif base gameplay-1x.gif
gif chivalry gameplay-chivalry.gif
ffmpeg -loglevel error -y -i "$(cygpath -w "$OUT/base.mp4")" -i "$(cygpath -w "$OUT/chivalry.mp4")" \
  -filter_complex "[0:v][1:v]concat=n=2:v=1:a=0[v]" -map "[v]" -c:v libx264 -crf 22 -pix_fmt yuv420p -movflags +faststart \
  "$(cygpath -w "$OUT/working-german-board-game-gameplay.mp4")"
ls -la docs/gameplay-1x.gif docs/gameplay-chivalry.gif "$OUT/working-german-board-game-gameplay.mp4"
