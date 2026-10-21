#!/usr/bin/env bash
# User-perspective play test (Mauro, 2026-10-10: "you play a game to the end, in each mode, online and local,
# before a push"). Everything a player does is a real tap on the real screens: the start screen (seats,
# ruleset, name), Play or Host/Start game, the glowing board spots, and the on-screen buttons (Roll, End turn,
# discard cards and Discard, pickers, Age of Chivalry buttons). No debug launch flags and no move hooks; the
# game's log is only read to know what is on screen and when the game is over.
# Fails on a crash, an ANR, a turn that never moves on, or a game that doesn't end in time.
# Two people on two phones (Mauro, 2026-10-11): run "host" on one emulator and "join" on another at the same
# time; the host hosts a room with seat 2 as a Friend, the joiner opens Join a room and types the room code,
# both by taps, and each plays its own seat to the end on the real server.
# Each run drives only its own device (ANDROID_SERIAL is required) and keeps its own folder, so two runs at
# once never share files or taps.
# Usage: ANDROID_SERIAL=emulator-5554 tools/userplay.sh <base|chivalry> <local|online|host|join> [players=3] [minutes=40]
#   RESUME=1 finishes the saved game instead (the splash's Resume game); ruleset and players are then the save's.
#   host/join meet through ROOM_FILE (default ${TEMP:-${TMPDIR:-/tmp}}/wgbg-userplay/room-code); start the host first.
set -uo pipefail
RULESET="$1"; MODE="$2"; PLAYERS="${3:-3}"; MINUTES="${4:-40}"
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device to drive (lane 1 emulator-5554, lane 2 emulator-5556)}"
export ANDROID_SERIAL
ADB="${ADB:-adb}"
PKG=com.mauro.wgbg
export MSYS_NO_PATHCONV=1
OUT="${OUT:-${TEMP:-${TMPDIR:-/tmp}}/wgbg-userplay/$ANDROID_SERIAL}"; mkdir -p "$OUT"
ROOM_FILE="${ROOM_FILE:-${TEMP:-${TMPDIR:-/tmp}}/wgbg-userplay/room-code}"
TAG="$RULESET-$MODE-${PLAYERS}p"
# One run per device: a second run on the same device would tap over this one.
if [ -f "$OUT/run.pid" ] && kill -0 "$(cat "$OUT/run.pid")" 2>/dev/null; then
  echo "USERPLAY: another run (pid $(cat "$OUT/run.pid")) is already driving $ANDROID_SERIAL; stop it first"; exit 1
fi
echo $$ > "$OUT/run.pid"; trap 'rm -f "$OUT/run.pid"' EXIT
# Stopping the shell that started a run (a background task stopped) leaves this script tapping on, holding the device:
# quit once that shell is gone.
PARENT=$PPID
orphaned() { kill -0 "$PARENT" 2>/dev/null || { echo "USERPLAY $TAG: stopped (the shell that started it is gone)"; exit 1; }; }

# The screen's text. A uiautomator left over from a stopped run blocks new ones ("already registered"): clear it and retry.
dump() {
  local x
  for _ in 1 2 3 4 5 6; do
    "$ADB" shell rm -f /sdcard/ui.xml; "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    x=$("$ADB" shell cat /sdcard/ui.xml 2>/dev/null); case "$x" in *hierarchy*) echo "$x" | tr '>' '\n'; return;; esac
    "$ADB" shell pkill -f uiautomator 2>/dev/null; sleep 1
  done
  echo "uiautomator dump failed 6 times (busy device?)" >&2
}
# Centre of the first on-screen text exactly $1 (optionally in $2 = a dump already taken).
find_text() {
  local d="${2:-$(dump)}"
  echo "$d" | grep -o "\(text\|content-desc\)=\"$1\"[^/]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1 |
    grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr '\n' ' ' | awk '{split($1,a,",");split($2,b,",");print int((a[1]+b[1])/2), int((a[2]+b[2])/2)}'
}
tap_text() { local xy; xy=$(find_text "$1" "${2:-}"); [ -n "$xy" ] || return 1; "$ADB" shell input tap $xy </dev/null; sleep 0.7; }
# </dev/null: adb shell reads stdin, so inside a `... | while read` loop it ate the rest of the list (Crest, 2026-10-13).
tap() { "$ADB" shell input tap "$1" "$2" </dev/null; }
# Tap an icon by its accessibility label (what a screen reader says), e.g. the Settings gear.
tap_desc() { local xy; xy=$(dump | grep -o "content-desc=\"$1\"[^/]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1 | grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | tr '
' ' ' |
  awk '{split($1,a,",");split($2,b,",");print int((a[1]+b[1])/2), int((a[2]+b[2])/2)}'); [ -n "$xy" ] || return 1; "$ADB" shell input tap $xy </dev/null; sleep 0.8; }
