"""Housekeeping of the work folders (`$DIETER_HOME/worktrees/PROJECT/CARD`) the daemon makes for worktree cards.

The daemon never removes one by itself. This sweep removes the folder of a card that has been in Done (or
archived) and untouched for `days` days, with the daemon's own operations, so the Dieter CLI stays as it is:

1. `workspace run --kind cleanup`: removes the folder and the branch; the daemon refuses unless the branch is
   merged into its base (a fast-forward or merge commit).
2. A squash-merged pull request leaves the branch unmerged for Git, so cleanup refuses. If GitHub reports a
   merged pull request whose head is exactly the workspace's head commit, every commit is in the base and
   `workspace run --kind discard` removes it (it keeps recovery artifacts under `$DIETER_HOME/recovery`).

Anything else stays: an unmerged branch is never discarded, a running card is never touched.
"""
import json
import re
import subprocess
import time
from datetime import datetime


def parse_time(s):
    """RFC 3339 with nanoseconds -> epoch seconds (None when absent or unreadable)."""
    try:
        return datetime.fromisoformat(re.sub(r"(\.\d{6})\d+", r"\1", str(s)).replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def idle_since(card):
    """When a Done or archived card was last touched: its last agent activity or its move into Done (archived
    cards also count the archiving, which the daemon records in updatedAt). None unless it is Done or archived."""
    archived = bool(card.get("archived"))
    if not archived and card.get("lane") != "done":
        return None
    keys = ("lastActivityAt", "phaseChangedAt") + (("updatedAt",) if archived else ())
    times = [t for t in (parse_time(card.get(k)) for k in keys) if t]
    return max(times) if times else None


def operation_result(out):
    """The last frame of `workspace run --wait` (a JSON line per change) -> (status, error)."""
    last = None
    for line in out.splitlines():
        try:
            last = json.loads(line)
        except ValueError:
            continue
    op = (last or {}).get("operation") or {}
    return op.get("status"), op.get("error") or ""


def gh_merged_head(path, branch, head):
    """True when a merged GitHub pull request of `branch` ended at commit `head` (read only, via `gh`)."""
    try:
        p = subprocess.run(["gh", "pr", "list", "--head", branch, "--state", "merged", "--limit", "10", "--json", "headRefOid"],
                           cwd=path, capture_output=True, text=True, timeout=60)
        return p.returncode == 0 and any(x.get("headRefOid") == head for x in json.loads(p.stdout or "[]"))
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return False


class Sweeper:
    """`run(args, timeout, parse)` is the bridge's CLI runner, `log` its logger; `merged_head` is replaceable for tests."""

    def __init__(self, run, log, days, merged_head=gh_merged_head, now=time.time):
        self.run, self.log, self.days, self.merged_head, self.now = run, log, days, merged_head, now
        self.skipped = {}   # card -> reason last logged, so a card that stays put is logged once, not every sweep

    def remove(self, card_id, kind):
        out = self.run(["workspace", "run", "--kind", kind, "--wait", card_id], timeout=120, parse=False)
        return operation_result(out)

    def sweep(self):
        """One pass over every project. Returns {card id: "cleanup" | "discard"} for the folders it removed."""
        removed = {}
        cutoff = self.now() - self.days * 86400
        projects = self.run(["project", "list", "--format", "json"], timeout=20) or {}
        for p in (projects.get("projects") if isinstance(projects, dict) else projects) or []:
            ws = self.run(["workspace", "list", "--project", p["id"]], timeout=30) or {}
            for w in ws.get("workspaces") or []:
                if w.get("mode") != "worktree" or w.get("state") != "ready":
                    continue
                cid = w["cardId"]
                try:
                    kind = self.one(p, w, cutoff)
                except Exception as e:
                    self.note(cid, f"failed: {e}")
                    continue
                if kind:
                    removed[cid] = kind
                    self.skipped.pop(cid, None)
        return removed

    def one(self, project, w, cutoff):
        cid = w["cardId"]
        card = (self.run(["card", "show", cid], timeout=20) or {}).get("card") or {}
        since = idle_since(card)
        if since is None or since > cutoff or card.get("runtime") == "running":
            return None
        status, error = self.remove(cid, "cleanup")
        if status == "succeeded":
            self.log(f"housekeeping: removed the work folder of {cid} ({card.get('title', '')[:50]!r}), branch merged")
            return "cleanup"
        if "not merged" not in error:
            self.note(cid, f"cleanup {status}: {error}")
            return None
        path = project.get("path") or ""
        if not (w.get("branch") and w.get("headSha") and self.merged_head(path, w["branch"], w["headSha"])):
            self.note(cid, "branch is not merged, kept")
            return None
        status, error = self.remove(cid, "discard")
        if status == "succeeded":
            self.log(f"housekeeping: removed the work folder of {cid} ({card.get('title', '')[:50]!r}), pull request merged "
                     "(recovery copy in the store's recovery folder)")
            return "discard"
        self.note(cid, f"discard {status}: {error}")
        return None

    def note(self, cid, why):
        if self.skipped.get(cid) != why:
            self.skipped[cid] = why
            self.log(f"housekeeping: {cid}: {why}")
