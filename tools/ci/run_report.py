"""A workflow run's jobs as one PASS/FAIL table, for the nightly issue and release_check's remote mode.

Reads the GitHub API's jobs list for a run (GET /repos/{repo}/actions/runs/{id}/jobs, one or more pages, each a JSON
object with "jobs") on stdin and prints a table: Markdown by default, plain text (release_check's report lines) with
--plain. Jobs still running show as RUN; the report job itself (--skip NAME) is left out.
Exit status: 0 all passed, 1 something failed or was cancelled, 3 something still running.
Usage: gh api --paginate repos/OWNER/REPO/actions/runs/ID/jobs | python tools/ci/run_report.py [--plain] [--skip report]
"""
import json
import sys
from datetime import datetime


def pages(text):
    """The API's pages, which --paginate prints back to back: {...}{...}."""
    dec, i, out = json.JSONDecoder(), 0, []
    text = text.strip()
    while i < len(text):
        obj, i = dec.raw_decode(text, i)
        out.append(obj)
        while i < len(text) and text[i].isspace():
            i += 1
    return out


def seconds(job):
    if not job.get("started_at") or not job.get("completed_at"):
        return None
    t = lambda s: datetime.fromisoformat(s.replace("Z", "+00:00"))
    return int((t(job["completed_at"]) - t(job["started_at"])).total_seconds())


def verdict(job):
    if job.get("status") != "completed":
        return "RUN"
    return {"success": "PASS", "skipped": "SKIP"}.get(job.get("conclusion"), "FAIL")


def main(argv):
    plain = "--plain" in argv
    skip = {argv[i + 1] for i, a in enumerate(argv) if a == "--skip" and i + 1 < len(argv)}
    jobs = [j for p in pages(sys.stdin.read()) for j in p.get("jobs", []) if j.get("name") not in skip]
    # The order a reader wants: failures first, then by name.
    jobs.sort(key=lambda j: (verdict(j) != "FAIL", j.get("name", "")))
    rows = [(verdict(j), seconds(j), j.get("name", "?"), j.get("html_url", "")) for j in jobs]
    if plain:
        for v, s, name, url in rows:
            secs = f"{s:5d} s" if s is not None else "    - s"
            print(f"{v:4}  {secs}  remote {name}" + (f"   ({url})" if v == "FAIL" else ""))
    else:
        print("| Result | Shard | Time | Log |\n|---|---|---|---|")
        for v, s, name, url in rows:
            mins = f"{s // 60} min {s % 60:02d} s" if s is not None else "-"
            mark = {"PASS": "✅ pass", "FAIL": "❌ **fail**", "SKIP": "➖ skip", "RUN": "⏳ running"}[v]
            print(f"| {mark} | {name} | {mins} | [log]({url}) |")
    verdicts = {r[0] for r in rows}
    return 1 if "FAIL" in verdicts else 3 if "RUN" in verdicts else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
