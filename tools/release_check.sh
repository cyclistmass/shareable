#!/usr/bin/env bash
# Every automated check before a release, in order, each one timed, ending in one report. It does NOT replace the
# user test (AGENTS.md: the exact APK installed fresh and over the last release, real taps from the start screen, full
# games, close and reopen, online): the report ends with that checklist and the APK's sha256 to test.
#
#   unit     engine, AI, net, CLI and app unit tests (rules, fuzz, break-it, the app-writes contract)
#   rules    Firestore rules tests on a private emulator (FS_PORT), including the replay of the app's real writes
#   apk      the debug APK, signed with the stable key (WGBG_DEBUG_KEYSTORE), and its certificate checked
#   install  that APK on both lanes (local device steps, install to layout: only when asked for in STEPS; remote runs
#            all of them on GitHub's runners instead and frees both lanes, M5 2026-10-21)
#   lanes    device scripts, lane 1 and lane 2 side by side: trade and touch play on one, withdraw, monkey, names and
#            colours on the other
#   friends  friends_sim on both lanes against a private pair of Firebase emulators (FS_PORT, AUTH_PORT)
#   layout   ci_layout on lane 1 (slow: ~25 min; CI runs it too)
#   remote   instead of install/lanes/friends/layout: all of them, plus the update from the last release, on GitHub's
#            runners (wgbg-check.yml on a test/<branch> Bosun pushes); waits for the run and reports each shard.
#            The default, STEPS="unit rules apk remote", leaves only the user test (on the APK built here) to a local
#            emulator. STEPS="unit rules apk install lanes friends" is the old all-local run, for when GitHub is down.
#
# Usage (Git Bash, project root): [STEPS="unit rules apk remote"] [RULESETS="base chivalry"]
#   [HOST_SERIAL=emulator-5554] [GUEST_SERIAL=emulator-5556] [FS_PORT=8187 AUTH_PORT=9197] tools/release_check.sh
# A step that fails is reported and the next one still runs, except that nothing runs on a device without an APK.
# Logs: $OUT (default $TEMP/wgbg-release-check/<time>/), one file per check.
set -uo pipefail
STEPS="${STEPS:-unit rules apk remote}"
RULESETS="${RULESETS:-base chivalry}"
HOST="${HOST_SERIAL:-emulator-5554}"; GUEST="${GUEST_SERIAL:-emulator-5556}"
FS_PORT="${FS_PORT:-8187}"; AUTH_PORT="${AUTH_PORT:-9197}"
OUT="${OUT:-${TEMP:-${TMPDIR:-/tmp}}/wgbg-release-check/$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
APK=app/build/outputs/apk/debug/app-debug.apk
CERT=90235a5b47da6ee29b077ea7c4ac94d2907c2126267adb5a999afcc4b5fb0093   # the stable key CI and every release use
export MSYS_NO_PATHCONV=1
REPORT=()

# check <name> <command...>: runs one check with its output in $OUT/<name>.log; records PASS/FAIL and the time.
check() {
  local name="$1"; shift; local t0=$SECONDS
  echo "== $name: $*"
  if "$@" > "$OUT/$name.log" 2>&1; then REPORT+=("PASS  $(printf '%5d' $((SECONDS - t0))) s  $name")
  else REPORT+=("FAIL  $(printf '%5d' $((SECONDS - t0))) s  $name   (log: $OUT/$name.log)"); tail -15 "$OUT/$name.log"; return 1; fi
}
on() { case " $STEPS " in *" $1 "*) return 0;; esac; return 1; }
# A path a Windows program (firebase, adb.exe) can open: Git Bash's /c/... and /tmp/... mean nothing to it.
natpath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else printf '%s
' "$1"; fi; }
ADB="${ADB:-$(command -v adb || echo "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")}"
fbconfig() {   # a firebase.json for a private pair of emulators, so a peer's 8080 or 8186 is never touched
  printf '{"firestore":{"rules":"%s"},"emulators":{"firestore":{"port":%s,"host":"0.0.0.0"},"auth":{"port":%s,"host":"0.0.0.0"},"ui":{"enabled":false},"singleProjectMode":true}}\n' \
    "$(natpath "$(pwd)/firestore.rules")" "$FS_PORT" "$AUTH_PORT" > "$OUT/firebase.json"
}

