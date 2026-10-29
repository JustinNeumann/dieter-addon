"""Unit tests of bridge/housekeeping.py with a fake `dieter` runner. Run: python3 -m unittest discover -s dieter/tests -p 'housekeeping_test.py'"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bridge"))
import housekeeping as hk  # noqa: E402

NOW = hk.parse_time("2026-11-20T12:00:00Z")
OLD, RECENT = "2026-11-01T10:00:00.123456789Z", "2026-11-10T10:00:00Z"   # 19 and 10 days before NOW


def frame(status, error=None):
    op = {"id": "gitop_1", "status": status, **({"error": error} if error else {})}
    return "\n".join(json.dumps({"operation": {**op, "status": s}}) for s in ("queued", "running")) + "\n" + json.dumps({"operation": op}) + "\n"


class Fake:
    """Cards by id; `cleanup`/`discard` outcomes by id. Records every removal."""

    def __init__(self, cards, cleanup=None, ws=None):
        self.cards, self.cleanup, self.calls = cards, cleanup or {}, []
        self.ws = ws or {cid: {"cardId": cid, "mode": "worktree", "state": "ready", "branch": f"dieter/{cid}", "headSha": "h1"} for cid in cards}

    def run(self, args, timeout=30, parse=True):
        if args[:2] == ["project", "list"]:
            return {"projects": [{"id": "p_1", "path": "/p"}]}
        if args[:2] == ["workspace", "list"]:
            return {"workspaces": list(self.ws.values())}
        if args[:2] == ["card", "show"]:
            return {"card": self.cards[args[2]]}
        if args[:2] == ["workspace", "run"]:
            kind, cid = args[args.index("--kind") + 1], args[-1]
            self.calls.append((kind, cid))
            return frame(*self.cleanup.get(cid, ("succeeded",))) if kind == "cleanup" else frame("succeeded")
        raise AssertionError(args)


def sweeper(fake, merged=False, days=14):
    logs = []
    s = hk.Sweeper(fake.run, lambda *a: logs.append(" ".join(map(str, a))), days=days, merged_head=lambda *a: merged, now=lambda: NOW)
    s.logs = logs
    return s


def card(lane="done", at=OLD, **kw):
    return {"lane": lane, "lastActivityAt": at, "phaseChangedAt": at, "runtime": "idle", "title": "t", **kw}


class Sweep(unittest.TestCase):
    def test_time_parsing(self):
        self.assertEqual(hk.parse_time("2026-10-29T05:38:37Z"), hk.parse_time("2026-10-29T05:38:37.999999999Z") - 0.999999)
        self.assertIsNone(hk.parse_time(None))
        self.assertIsNone(hk.parse_time("soon"))

    def test_idle_since(self):
        self.assertIsNone(hk.idle_since(card("review")))
        self.assertEqual(hk.idle_since(card("done", OLD)), hk.parse_time(OLD))
        c = card("review", OLD, archived=True, updatedAt=RECENT)   # archived recently: the clock starts there
        self.assertEqual(hk.idle_since(c), hk.parse_time(RECENT))
        self.assertEqual(hk.idle_since(card("done", OLD, updatedAt=RECENT)), hk.parse_time(OLD))   # a plain edit does not count

    def test_old_done_card_is_cleaned_up(self):
        f = Fake({"c_a": card()})
        self.assertEqual(sweeper(f).sweep(), {"c_a": "cleanup"})
        self.assertEqual(f.calls, [("cleanup", "c_a")])

    def test_kept_cards(self):
        f = Fake({"c_recent": card(at=RECENT), "c_review": card("review"), "c_running": card(runtime="running"), "c_todo": card("todo")})
        self.assertEqual(sweeper(f).sweep(), {})
        self.assertEqual(f.calls, [])

    def test_archived_card_in_any_lane(self):
        f = Fake({"c_a": card("review", archived=True, updatedAt=OLD)})
        self.assertEqual(sweeper(f).sweep(), {"c_a": "cleanup"})

    def test_retention_days(self):
        f = Fake({"c_a": card(at=RECENT)})
        self.assertEqual(sweeper(f, days=5).sweep(), {"c_a": "cleanup"})

    def test_project_mode_workspaces_are_ignored(self):
        f = Fake({"c_a": card()}, ws={"c_a": {"cardId": "c_a", "mode": "project", "state": "ready"}})
        self.assertEqual(sweeper(f).sweep(), {})

    def test_squash_merged_pull_request_is_discarded(self):
        f = Fake({"c_a": card()}, cleanup={"c_a": ("failed", "workspace branch is not merged into its base")})
        self.assertEqual(sweeper(f, merged=True).sweep(), {"c_a": "discard"})
        self.assertEqual(f.calls, [("cleanup", "c_a"), ("discard", "c_a")])

    def test_unmerged_work_is_never_discarded(self):
        f = Fake({"c_a": card()}, cleanup={"c_a": ("failed", "workspace branch is not merged into its base")})
        s = sweeper(f, merged=False)
        self.assertEqual(s.sweep(), {})
        self.assertEqual(f.calls, [("cleanup", "c_a")])
        s.sweep()
        self.assertEqual(len([l for l in s.logs if "kept" in l]), 1)   # logged once, not every hour

    def test_other_cleanup_failures_do_not_fall_back_to_discard(self):
        f = Fake({"c_a": card()}, cleanup={"c_a": ("failed", "workspace has conflicts")})
        self.assertEqual(sweeper(f, merged=True).sweep(), {})
        self.assertEqual(f.calls, [("cleanup", "c_a")])

    def test_one_failure_does_not_stop_the_others(self):
        f = Fake({"c_a": card(), "c_b": card()})
        orig = f.run
        f.run = lambda args, **k: (_ for _ in ()).throw(RuntimeError("boom")) if args[:3] == ["card", "show", "c_a"] else orig(args, **k)
        self.assertEqual(sweeper(f).sweep(), {"c_b": "cleanup"})


if __name__ == "__main__":
    unittest.main()
