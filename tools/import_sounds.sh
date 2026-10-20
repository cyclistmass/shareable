#!/usr/bin/env bash
# Cuts the game's CC0 alert sounds into app/src/main/res/raw from their downloaded sources (CREDITS.md, "Sound"):
#   event_clunk.ogg   the Raiders' ship lands on the event die: a heavy wooden knock and a light bounce (Kenney Impact Sounds)
#   turn_bell.ogg     it's your turn: one heavy bell strike (Kenney Impact Sounds)
#   raiders_horn.ogg  the Raiders are close: the first blast of a hunting horn (Wikimedia Commons, CC0)
# Sources (download them into one folder, unzipped):
#   https://kenney.nl/assets/impact-sounds  -> kenney_impact-sounds/Audio/...
#   https://commons.wikimedia.org/wiki/File:Hunting_horn_tone.ogg  -> hunting_horn_tone.ogg
# Usage: tools/import_sounds.sh <source folder>   (needs ffmpeg)
set -euo pipefail
SRC="${1:?usage: tools/import_sounds.sh <source folder>}"
RAW="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/raw"
KEN="$SRC/kenney_impact-sounds/Audio"
# Mono, 44.1 kHz, each peaking at -3 dB (too short for loudnorm, which left them clipping at 0 dB). The shaped sound
# (filter graph $F, inputs as arguments) is rendered to a float WAV, its peak read there, then encoded with the gain that
# puts that peak at -3 dB. (Measured inside the shaping graph, the gain came out wrong.)
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
out() { local peak
  ffmpeg -loglevel error -y "$@" -filter_complex "$F,aformat=sample_fmts=flt:channel_layouts=mono" -ar 44100 -c:a pcm_f32le "$TMP/s.wav"
  peak=$(ffmpeg -hide_banner -i "$TMP/s.wav" -af astats=measure_overall=Peak_level:measure_perchannel=none -f null - 2>&1 | sed -n 's/.*Peak level dB: \(-*[0-9.]*\).*/\1/p')
  ffmpeg -loglevel error -y -i "$TMP/s.wav" -af "volume=$(awk -v p="$peak" 'BEGIN { print -3 - p }')dB" -c:a libvorbis -q:a 5 "$RAW/$NAME.ogg"
  echo "$NAME.ogg  $(stat -c %s "$RAW/$NAME.ogg") bytes (peak was $peak dB)"; }
NAME=event_clunk F="[1]adelay=95,volume=0.35[b];[0][b]amix=inputs=2:duration=longest:normalize=0" \
  out -i "$KEN/impactWood_heavy_002.ogg" -i "$KEN/impactWood_light_001.ogg"
NAME=turn_bell F="anull" out -i "$KEN/impactBell_heavy_000.ogg"
NAME=raiders_horn F="afade=t=in:d=0.04,afade=t=out:st=1.05:d=0.4" out -ss 0.45 -t 1.45 -i "$SRC/hunting_horn_tone.ogg"
