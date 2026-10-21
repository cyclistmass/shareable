#!/usr/bin/env bash
# What's on screen, for the touch scripts (Fable study #4). Source it, then:
#   dump            the screen, one element a line with uiautomator's attributes (text, resource-id, class, content-desc,
#                   clickable, bounds), so the scripts' greps read it as before. A debug build answers a broadcast in
#                   about 0.2 s (UiLine.kt); with no answer (a release build, the app not up) it falls back to
#                   `uiautomator dump` (2-4.5 s, about 15 s over the moving board). UI_FAST=0 always uses uiautomator.
#   ui_dump         the same for one device of several: UI_SERIAL=emulator-5556 ui_dump (dump is ui_dump on ANDROID_SERIAL)
#   at TEXT         "x y", the centre of the element whose text or content-desc is TEXT, a clickable one first
#   tap_text TEXT   tap it, then wait UI_TAP_WAIT s (default 0.8); false if it isn't on screen
#   tap_id TAG      the same by test tag. Tags first, text only where there's no tag: a text can be on screen twice
#                   (bcff7952: the player strip is also labelled "Players", and a tap by text hit it).
#   ui_line         the latest WGBG-UI logcat line: the screen, its sheets and every tappable thing, as they change
# Needs ADB (adb binary) and ANDROID_SERIAL like the scripts that source it.
UI_PKG=${UI_PKG:-com.mauro.wgbg}
UI_FAST=${UI_FAST:-1}
ui_adb() { "${ADB:-adb}" ${UI_SERIAL:+-s "$UI_SERIAL"} "$@" </dev/null; }   # adb shell eats a read loop's stdin otherwise

dump() { ui_dump; }
ui_dump() {
  local x
  if [ "$UI_FAST" = 1 ]; then
    # The old answer goes first, so a broadcast nobody got can't hand back a stale screen.
    x=$(ui_adb shell "run-as $UI_PKG rm -f files/ui.txt 2>/dev/null; am broadcast -a $UI_PKG.UI -p $UI_PKG >/dev/null 2>&1; run-as $UI_PKG cat files/ui.txt 2>/dev/null")
    # return 0: under pipefail, a `dump | grep -q` that quits at its first match would otherwise fail the pipe (SIGPIPE,
    # 141) once the screen passes about 16 KB, and the check reads as "not on screen". Pipes of a big "$var" the same:
    # `grep -q X <<< "$var"`, not `echo "$var" | grep -q X`.
    case "$x" in *"</hierarchy>"*) printf '%s\n' "$x" | tr -d '\r'; return 0;; esac
    echo "uiread${UI_SERIAL:+ [$UI_SERIAL]}: no answer from the app, reading with uiautomator" >&2
  fi
  for _ in 1 2 3 4 5 6; do ui_adb shell rm -f /sdcard/ui.xml; ui_adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    x=$(ui_adb shell cat /sdcard/ui.xml 2>/dev/null); case "$x" in *hierarchy*) echo "$x" | tr '>' '\n'; return 0;; esac
    ui_adb shell pkill -f uiautomator 2>/dev/null; sleep 1   # one left over from a stopped run blocks new ones ("already registered")
  done
  echo "uiautomator dump failed 6 times (busy device?)" >&2; return 1
}
ui_centre() { grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 | tr -c '0-9\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'; }
at() { local m; m=$(dump | grep "\(text\|content-desc\)=\"$1\""); { echo "$m" | grep 'clickable="true"'; echo "$m"; } | ui_centre; }
ui_say() { declare -F say >/dev/null && say "$@"; return 0; }
tap_text() { local xy; xy=$(at "$1"); [ -n "$xy" ] || return 1; ui_say "tap '$1' at $xy"; ui_adb shell input tap $xy; sleep "${UI_TAP_WAIT:-0.8}"; }
tap_id() { local xy; xy=$(dump | grep "resource-id=\"$1\"" | ui_centre); [ -n "$xy" ] || return 1; ui_say "tap #$1 at $xy"; ui_adb shell input tap $xy; sleep "${UI_TAP_WAIT:-0.8}"; }
ui_line() { ui_adb logcat -d -s WGBG-UI:I | tail -1 | sed 's/^.*WGBG-UI[^:]*: //'; }
