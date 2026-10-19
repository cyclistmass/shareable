#!/usr/bin/env bash
# Closes the issues a release shipped (Mauro, 2026-10-19: "make closing these part of the CI cycle"; 28 shipped
# issues had stayed open for a week). Run by tools/publish_release.sh after the release exists, or by hand.
# An issue counts as shipped when a commit between the previous release tag and the released commit says so with a
# closing word: "Fixes #105", "Closes #34", "Resolves #12". A bare "#130" is progress, not done (the whale was the
# first of the #130 critter modules; #84 renamed only what players see), so those are listed for review, never closed.
# Each one still open gets a comment naming the version and commit, then is closed as completed.
# Trackers stay open: titles with "todo", "plan" or "live", and anything labelled keep-open.
# Usage: tools/close_shipped.sh <version> <released commit> [previous tag]    DRY_RUN=1 lists without writing.
set -euo pipefail
VERSION="$1"; SHA="$2"
REPO="${REPO:-cyclistmass/shareable}"
SHAREABLE="${SHAREABLE:-../shareable}"
tok() { printf "protocol=https\nhost=github.com\npath=%s.git\n\n" "$REPO" | GCM_INTERACTIVE=never GIT_TERMINAL_PROMPT=0 git -c credential.useHttpPath=true credential fill | sed -n 's/^password=//p'; }
api() { curl -s -m 60 -H "Accept: application/vnd.github+json" -H "Authorization: Bearer $(tok)" "$@"; }

git -C "$SHAREABLE" fetch -q --tags origin
# The newest wgbg-v* tag that is an ancestor of the released commit and isn't this version's own tag.
PREV="${3:-$(git -C "$SHAREABLE" tag -l 'wgbg-v*' --sort=-creatordate --merged "$SHA" | grep -vx "wgbg-v$VERSION" | head -1)}"
[ -n "$PREV" ] || { echo "close_shipped: no earlier wgbg-v tag below ${SHA:0:7}"; exit 1; }

# "<issue> <short sha> <subject>" per closing word; the first commit to close an issue is the one quoted.
git -C "$SHAREABLE" log --reverse --format='%h%x09%s%x09%b%x1e' "$PREV..$SHA" | py -c "
import re,sys
seen=set(); named=set()
for rec in sys.stdin.read().split('\x1e'):
    parts=rec.strip('\n').split('\t',2)
    if len(parts)<2: continue
    h,subj=parts[0],parts[1]; body=parts[2] if len(parts)>2 else ''
    for n in re.findall(r'(?i)\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+#(\d+)\b',subj+' '+body):
        if n not in seen: seen.add(n); print(n,h,subj)
    named.update(re.findall(r'#(\d+)\b',subj+' '+body))
for n in sorted(named-seen,key=int): print('  named, left open: #'+n,file=sys.stderr)
" > "${TEMP:-/tmp}/wgbg-shipped.txt"

echo "close_shipped: $PREV..${SHA:0:7} names $(wc -l < "${TEMP:-/tmp}/wgbg-shipped.txt") issues"
while read -r n h subj; do
  info=$(api "https://api.github.com/repos/$REPO/issues/$n" | py -c "
import json,re,sys
d=json.load(sys.stdin)
keep=d.get('pull_request') or d.get('state')!='open' or re.search(r'(?i)todo|plan|\blive\b',d.get('title','')) \
  or any(l['name']=='keep-open' for l in d.get('labels',[]))
print('skip' if keep else 'open', d.get('state','?'), d.get('title','')[:70])")
  case "$info" in skip*) continue;; esac
  echo "  #$n ${info#open } <- $h"
  [ -n "${DRY_RUN:-}" ] && continue
  py -c "import json,sys;print(json.dumps({'body':'Shipped in $VERSION (commit '+sys.argv[1]+': '+sys.argv[2]+').'}))" "$h" "$subj" > "${TEMP:-/tmp}/wgbg-close.json"
  api -X POST "https://api.github.com/repos/$REPO/issues/$n/comments" -d @"${TEMP:-/tmp}/wgbg-close.json" > /dev/null
  api -X PATCH "https://api.github.com/repos/$REPO/issues/$n" -d '{"state":"closed","state_reason":"completed"}' > /dev/null
done < "${TEMP:-/tmp}/wgbg-shipped.txt"
