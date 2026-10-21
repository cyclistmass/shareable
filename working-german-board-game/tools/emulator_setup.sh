#!/usr/bin/env bash
# Puts a test phone (emulator) in a known state before device scripts, and back for a person afterwards:
#   test  Play Store off (its updates and pop-ups ate CPU and covered the app mid-run), animations off, the
#         full-screen and gesture hints already seen, the screen kept on while plugged in. Warns when the emulator has
#         been up for hours: they leak host memory and slow down, so restart it before timing, monkey or user tests.
#   user  Play Store back on and animations back to normal, for a person (Mauro's own account on lane 2) to use.
# Usage (Git Bash): [ANDROID_SERIAL=emulator-5556] tools/emulator_setup.sh [test|user]
set -euo pipefail
MODE="${1:-test}"
A="${ADB:-$(command -v adb || echo "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")}"
adbsh() { "$A" shell "$@" | tr -d '\r'; }
anim() { for k in window_animation_scale transition_animation_scale animator_duration_scale; do adbsh settings put global "$k" "$1"; done; }
case "$MODE" in
  test)
    adbsh pm disable-user --user 0 com.android.vending > /dev/null 2>&1 || echo "no Play Store on this image"
    anim 0
    adbsh settings put secure immersive_mode_confirmations confirmed
    adbsh settings put secure navigation_mode_education_shown 1 2>/dev/null || true
    adbsh svc power stayon usb
    up=$(adbsh cat /proc/uptime | cut -d. -f1)
    echo "$(adbsh getprop ro.product.model) ready for tests; up $((up / 3600)) h $((up % 3600 / 60)) min"
    [ "$up" -lt $((3 * 3600)) ] || echo "WARNING: up over 3 h; restart it (tools/emulator.ps1 -Launch) before timing, monkey or user tests"
    ;;
  user)
    adbsh pm enable com.android.vending > /dev/null 2>&1 || true
    anim 1
    adbsh svc power stayon false
    echo "$(adbsh getprop ro.product.model) back to normal for a person"
    ;;
  *) echo "usage: tools/emulator_setup.sh [test|user]"; exit 2;;
esac