# The instrumented tests are compiled too: assembleDebug never touches them, and 0.3.4 reached CI with one that no
# longer compiled.
on unit && check unit bash ./gradlew :engine:test :ai:test :net:test :cli:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin

if on rules; then
  fbconfig
  check rules firebase emulators:exec --config "$(natpath "$OUT/firebase.json")" --only firestore --project demo-wgbg \
    "npm --prefix firebase/rules-test test"
fi

if on apk; then
  [ -n "${WGBG_DEBUG_KEYSTORE:-}" ] || echo "WGBG_DEBUG_KEYSTORE is not set: the APK gets the local debug key and the cert check fails"
  check apk bash ./gradlew :app:assembleDebug && {
    SIGNER="$(ls -d "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"/build-tools/*/ | tail -1)apksigner"; [ -f "$SIGNER" ] || SIGNER="$SIGNER.bat"
    got=$("$SIGNER" verify --print-certs "$APK" | sed -n 's/.*SHA-256 digest: //p' | head -1)
    [ "$got" = "$CERT" ] && REPORT+=("PASS      0 s  apk certificate") || REPORT+=("FAIL      0 s  apk certificate: $got, not the stable key")
  }
fi

# remote: every device step on GitHub's runners (shareable's wgbg-check.yml, one emulator per shard). This script
# can't push: it names the branch for Bosun to push, then waits for that branch's run and adds its jobs to the report.
if on remote; then
  BRANCH="${REMOTE_BRANCH:-test/$(git branch --show-current | tr '/' '-')}"
  SUBJECT=$(git log -1 --format=%s); WAIT="${REMOTE_WAIT:-150}"   # minutes
  REPO=cyclistmass/shareable
  [ -z "$(git status --porcelain --untracked-files=no)" ] || echo "!! uncommitted changes: the remote run tests the commit, not them"
  echo "== remote: ask Bosun to push $BRANCH at $(git rev-parse --short HEAD) (\"$SUBJECT\"); waiting up to $WAIT min"
  # GitHub's API: Mauro's token helper when there is one (no rate limit worries), else gh, else anonymous (public repo).
  GH_API="${GH_API:-$HOME/.claude/tools/gh_api.sh}"
  api() { if [ -x "$GH_API" ]; then "$GH_API" GET "/$1"; elif command -v gh >/dev/null; then gh api "$1"; else curl -s "https://api.github.com/$1"; fi; }
  PY=$(for p in python3 python; do "$p" -c 1 >/dev/null 2>&1 && { echo "$p"; break; }; done)
  t0=$SECONDS; run=""; state=""
  while [ $((SECONDS - t0)) -lt $((WAIT * 60)) ]; do
    # The newest run of wgbg-check.yml on that branch whose commit carries this commit's subject (the subtree publish
    # gives the shareable commit its own SHA, but keeps the message).
    read -r run state < <(api "repos/$REPO/actions/workflows/${REMOTE_WORKFLOW:-wgbg-check.yml}/runs?branch=$BRANCH&per_page=5" | SUBJECT="$SUBJECT" "$PY" -c '
import json, os, sys
runs = [r for r in json.load(sys.stdin).get("workflow_runs", []) if r["head_commit"]["message"].split("\n")[0] == os.environ["SUBJECT"]]
print(runs[0]["id"], runs[0]["status"]) if runs else print("", "")' 2>/dev/null | tr -d '\r') || true
    [ "$state" = completed ] && break
    m=$(( (SECONDS - t0) / 60 ))
    [ -n "$run" ] && echo "   run $run $state, $m min" || { [ $((m % 10)) = 0 ] && echo "   no run on $BRANCH yet ($m min): pushed?"; }
    sleep 60
  done
  if [ "$state" = completed ]; then
    mapfile -t -O "${#REPORT[@]}" REPORT < <(api "repos/$REPO/actions/runs/$run/jobs?per_page=100" | "$PY" tools/ci/run_report.py --plain | tr -d '\r')
    REPORT+=("      https://github.com/$REPO/actions/runs/$run   (then ask Bosun to delete $BRANCH)")
  else
    REPORT+=("FAIL  $(printf '%5d' $((SECONDS - t0))) s  remote: no finished run on $BRANCH within $WAIT min${run:+ (run $run: $state)}")
  fi
fi

if [ -f "$APK" ]; then
  SHA=$(sha256sum < "$APK" | cut -c1-64)
  if on install; then
    check install-host "$ADB" -s "$HOST" install -r "$APK"; check install-guest "$ADB" -s "$GUEST" install -r "$APK"
    # Play Store off, animations off, hints seen; warns about a long-running emulator (they leak memory over hours).
    for d in "$HOST" "$GUEST"; do ANDROID_SERIAL="$d" bash tools/emulator_setup.sh test; done
  fi
  if on lanes; then
    # Each lane runs its scripts one after another; the two lanes run side by side.
    # Lane 1 keeps the report so far (its file holds every line up to here); lane 2 starts empty.
    ( for r in $RULESETS; do ANDROID_SERIAL="$HOST" check "trade-$r" bash tools/ci_trade.sh 300 "$r"; done
      ANDROID_SERIAL="$HOST" check touchplay bash tools/ci_touchplay.sh 100 10 "$(for r in $RULESETS; do printf '%s:3 %s:4 ' "$r" "$r"; done)"
      printf '%s\n' "${REPORT[@]}" > "$OUT/lane1.report" ) &
    L1=$!
    ( REPORT=()
      ANDROID_SERIAL="$GUEST" check withdraw bash tools/ci_withdraw.sh 300 "$RULESETS"
      ANDROID_SERIAL="$GUEST" check monkey bash tools/ci_monkey.sh "$APK" 5000
      ANDROID_SERIAL="$GUEST" check names bash tools/names_check.sh "English queens" "Elizabeth I,Charlotte,Catherine"
      ANDROID_SERIAL="$GUEST" SMALL=1 check colours bash tools/colours_check.sh
      printf '%s\n' "${REPORT[@]}" > "$OUT/lane2.report" ) &
    L2=$!
    wait $L1 $L2
    mapfile -t REPORT < <(cat "$OUT/lane1.report" "$OUT/lane2.report" 2>/dev/null)
  fi
  if on friends; then
    fbconfig
    ( cd "$OUT" && exec firebase emulators:start --config firebase.json --only firestore,auth --project demo-wgbg ) > "$OUT/fbemu.log" 2>&1 &
    FB=$!
    for _ in $(seq 60); do curl -s -m 2 "localhost:$FS_PORT" > /dev/null && curl -s -m 2 "localhost:$AUTH_PORT" > /dev/null && break; sleep 2; done
    for r in $RULESETS; do
      PORTS="--ei fsport $FS_PORT --ei authport $AUTH_PORT" HOST_SERIAL="$HOST" GUEST_SERIAL="$GUEST" check "friends-$r" bash tools/friends_sim.sh "$r"
    done
    kill $FB 2>/dev/null; wait $FB 2>/dev/null
  fi
  on layout && ANDROID_SERIAL="$HOST" check layout bash tools/ci_layout.sh
elif on install || on lanes || on friends || on layout; then
  REPORT+=("SKIP      0 s  device checks: no APK at $APK")
fi

{
  echo; echo "Release check, $(git log -1 --format='%h %s' | cut -c1-70)"
  printf '  %s\n' "${REPORT[@]}"
  [ -n "${SHA:-}" ] && echo "  local APK sha256: $SHA (no Firebase config: not the one to publish or user-test)"
  cat <<'EOF'

Still to do by hand (AGENTS.md, never by hooks or debug extras):
  [ ] HARD STEP: the exact APK to be published, downloaded from the green per-push run publish_release will take it
      from (artifact working-german-board-game-debug-apk; never a local build: those have no Firebase config, and
      the hand-uploaded 0.3.4 had none). Installed fresh, and as an update over the last release.
  [ ] HARD STEP: with that APK, host and join online against real Firebase (no --es firebase emu), and send a report
  [ ] in through the start screen with real taps; full games to the end, base and Age of Chivalry, 3 and 4 players
  [ ] closed and reopened mid-game
  [ ] every changed feature tried
  Afterwards: ANDROID_SERIAL=<lane> tools/emulator_setup.sh user  on a phone a person uses (Play Store back on).
EOF
} | tee "$OUT/report.txt"
! printf '%s\n' "${REPORT[@]}" | grep -q '^FAIL'