# Tap a button that stays put (Roll, End turn): find it once, then reuse its spot.
declare -A POS
tap_at() { [ -n "${POS[$1]:-}" ] || POS[$1]=$(find_text "$1"); [ -n "${POS[$1]}" ] || { unset "POS[$1]"; return 1; }; "$ADB" shell input tap ${POS[$1]} </dev/null; sleep 0.5; }
shot() { "$ADB" exec-out screencap -p > "$OUT/$TAG-$1.png"; }
crashed() { [ "$("$ADB" logcat -d -b crash | grep -A8 'FATAL EXCEPTION' | grep -c "$PKG")" != 0 ]; }
fail() { echo "USERPLAY $TAG FAILED: $1"; [ "$MODE" = join ] && touch "$ROOM_FILE.failed"; shot fail; "$ADB" logcat -d -b crash | grep -A30 "FATAL EXCEPTION" | head -40; exit 1; }
# Centres of every element with text $1, left to right.
all_text() { echo "$2" | grep -o "\(text\|content-desc\)=\"$1\"[^/]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | grep -o 'bounds=.*' | tr -c '0-9\n' ' ' |
  awk '{print int(($1+$3)/2), int(($2+$4)/2)}' | sort -n -k1,1; }
# Close the on-screen keyboard with its Enter/Done key, as a player would. Not Back: on Android 14 (lane 2) one Back
# closes the keyboard AND the app (0.2.9 test, 2026-10-13).
# Enter first (a player's Done key); if it only moved to the next box, Back closes the keyboard. Back only while
# it's still up, or it would close the screen. Check it's really gone: a box hidden under it gets the wrong text.
kb_up() { "$ADB" shell dumpsys input_method | grep -q "mInputShown=true"; }
hide_keyboard() { sleep 0.5; for k in ENTER BACK; do kb_up || return 0; "$ADB" shell input keyevent KEYCODE_$k; sleep 1; done
  kb_up && fail "the keyboard won't close"; return 0; }
# Type into a text box (tap it, clear it, type).
type_into() { [ -n "$3" ] || fail "no text box to type \"$1\" into"; tap $1 $2; sleep 0.5; "$ADB" shell input keyevent KEYCODE_MOVE_END; for _ in $(seq 1 14); do "$ADB" shell input keyevent KEYCODE_DEL; done
  "$ADB" shell input text "$3"; sleep 0.5; }

