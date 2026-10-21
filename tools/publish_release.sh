#!/usr/bin/env bash
# Publishes release wgbg-v<versionName> on cyclistmass/shareable: the debug APK built by CI for the pushed
# commit, plus the gameplay video from tools/record_showcase.sh. Asset names never change, so
#   https://github.com/cyclistmass/shareable/releases/latest/download/working-german-board-game.apk
# always serves the newest version. Run from the project root after CI is green for the pushed commit.
# The GitHub token comes from Git Credential Manager for one command at a time; it is never printed or saved.
# Usage: [APK=<tested apk>] [KNOWN=<known issues, markdown>] [PRERELEASE=1] tools/publish_release.sh [path to gameplay mp4]
# PRERELEASE=1: a test build from a test/* branch (SHA=<its commit>, CI = its green release check). It is marked
# pre-release and never becomes latest, so the always-latest link keeps serving the last real release.
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
RUN=$(api "https://api.github.com/repos/$REPO/actions/runs?head_sha=$SHA" | py -c "
import json,sys
r=[w for w in json.load(sys.stdin)['workflow_runs'] if 'working' in w['name'] or w['name']=='wgbg release check']
# Any green run for this commit (a re-run may still be going); none green means don't publish.
ok=[w for w in r if w['conclusion']=='success']
print(ok[0]['id'] if ok else '')")
[ -n "$RUN" ] || { echo "CI is not green for ${SHA:0:7}; not publishing"; exit 1; }
ART=$(api "https://api.github.com/repos/$REPO/actions/runs/$RUN/artifacts" | py -c "
import json,sys
print([a['id'] for a in json.load(sys.stdin)['artifacts'] if a['name'].endswith('debug-apk') or a['name']=='shards-apk'][0])")
curl -sL -m 300 -H "Authorization: Bearer $(tok)" -o "$WORK/apk.zip" "https://api.github.com/repos/$REPO/actions/artifacts/$ART/zip"
# The release check's artifact also holds the instrumented-test APK, in its own folder: take the app's.
(cd "$WORK" && unzip -q apk.zip && mv "$(find . -name app-debug.apk -not -path '*androidTest*' | head -1)" working-german-board-game.apk)
# APK=<file>: publish the exact APK that was user-tested (same commit), not CI's rebuild of it. CI must still be green.
if [ -n "${APK:-}" ]; then
  echo "CI's APK:    $(sha256sum < "$WORK/working-german-board-game.apk" | cut -c1-64)"
  echo "Tested APK:  $(sha256sum < "$APK" | cut -c1-64) (publishing this one)"
  # Players update over CI-signed releases: a different signing cert would make every update fail.
  cert() { "$(ls -d "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"/build-tools/*/ | tail -1)apksigner.bat" verify --print-certs "$1" | sed -n 's/.*SHA-256 digest: //p'; }
  [ "$(cert "$APK")" = "$(cert "$WORK/working-german-board-game.apk")" ] || { echo "Tested APK is signed with a different cert than CI's; not publishing"; exit 1; }
  cp "$APK" "$WORK/working-german-board-game.apk"
fi

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
up() { MAXTIME=900 api -H "Content-Type: $2" --data-binary @"$1" "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=$(basename "$1")" |
  py -c "import json,sys;d=json.load(sys.stdin);print(d['browser_download_url'])" || { echo "upload of $(basename "$1") failed; release $ID left as a draft"; exit 1; }; }
up "$WORK/working-german-board-game.apk" application/vnd.android.package-archive
# A pre-release gets no video: the showcase is recorded for real releases.
if [ -f "$VIDEO" ] && [ -z "${PRERELEASE:-}" ]; then cp "$VIDEO" "$WORK/working-german-board-game-gameplay.mp4"; up "$WORK/working-german-board-game-gameplay.mp4" video/mp4; fi
LATEST=true; [ -n "${PRERELEASE:-}" ] && LATEST=false
api -X PATCH "https://api.github.com/repos/$REPO/releases/$ID" -d "{\"draft\":false,\"make_latest\":\"$LATEST\"}" |
  py -c "import json,sys;d=json.load(sys.stdin);print('published', d['html_url'])"
rm -rf "$WORK"
echo "published wgbg-v$VERSION from ${SHA:0:7}"
# Close the issues this release shipped (named in commit subjects since the previous release).
# Not for a pre-release: nothing has shipped until the real release.
[ -n "${PRERELEASE:-}" ] || REPO="$REPO" SHAREABLE="$SHAREABLE" "$(dirname "$0")/close_shipped.sh" "$VERSION" "$SHA"

