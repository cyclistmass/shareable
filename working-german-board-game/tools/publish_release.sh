#!/usr/bin/env bash
# Publishes release wgbg-v<versionName> on cyclistmass/shareable: the debug APK built by CI for the pushed
# commit, plus the gameplay video from tools/record_showcase.sh. Asset names never change, so
#   https://github.com/cyclistmass/shareable/releases/latest/download/working-german-board-game.apk
# always serves the newest version. Run from the project root after CI is green for the pushed commit.
# The GitHub token comes from Git Credential Manager for one command at a time; it is never printed or saved.
# Usage: [APK=<tested apk>] [KNOWN=<known issues, markdown>] [PRERELEASE=1] tools/publish_release.sh [path to gameplay mp4]
# PRERELEASE=1: a test build from a test/* branch (SHA=<its commit>, CI = its green release check). It is marked
# pre-release and never becomes latest, so the always-latest link keeps serving the last real release. The APK must
# still come from a per-push CI run (built with the Firebase config): a check run's shards-apk is never published.
set -euo pipefail
VIDEO="${1:-$TEMP/wgbg-showcase/working-german-board-game-gameplay.mp4}"
REPO="${REPO:-cyclistmass/shareable}"   # REPO=cyclistmass/sharable until the rename has run
SHAREABLE="${SHAREABLE:-../shareable}"
WORK="$(mktemp -d)"
# Never wait on a login prompt: no token in the store means fail now (a prompt would hang the release).
tok() { printf "protocol=https\nhost=github.com\npath=%s.git\n\n" "$REPO" | GCM_INTERACTIVE=never GIT_TERMINAL_PROMPT=0 git -c credential.useHttpPath=true credential fill | sed -n 's/^password=//p'; }
# MAXTIME: seconds per call; an upload of the ~120 MB APK takes about two minutes (0.3.4's hit a 60 s limit).
api() { curl -s -m "${MAXTIME:-60}" -H "Authorization: Bearer $(tok)" "$@"; }