# A fresh install state, then in through the start screen like a player.
"$ADB" shell am force-stop $PKG
# RESUME=1 keeps the saved game (an update over the last release, or a relaunch mid-game) and goes in through the
# splash's Resume game instead of a new game.
[ -n "${RESUME:-}" ] || "$ADB" shell pm clear $PKG >/dev/null
"$ADB" logcat -c; "$ADB" logcat -b crash -c 2>/dev/null || true
# Tap the launcher icon. Android may still be tidying up after the clear and close the new window right away, so
# make sure the app is really running (relaunch if not).
sleep 2
for _ in 1 2 3; do
  "$ADB" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
  [ -n "$("$ADB" shell pidof $PKG | tr -d '
')" ] && break
done
[ -n "$("$ADB" shell pidof $PKG | tr -d '
')" ] || fail "the app wouldn't start"
# Wait for the app to open (a slow phone shows the launch splash for a while): the intro or the splash (#132).
for _ in $(seq 1 15); do d=$(dump); echo "$d" | grep -q 'text="Tap to skip"\|text="New game"' && break; sleep 2; done
# First launch plays the intro fly-over; a player taps to skip it.
if echo "$d" | grep -q 'text="Tap to skip"'; then tap 1200 540; sleep 2
  for _ in $(seq 1 10); do d=$(dump); echo "$d" | grep -q 'text="New game"' && break; sleep 1; done; fi
echo "$d" | grep -q 'text="New game"' || fail "the splash never appeared"
shot splash
if [ -n "${RESUME:-}" ]; then
  tap_text "Resume game" "$d" || fail "no Resume game button"
else
# The splash's New game opens the game-start screen (players, ruleset, Play, Host and Join).
tap_text "New game" "$d" || fail "no New game button"
for _ in $(seq 1 10); do d=$(dump); echo "$d" | grep -q 'text="Play"' && break; sleep 1; done
echo "$d" | grep -q 'text="Play"' || fail "the game-start screen never appeared"
if [ "$MODE" = join ]; then
  # The friend: Join a room, their name and the code the host read out, Join, then mark themselves ready.
  for _ in $(seq 1 90); do [ -s "$ROOM_FILE" ] && break; sleep 2; done
  code=$(cat "$ROOM_FILE" 2>/dev/null); [ -n "$code" ] || fail "no room code from the host in $ROOM_FILE"
  # Online needs a name first (the buttons stay off for "You").
  xy=$(find_text "You" "$d") || true; [ -n "$xy" ] && { type_into $xy "Duo"; hide_keyboard; }
  tap_text "Join a room" || fail "no Join a room button"
  sleep 1.5; d=$(dump)
  # Text boxes top to bottom: name, code. The keyboard moves things, so close it and look again before each box.
  boxes() { dump | grep 'class="android.widget.EditText"' | grep -o 'bounds="[^"]*"' | tr -c '0-9
' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}' | sort -n -k2,2; }
  [ "$(boxes | grep -c .)" -ge 2 ] || fail "the join screen has no name and code boxes"
  type_into $(boxes | sed -n 1p) "Duo"; hide_keyboard
  type_into $(boxes | sed -n 2p) "$code"; hide_keyboard
  dump | grep -q "text=\"$code\"" || fail "the code didn't go into the code box"
  tap_text "Join" || fail "no Join button"
  for _ in $(seq 1 30); do sleep 1; tap_text "Not ready" && break; done || fail "joined $code but never saw my Not ready toggle"
  shot lobby
else
  # Seats: you plus bots; empty the last seats down to the player count. Seat columns are left to right
  # (Seat 2, 3, 4), each with its own Friend / Bot / Empty buttons.
  if [ "$PLAYERS" -lt 4 ]; then
    empties=$(all_text Empty "$d")
    for n in $(seq 4 -1 $((PLAYERS + 1))); do xy=$(echo "$empties" | sed -n "$((n - 1))p"); [ -n "$xy" ] && tap $xy && sleep 0.6; done
  fi
  # Hosting for a friend: seat 2 becomes a Friend seat.
  [ "$MODE" = host ] && { xy=$(all_text Friend "$(dump)" | head -1); [ -n "$xy" ] || fail "no Friend seat button"; tap $xy; sleep 0.6; }
  # The Game row can sit below the fold (0.3.4's Colours rows): scroll the Players panel to it, then back to the top.
  if [ "$RULESET" = chivalry ]; then
    read -r sw sh < <("$ADB" shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr x ' '); [ "$sw" -lt "$sh" ] && { t=$sw; sw=$sh; sh=$t; }
    ok=0; for _ in 1 2 3 4 5 6; do tap_text "Age of Chivalry" && { ok=1; break; }; "$ADB" shell input swipe $((sw / 4)) $((sh * 3 / 4)) $((sw / 4)) $((sh / 3)) 400 </dev/null; sleep 0.6; done
    [ $ok = 1 ] || fail "no ruleset button"
    for _ in 1 2 3 4 5 6; do "$ADB" shell input swipe $((sw / 4)) $((sh / 3)) $((sw / 4)) $((sh * 3 / 4)) 300 </dev/null; done
  fi
  if [ "$MODE" = online ] || [ "$MODE" = host ]; then
    xy=$(find_text "You") || true; [ -n "$xy" ] && { type_into $xy "Tester"; hide_keyboard; }
    tap_text "Host a room" || fail "no Host a room button"
    sleep 8
    if [ "$MODE" = host ]; then
      # Read the room code off the lobby and hand it to the friend; Start game once they're in and ready.
      code=$(dump | grep -o 'text="[^"]*"' | grep -o '[A-Z]\{3,\}-[1-9][0-9]\{1,2\}' | head -1)
      [ -n "$code" ] || fail "no room code on the lobby screen"
      mkdir -p "$(dirname "$ROOM_FILE")"; rm -f "$ROOM_FILE.failed"; echo "$code" > "$ROOM_FILE"; echo "USERPLAY $TAG: hosting $code"
      shot lobby
      for _ in $(seq 1 90); do
        orphaned
        "$ADB" logcat -d -s WGBG-TURN:I | grep -q 'phase=' && break
        [ -e "$ROOM_FILE.failed" ] && break
        d=$(dump); echo "$d" | grep -q 'text="Duo"' && tap_text "Start game" "$d"; sleep 3
      done
      rm -f "$ROOM_FILE"
      # A friend who never made it in would leave the host sitting in the lobby until its time limit.
      "$ADB" logcat -d -s WGBG-TURN:I | grep -q 'phase=' || { rm -f "$ROOM_FILE.failed"; fail "the friend never joined (see the join log)"; }
    else
      tap_text "Start game" || fail "no Start game (lobby: $(dump | grep -o 'text="[^"]*ready[^"]*"' | head -2))"
    fi
  else
    tap_text "Play" || fail "no Play button"
  fi
fi
fi
sleep 6
shot start
# The game must actually be on: the board asks for the first placement (the joiner waits for the host's Start).
for _ in $(seq 1 120); do "$ADB" logcat -d -s WGBG-TURN:I | grep -q 'phase=' && break; sleep 1; done
"$ADB" logcat -d -s WGBG-TURN:I | grep -q 'phase=' || fail "the game never started (still on the start screen?)"
# Animations off from the menu, like a player can (Mauro: test games skip the animation; it also makes reading the
# screen fast, since a still screen needs no waiting): Menu, Settings, Scenery, drag Density to 0, Hide (it saves as
# you go). Settings opens with its groups shut (Fable review T4); Density is the first slider in Scenery. (0.3.4 dropped
# the Animations switch and, in your own games, the bots' speed button: AI pace, at its fastest by default, is it, #91.)
if tap_text "Menu"; then
  if tap_desc "Settings" && tap_text "Scenery"; then
    b=$(dump | grep -o 'class="android.widget.SeekBar"[^/]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 | grep -o '[0-9]*' | tr '
' ' ')
    if [ -n "$b" ]; then set -- $b; "$ADB" shell input swipe $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )) $(( $1 - 40 )) $(( ($2 + $4) / 2 )) 400 </dev/null; sleep 0.5
      dump | grep -q 'text="Density 0%"' || echo "USERPLAY $TAG: Density didn't go to 0"
    else echo "USERPLAY $TAG: no Density slider"; fi
  fi
  tap_text "Hide"
fi

start=$(date +%s); moves=0; last=""; prev=""; same=0; tries=0; changed=$(date +%s)
while true; do
  orphaned
  [ $(( $(date +%s) - start )) -gt $((MINUTES * 60)) ] && fail "game not over after $MINUTES minutes ($moves moves)"
  crashed && fail "crash after $moves moves"
  if "$ADB" logcat -d -s WGBG-GAME:I WGBG-ONLINE:I | grep -q -E "over|game over"; then
    echo "USERPLAY $TAG: game over after $moves of my moves, $(( ($(date +%s) - start) / 60 )) min: $("$ADB" logcat -d -s WGBG-GAME:I WGBG-ONLINE:I | grep -E 'over' | tail -1 | grep -o 'over.*\|game over.*')"
    shot end; break
  fi
  line=$("$ADB" logcat -d -s WGBG-TURN:I | tail -1)
  legal=$(echo "$line" | sed -n 's/.* legal=\([^ ]*\).*/\1/p')
  if [ -z "$legal" ] || [ "$line" = "$last" ]; then
    # Not my move (bots playing, dice flying), or my last tap didn't take yet.
    # Stuck = the game log hasn't moved for 5 minutes (anyone's move moves it; a slow friend or bot doesn't count).
    [ "$line" = "$prev" ] || { changed=$(date +%s); prev="$line"; tries=0; }
    same=$((same + 1)); [ $(( $(date +%s) - changed )) -gt 300 ] && fail "stuck: ${line:0:200}"
    # A move of mine that didn't take: try the on-screen buttons again.
    if [ -n "$legal" ] && [ $((same % 6)) = 5 ]; then last=""; fi
    sleep 1; continue
  fi
  if [ "$line" = "$prev" ]; then tries=$((tries + 1)); else same=0; tries=0; changed=$(date +%s); fi; last="$line"; prev="$line"
  spots=$(echo "$line" | sed -n 's/.* spots=\([^ ]*\).*/\1/p' | tr ';' '\n')
  phase=$(echo "$line" | sed -n 's/.* phase=\([^ ]*\).*/\1/p')
  [ "$phase" = DISCARD ] && echo "$(date +%T) my discard prompt, try $tries" >&2
  # A taken tap that didn't land twice: forget the remembered button spots and read the screen again.
  [ "$tries" -ge 2 ] && POS=()
  # Reading the screen takes ~5 s (the sea never stops moving), so only read it when the log can't say what to tap.
  # BUILDMENU=1: build through the build menu like a player who uses it: tap the piece's button (Town, Homestead,
  # Road), then its glowing spot, then confirm; the move must then leave the legal list (it was really built).
  menu_pick=""
  if [ "${BUILDMENU:-0}" = 1 ] && [ -n "$spots" ]; then
    for t in BUILD_CITY BUILD_SETTLEMENT BUILD_ROAD; do menu_pick=$(echo "$spots" | grep "^$t:" | shuf -n 1); [ -n "$menu_pick" ] && break; done
  fi
  if [ -n "$menu_pick" ]; then
    mv_=${menu_pick%@*}; label=$(case ${mv_%%:*} in BUILD_CITY) echo Town;; BUILD_SETTLEMENT) echo Homestead;; *) echo Road;; esac)
    if tap_desc "$label"; then
      sleep 0.6; xy=${menu_pick#*@}; tap "${xy%,*}" "${xy#*,}"; sleep 0.6; tap "${xy%,*}" "${xy#*,}"
      for _ in $(seq 1 10); do sleep 0.5; after=$("$ADB" logcat -d -s WGBG-TURN:I | tail -1); [ "$after" != "$line" ] && break; done
      if echo "$after" | grep -q "legal=[^ ]*\b$mv_\b"; then echo "MENUBUILD FAIL $label $mv_: still legal after menu, spot, confirm"; MENU_FAIL=$((${MENU_FAIL:-0} + 1)); shot "menufail-$label"
      else echo "MENUBUILD ok $label $mv_"; MENU_OK=$((${MENU_OK:-0} + 1)); fi
    else echo "MENUBUILD FAIL $label: no $label button in the build menu while $mv_ is legal"; MENU_FAIL=$((${MENU_FAIL:-0} + 1)); shot "menufail-nobutton-$label"; fi
  elif [ -n "$spots" ] && [ $((RANDOM % 5)) != 0 ]; then
    pick=$(echo "$spots" | shuf -n 1); xy=${pick#*@}
    tap "${xy%,*}" "${xy#*,}"; sleep 0.6; tap "${xy%,*}" "${xy#*,}"
  elif echo ",$legal" | grep -q ',ROLL:' && tap_at "Roll"; then :
  elif echo ",$legal" | grep -q ',BUY_DEVELOPMENT_CARD:' && [ $((RANDOM % 4)) = 0 ] && tap_desc "Fortune card"; then :   # the build menu's card icon
  elif echo ",$legal" | grep -q ',ACCEPT_TRADE:\|,REJECT_TRADE:'; then
    # A trade offered to you: answer it like a person, Yes or No (Counter is left out on purpose).
    if echo ",$legal" | grep -q ',ACCEPT_TRADE:' && [ $((RANDOM % 3)) = 0 ]; then tap_text "Yes" || tap_text "No"; else tap_text "No" || tap_text "Yes"; fi
  elif [ "$phase" != DISCARD ] && echo ",$legal" | grep -q ',END_TURN:' && { [ "$tries" -ge 3 ] || [ $((RANDOM % 3)) = 0 ]; } && tap_at "End turn"; then :
  elif d=$(dump); echo "$d" | grep -q 'text="Discard [0-9]'; then
    # The discard sheet, the way a person does it: look once, tap cards you hold until enough are chosen, press
    # Discard. ("choose 5 to discard (2 chosen)"; each card shows "have N" under it.)
    echo "$(date +%T) discard sheet seen (${phase})" >&2
    read -r owed chosen < <(echo "$d" | grep -o '[Cc]hoose [0-9]* to discard ([0-9]* chosen)' | head -1 | sed 's/[Cc]hoose \([0-9]*\) to discard (\([0-9]*\) chosen)/\1 \2/')
    need=$(( ${owed:-0} - ${chosen:-0} ))
    # Two quick taps on one card count as one, so tap at a person's pace and look again until enough are chosen.
    for _round in 1 2 3; do
    [ "$need" -gt 0 ] || break
      # Every held card once per copy (centre x, y), shuffled; tap the first "need" of them.
      echo "$d" | grep -o '\(content-desc\|text\)="\(Timber\|Clay\|Fleece\|Grain\|Stone\|Parchment\|Fabric\|Silver\)"[^/]*bounds="[^"]*"\|text="have [0-9]*"[^/]*bounds="[^"]*"' |
        sed 's/^text="have \([0-9]*\)"[^/]*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),[0-9]*\]"/H \1 \2 \4 \3/; s/^[a-z-]*="[A-Za-z]*"[^/]*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/C \1 \2 \3 \4/' |
        # A "have N" counts only for the card right above it (a hidden hand row under the sheet must not match).
        awk '$1=="C"{n++; x1[n]=$2; y1[n]=$3; x2[n]=$4; y2[n]=$5} $1=="H"{h[++m]=$2; hx[m]=($3+$4)/2; hy[m]=$5}
             END{for(i=1;i<=n;i++){cx=(x1[i]+x2[i])/2; for(k=1;k<=m;k++) if(hx[k]>=x1[i] && hx[k]<=x2[i] && hy[k]>=y2[i]-10 && hy[k]<=y2[i]+60) for(c=0;c<h[k];c++) print int(cx), int((y1[i]+y2[i])/2)}}' |
        shuf | head -n "$need" | while read -r x y; do tap "$x" "$y"; sleep 0.6; done
      d=$(dump)
      read -r owed chosen < <(echo "$d" | grep -o '[Cc]hoose [0-9]* to discard ([0-9]* chosen)' | head -1 | sed 's/[Cc]hoose \([0-9]*\) to discard (\([0-9]*\) chosen)/\1 \2/')
      need=$(( ${owed:-0} - ${chosen:-0} ))
    done
    tap_text "$(echo "$d" | grep -o 'text="Discard [0-9]*"' | head -1 | cut -d'"' -f2)" "$d" || true
  elif tap_text "Roll" "$d"; then :
  elif echo "$d" | grep -q 'text="Card guide"' && echo "$d" | grep -q 'text="Close"'; then
    tap_text "Close" "$d" || true   # the card guide got opened: close it, as a player would
  elif echo "$d" | grep -q 'text="Hide"'; then
    # The menu is open (a stray tap on the gear): carry on playing if it got paused, then close it, as a player would.
    echo "$(date +%T) menu open, closing it" >&2
    echo "$d" | grep -q 'text="Play"' && tap_text "Play" "$d"
    tap_text "Hide" "$d" || true
  elif echo ",$legal" | grep -q ',CHOOSE_CARD:' && echo "$d" | grep -q 'text="ha[sv]e [0-9]'; then
    # A one-card picker (Harbour Charter's give-back, Grand Factor's take): the cards are pictures with "have N"
    # (yours) or "has N" (theirs) under them
    # and no text button, so tap one card you hold, as a person would.
    echo "$(date +%T) card picker seen (${phase})" >&2
    xy=$(echo "$d" | grep -o '\(content-desc\|text\)="\(Timber\|Clay\|Fleece\|Grain\|Stone\|Parchment\|Fabric\|Silver\)"[^/]*bounds="[^"]*"\|text="ha[sv]e [0-9]*"[^/]*bounds="[^"]*"' |
      sed 's/^text="ha[sv]e \([0-9]*\)"[^/]*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),[0-9]*\]"/H \1 \2 \4 \3/; s/^[a-z-]*="[A-Za-z]*"[^/]*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/C \1 \2 \3 \4/' |
      awk '$1=="C"{n++; x1[n]=$2; y1[n]=$3; x2[n]=$4; y2[n]=$5} $1=="H"{h[++m]=$2; hx[m]=($3+$4)/2; hy[m]=$5}
           END{for(i=1;i<=n;i++) for(k=1;k<=m;k++) if(h[k]>0 && hx[k]>=x1[i] && hx[k]<=x2[i] && hy[k]>=y2[i]-10 && hy[k]<=y2[i]+60) print int((x1[i]+x2[i])/2), int((y1[i]+y2[i])/2)}' |
      shuf -n 1)
    if [ -n "$xy" ]; then tap "${xy% *}" "${xy#* }"; else echo "$(date +%T) card picker: no held card found" >&2; fi
  elif echo "$d" | grep -q 'text="Back"' && echo "$d" | grep -q 'text="Play [^"]*: '; then
    # A card's player picker ("Play Grand Factor: ... Blue / White / Back"): pick one of the named players. The
    # general list below leaves player names out because the scores panel shows them too.
    who=$(echo "$d" | grep -o 'text="\(Red\|Blue\|Orange\|White\|Yellow\|Purple\|Black\)"[^/]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' |
      sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2 \3 \4/' | awk '$1 > 600 {print int(($1+$3)/2), int(($2+$4)/2)}' | shuf -n 1)
    if [ -n "$who" ]; then echo "$(date +%T) player picker" >&2; tap "${who% *}" "${who#* }"; else tap_text "Back" "$d" || true; fi
  else
    # Any other on-screen choice (pickers, victims, Age of Chivalry buttons), else End turn.
    opts=$(echo "$d" | grep -o 'text="[^"]\+"' | cut -d'"' -f2 | grep -v -x -E 'Menu|Trade|Hide|Exit|Leave|Settings|End turn|Roll|Counter|Offer|You|Blue|Orange|White|Red|Yellow|Purple|Black|Tester|Duo|\(.*\)|Your turn|[0-9/ ]+|.*turn.*|.*Tap .*|.*built.*|.*rolled.*|.*\+[0-9].*|Raiders|Zoom|Tilt|Players|Maritime|Harbour|have [0-9]+|.*offers.*|.*chosen.*|Pause|Play|Slow|Normal|Fast|Max|Watch bots|Fit|Dice.*|v[0-9.]+|Sound|Music.*|Sounds.*|Inventory|Look.*|Card guide|Close' | shuf)
    # After a few taps that changed nothing, a player ends the turn.
    if [ -n "$opts" ] && [ "$tries" -lt 3 ] && [ $((RANDOM % 3)) != 0 ]; then tap_text "$(echo "$opts" | shuf -n 1)" "$d" || true
    else tap_text "End turn" "$d" || { [ -n "$opts" ] && tap_text "$(echo "$opts" | shuf -n 1)" "$d"; } || true; fi
  fi
  moves=$((moves + 1))
  sleep 0.8
done
anr=$("$ADB" logcat -d | grep -c "ANR in $PKG" || true)
[ "$anr" = 0 ] || fail "ANR"
if [ "${BUILDMENU:-0}" = 1 ]; then
  echo "USERPLAY $TAG build menu: ${MENU_OK:-0} built, ${MENU_FAIL:-0} failed"
  [ "${MENU_FAIL:-0}" = 0 ] && [ "${MENU_OK:-0}" -gt 0 ] || fail "build menu: ${MENU_OK:-0} built, ${MENU_FAIL:-0} failed"
fi
echo "USERPLAY $TAG PASSED"
