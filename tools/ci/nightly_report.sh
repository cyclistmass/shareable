#!/usr/bin/env bash
# The nightly run's last job: every shard's result in one table, as an issue "Nightly YYYY-MM-DD" (label testing) when
# anything failed, and every Monday whatever happened, so a quiet week still leaves a record. The run summary always
# gets the table. Needs GH_TOKEN (the workflow's GITHUB_TOKEN with issues: write and actions: read).
set -euo pipefail
REPO="$GITHUB_REPOSITORY"; RUN="$GITHUB_RUN_ID"
URL="$GITHUB_SERVER_URL/$REPO/actions/runs/$RUN"
DAY=$(date -u +%F)
jobs=$(gh api --paginate "repos/$REPO/actions/runs/$RUN/attempts/${GITHUB_RUN_ATTEMPT:-1}/jobs?per_page=100")
set +e
table=$(printf '%s' "$jobs" | python3 tools/ci/run_report.py --skip report)
status=$?
set -e
failed=$(printf '%s\n' "$table" | grep -c 'fail\*\*' || true)
body=$(cat <<EOF
Nightly playtest of \`${GITHUB_SHA:0:8}\` ($DAY): $([ "$failed" = 0 ] && echo "everything passed" || echo "**$failed failed**").

$table

Run, logs and artifacts (screenshots, monkey seeds, dice-fairness reports): $URL
Automated checks only: they never count as user testing (AGENTS.md).
EOF
)
printf '%s\n' "$body" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
printf '%s\n' "$body"

if [ "$status" = 0 ] && [ "$(date -u +%u)" != 1 ]; then echo "all passed and not Monday: no issue"; exit 0; fi
gh label create testing --repo "$REPO" --color 0e8a16 --description "Test runs and their results" 2>/dev/null || true
title="Nightly $DAY"
# A re-run of the same night adds a comment to that night's issue instead of opening a second one.
existing=$(gh issue list --repo "$REPO" --label testing --state all --search "\"$title\" in:title" --json number,title \
  --jq ".[] | select(.title == \"$title\") | .number" | head -1)
if [ -n "$existing" ]; then gh issue comment "$existing" --repo "$REPO" --body "$body"
else gh issue create --repo "$REPO" --title "$title" --label testing --body "$body"; fi
# The report itself passes: a failing shard already shows red on its own job.