# The commit to release: origin/main, or SHA=<commit> to pin an earlier tested build while newer work lands on main.
# Always the full 40-character id: the GitHub API matches head_sha only in full (a short SHA=44ef434 found no CI run).
SHA="$(git -C "$SHAREABLE" rev-parse "${SHA:-origin/main}^{commit}")"
# The version of that commit, not of this checkout (main may already be on the next version).
VERSION=$(git -C "$SHAREABLE" show "$SHA:working-german-board-game/app/build.gradle.kts" | sed -n 's/.*versionName = "\(.*\)".*/\1/p')
RUNS=$(api "https://api.github.com/repos/$REPO/actions/runs?head_sha=$SHA" | py -c "
import json,sys
r=[w for w in json.load(sys.stdin)['workflow_runs'] if 'working' in w['name'] or w['name']=='wgbg release check']
# Every green run for this commit (a re-run may still be going); none green means don't publish.
print(' '.join(str(w['id']) for w in r if w['conclusion']=='success'))")
[ -n "$RUNS" ] || { echo "CI is not green for ${SHA:0:7}; not publishing"; exit 1; }
# Only the per-push CI's APK, built with the Firebase config; never a check run's shards-apk (built without it for
# the shards). The published 0.3.4 had no config (a local build, hand-uploaded) and was swapped for CI's.
RUN=; ART=
for r in $RUNS; do
  ART=$(api "https://api.github.com/repos/$REPO/actions/runs/$r/artifacts" | py -c "
import json,sys
print(([a['id'] for a in json.load(sys.stdin)['artifacts'] if a['name']=='working-german-board-game-debug-apk']+[''])[0])")
  [ -z "$ART" ] || { RUN=$r; break; }
done
[ -n "$ART" ] || { echo "no green run for ${SHA:0:7} has the per-push APK (working-german-board-game-debug-apk); not publishing"; exit 1; }
curl -sL -m 300 -H "Authorization: Bearer $(tok)" -o "$WORK/apk.zip" "https://api.github.com/repos/$REPO/actions/artifacts/$ART/zip"
(cd "$WORK" && unzip -q apk.zip && mv "$(find . -name app-debug.apk -not -path '*androidTest*' | head -1)" working-german-board-game.apk)
# APK=<file>: publish the exact APK that was user-tested (same commit; it must still pass verify below, so a local
# build, which has no Firebase config, is refused). CI must still be green.
if [ -n "${APK:-}" ]; then
  echo "CI's APK:    $(sha256sum < "$WORK/working-german-board-game.apk" | cut -c1-64)"
  echo "Tested APK:  $(sha256sum < "$APK" | cut -c1-64) (publishing this one)"
  # Players update over CI-signed releases: a different signing cert would make every update fail.
  cert() { "$(ls -d "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"/build-tools/*/ | tail -1)apksigner.bat" verify --print-certs "$1" | sed -n 's/.*SHA-256 digest: //p'; }
  [ "$(cert "$APK")" = "$(cert "$WORK/working-german-board-game.apk")" ] || { echo "Tested APK is signed with a different cert than CI's; not publishing"; exit 1; }
  cp "$APK" "$WORK/working-german-board-game.apk"
fi
# verify <apk>: the stable key, and the Firebase config (string/google_app_id): without it online play and in-app
# reports are dead. Local builds never have it; CI's write it from the GOOGLE_SERVICES_JSON secret.
verify() {
  local bt; bt="$(ls -d "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"/build-tools/*/ | tail -1)"
  local signer="${bt}apksigner" aapt2="${bt}aapt2"; [ -e "$signer" ] || signer="$signer.bat"; [ -e "$aapt2" ] || aapt2="$aapt2.exe"
  local cert res
  cert=$("$signer" verify --print-certs "$1" | tr -d '\r' | sed -n 's/.*SHA-256 digest: //p' | head -1)
  [ "$cert" = 90235a5b47da6ee29b077ea7c4ac94d2907c2126267adb5a999afcc4b5fb0093 ] || { echo "$1: not the stable key ($cert)"; return 1; }
  res=$("$aapt2" dump resources "$1" | tr -d '\r')
  grep -c 'string/google_app_id' <<< "$res" > /dev/null || { echo "$1: NO Firebase config (no string/google_app_id)"; return 1; }
  echo "$1: stable key, Firebase config present"
}
verify "$WORK/working-german-board-game.apk" || { echo "not publishing"; exit 1; }
APK_SHA=$(sha256sum < "$WORK/working-german-board-game.apk" | cut -c1-64)
# back <what> <curl args...>: downloads the APK as players or the API serve it (three tries: the CDN can lag a moment)
# and checks it is the one checked above, byte for byte, and passes the same check.
back() {
  local what="$1" f="$WORK/back.apk" got=; shift
  for _ in 1 2 3; do rm -f "$f"; curl -sSfL -m 900 -o "$f" "$@" && got=$(sha256sum < "$f" | cut -c1-64) && [ "$got" = "$APK_SHA" ] && break; sleep 10; done
  [ "$got" = "$APK_SHA" ] || { echo "$what: downloaded back as ${got:-nothing}, not $APK_SHA" >&2; return 1; }
  verify "$f" >&2 || { echo "$what: the APK served fails its check" >&2; return 1; }
  echo "$what: served APK matches ($APK_SHA) and passes its check" >&2
}

# KNOWN=<text> adds a Known issues section (read from the environment, so its quotes and $ stay as written).
KNOWN="${KNOWN:-}" py -c "
import json,sys,os
known=os.environ.get('KNOWN','').strip()
print(json.dumps({'tag_name':'wgbg-v$VERSION','target_commitish':'$SHA','name':'Working German Board Game $VERSION'+(' (pre-release)' if os.environ.get('PRERELEASE') else ''),'draft':True,'prerelease':bool(os.environ.get('PRERELEASE')),
 'body':'''Android build $VERSION of Working German Board Game (debug-signed, for testing).

**Install:** download working-german-board-game.apk below on an Android phone (Android 8+), open it, and allow installing from this source when asked. It updates any earlier version.

'''+('**Pre-release:** a test build of work in progress: automated tests and a short check by hand, not the full hand test a release gets. It installs over the last release; the always-latest link below still serves that release.'+chr(10)*2 if os.environ.get('PRERELEASE') else '')+'''Always-latest link: https://github.com/$REPO/releases/latest/download/working-german-board-game.apk

Built by CI from commit $SHA; source in [working-german-board-game/](https://github.com/$REPO/tree/$SHA/working-german-board-game). GPL-3.0.'''+((chr(10)*2+'**Known issues:**'+chr(10)+known) if known else '')}))" > "$WORK/release.json"
ID=$(api -X POST "https://api.github.com/repos/$REPO/releases" -d @"$WORK/release.json" | py -c "import json,sys;d=json.load(sys.stdin);print(d.get('id') or d)")
case "$ID" in *[!0-9]*) echo "release not created: $ID"; exit 1;; esac
# The release is a draft until every file is up, so releases/latest never points at one without its APK (0.3.4 did,
# for four minutes, after a timed-out upload). A failed upload stops here and leaves the draft to look at.
# up prints the new asset's id.
up() { MAXTIME=900 api -H "Content-Type: $2" --data-binary @"$1" "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=$(basename "$1")" |
  py -c "import json,sys;d=json.load(sys.stdin);print(d['id'])" || { echo "upload of $(basename "$1") failed; release $ID left as a draft" >&2; exit 1; }; }
ASSET=$(up "$WORK/working-german-board-game.apk" application/vnd.android.package-archive) || exit 1
# The draft's APK, downloaded back before anyone can see it: a broken or swapped upload stops here, still a draft.
back "draft asset $ASSET" -H "Authorization: Bearer $(tok)" -H "Accept: application/octet-stream" \
  "https://api.github.com/repos/$REPO/releases/assets/$ASSET" || { echo "release $ID left as a draft: fix or delete it"; exit 1; }
# A pre-release gets no video: the showcase is recorded for real releases.
if [ -f "$VIDEO" ] && [ -z "${PRERELEASE:-}" ]; then cp "$VIDEO" "$WORK/working-german-board-game-gameplay.mp4"; up "$WORK/working-german-board-game-gameplay.mp4" video/mp4; fi
LATEST=true; [ -n "${PRERELEASE:-}" ] && LATEST=false
api -X PATCH "https://api.github.com/repos/$REPO/releases/$ID" -d "{\"draft\":false,\"make_latest\":\"$LATEST\"}" |
  py -c "import json,sys;d=json.load(sys.stdin);print('published', d['html_url'])"
# And as players get it now: the always-latest link for a release, the tag's link for a pre-release.
URL="https://github.com/$REPO/releases/latest/download/working-german-board-game.apk"
[ -n "${PRERELEASE:-}" ] && URL="https://github.com/$REPO/releases/download/wgbg-v$VERSION/working-german-board-game.apk"
back "$URL" "$URL" || { echo "!!! PUBLISHED, BUT $URL DOES NOT SERVE THE CHECKED APK: set release $ID back to draft or swap its asset now"; exit 1; }
rm -rf "$WORK"
echo "published wgbg-v$VERSION from ${SHA:0:7}"
# Close the issues this release shipped (named in commit subjects since the previous release).
# Not for a pre-release: nothing has shipped until the real release.
[ -n "${PRERELEASE:-}" ] || REPO="$REPO" SHAREABLE="$SHAREABLE" "$(dirname "$0")/close_shipped.sh" "$VERSION" "$SHA"

