#!/usr/bin/env bash
# The APK checks before anything is uploaded or published (#147): every APK given carries the stable key (phones refuse
# an update signed with another), and the first one, the release players install, is not debuggable (a debuggable APK
# has the test hooks on: the MOVE broadcast, WGBG-TURN lines, run-as).
# Usage: tools/ci/apk_check.sh <release apk> [more apks that only need the key]
set -euo pipefail
KEY=90235a5b47da6ee29b077ea7c4ac94d2907c2126267adb5a999afcc4b5fb0093
BT=$(ls -d "${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}"/build-tools/*/ | tail -1)
tool() { if [ -e "$BT$1" ]; then echo "$BT$1"; else echo "$BT$1${2:-.exe}"; fi; }
APKSIGNER=$(tool apksigner .bat); AAPT2=$(tool aapt2)
ok=1
for apk in "$@"; do
  got=$("$APKSIGNER" verify --print-certs "$apk" | tr -d '\r' | sed -n 's/.*SHA-256 digest: //p' | head -1)
  echo "$apk: certificate SHA-256 $got"
  [ "$got" = "$KEY" ] || { echo "  wrong signing key: phones could not update to it"; ok=0; }
done
badging=$("$AAPT2" dump badging "$1" | tr -d '\r')
grep -c '^application-debuggable' <<< "$badging" > /dev/null &&
  { echo "$1: debuggable, so the test hooks are on in it; never publish it"; ok=0; } || echo "$1: not debuggable"
[ $ok = 1 ]
