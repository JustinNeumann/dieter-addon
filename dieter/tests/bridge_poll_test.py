"""Unit tests of the bridge's board polling (State, Watch) against a fake `dieter` CLI: which calls a change costs.
Run: python3 -m unittest discover -s dieter/tests -p 'bridge_poll_test.py'"""
import base64
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

HOME = tempfile.mkdtemp(prefix="bridge-poll-test-")
os.environ.update(DIETER_HOME=HOME, BRIDGE_KEY_FILE=os.path.join(HOME, "key"), BRIDGE_SETTINGS_FILE=os.path.join(HOME, "settings.json"))
os.makedirs(os.path.join(HOME, "runtime"))
with open(os.path.join(HOME, "runtime", "daemon.json"), "w") as f:
    json.dump({"daemonId": "d_here", "enrolled": True}, f)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bridge"))
_exists = os.path.exists
with mock.patch("os.path.exists", lambda p: False if p == "/data/options.json" else _exists(p)):
    import dieter_bridge as br  # noqa: E402

PIPE = {"id": "df_0123456789", "name": "Dreamfall", "project_id": "p_df",
        "stages": [{"role": "enumeration", "board_id": "b_e"}, {"role": "navigation", "board_id": "b_n"},
                   {"role": "implementation", "board_id": "b_i"}]}


def card(cid, pid, board, lane="todo", owner="d_here", **kw):
    return {"id": cid, "title": cid, "project_id": pid, "board_id": board, "lane": lane, "owner_daemon_id": owner,
            "runtime": "idle", "scope": "board", "updated_at": "2026-10-29T01:00:00Z", **kw}


def shown(c):
    """`card show`: camelCase under "card" (and the response counter as a string)."""
    camel = {"id": c["id"], "title": c["title"], "projectId": c["project_id"], "boardId": c.get("board_id"), "lane": c.get("lane"),
             "ownerDaemonId": c["owner_daemon_id"], "runtime": c["runtime"], "scope": c["scope"], "updatedAt": c["updated_at"]}
    if c.get("archived"):
        camel["archived"] = True
    return {"card": camel, "project": {}, "board": {}}


class FakeCli:
    def __init__(self):
        self.calls = []
        self.projects = [{"id": "p_a", "name": "a"}, {"id": "p_df", "name": "df"}]
        self.cards = {"p_a": [card("c_a1", "p_a", "b_a"), card("c_a2", "p_a", "b_a", owner="d_pc")],
                      "p_df": [card("c_e1", "p_df", "b_e"), card("c_n1", "p_df", "b_n", "review"), card("c_x", "p_df", "b_other")]}
        self.chats = [{**card("c_chat", "p_a", None, None), "scope": "chat"}]

    def __call__(self, args, timeout=30, parse=True):
        self.calls.append(args)
        if args[0] == "status":
            return {"health": {"status": "ok"}}
        if args[:2] == ["project", "list"]:
            return {"projects": self.projects}
        if args[:2] == ["board", "list"]:
            return {"boards": [{"id": b, "name": b, "projectId": p} for p, b in (("p_a", "b_a"), ("p_df", "b_e"), ("p_df", "b_n"), ("p_df", "b_i"))]}
        if args[:2] == ["chat", "list"]:
            return self.chats
        if args[:2] == ["card", "list"]:
            pid, limit = args[args.index("--project") + 1], int(args[args.index("--limit") + 1])
            return self.cards[pid][:limit]
        if args[:2] == ["card", "show"]:
            cid = args[2]
            for c in [c for lst in self.cards.values() for c in lst] + self.chats:
                if c["id"] == cid:
                    if c["owner_daemon_id"] != "d_here":
                        raise br.CliError(f"conversation belongs to another machine; target its owner: {c['owner_daemon_id']}")
                    return shown(c)
            raise br.CliError(f'rpc error: code = NotFound desc = card "{cid}": not found')
        raise AssertionError(args)

    def kinds(self):
        out = [" ".join(a[:2]) for a in self.calls]
        self.calls = []
        return out


class Poll(unittest.TestCase):
    def setUp(self):
        self.cli = FakeCli()
        self.state = br.State()
        self.df = br.dreamfall.Cache(br.norm_card, on_change=lambda: None, log=lambda *a: None)
        self.df.records = {"p." + PIPE["id"]: PIPE}
        self.patches = [mock.patch.object(br, "run", self.cli), mock.patch.object(br, "STATE", self.state),
                        mock.patch.object(br, "DREAMFALL", self.df)]
        for p in self.patches:
            p.start()
        self.state.poll_fast()
        self.cli.kinds()

    def tearDown(self):
        for p in self.patches:
            p.stop()

    def cards(self):
        return {c["id"]: c for c in self.state.data["cards"]}

    def test_full_poll_lists_dreamfall_project_whole_and_feeds_its_stage_boards(self):
        self.state.refresh()
        self.state.poll_fast()
        lists = [a for a in self.cli.calls if a[:2] == ["card", "list"]]
        self.assertEqual({a[a.index("--project") + 1]: a[a.index("--limit") + 1] for a in lists}, {"p_a": "200", "p_df": "2000"})
        self.assertEqual(sorted(self.cli.kinds()), ["board list", "card list", "card list", "chat list", "project list", "status --format"])
        self.assertEqual([c["id"] for c in self.df.cards_of("b_e")], ["c_e1"])
        self.assertEqual([c["id"] for c in self.df.cards["b_n"]["review"][1]], ["c_n1"])
        self.assertNotIn("b_other", self.df.cards)            # not a stage board

    def test_state_keeps_card_limit_of_a_whole_listed_project(self):
        self.cli.cards["p_df"] += [card(f"c_d{i:03}", "p_df", "b_e") for i in range(br.CARD_LIMIT + 50)]
        self.state.refresh()
        self.state.poll_fast()
        self.assertEqual(sum(c["project_id"] == "p_df" for c in self.state.data["cards"]), br.CARD_LIMIT)
        self.assertEqual(len(self.df.cards_of("b_e")), br.CARD_LIMIT + 51)   # the stage board gets all of them

    def test_change_of_own_card_is_one_card_show(self):
        self.cli.cards["p_a"][0]["lane"] = "running"
        self.state.refresh(ids=["c_a1"])
        self.assertTrue(self.state.cheap())
        listed = self.state.poll_fast()
        self.assertEqual(self.cli.kinds(), ["card show"])
        self.assertFalse(listed)
        self.assertEqual(self.cards()["c_a1"]["lane"], "running")
        self.assertEqual(set(self.cards()), {"c_a1", "c_a2", "c_e1", "c_n1", "c_x"})

    def test_change_of_another_machines_card_lists_its_project_only(self):
        self.cli.cards["p_a"][1]["title"] = "renamed on the PC"
        self.state.refresh(ids=["c_a2"])
        self.assertFalse(self.state.cheap())
        self.assertTrue(self.state.poll_fast())
        self.assertEqual(self.cli.kinds(), ["card list"])
        self.assertEqual(self.cards()["c_a2"]["title"], "renamed on the PC")

    def test_new_card_is_shown_and_reaches_dreamfall(self):
        self.cli.cards["p_df"].append(card("c_e2", "p_df", "b_e"))
        self.state.refresh(ids=["c_e2"])
        self.state.poll_fast()
        self.assertEqual(self.cli.kinds(), ["card show"])
        self.assertIn("c_e2", self.cards())
        self.assertEqual(sorted(c["id"] for c in self.df.cards_of("b_e")), ["c_e1", "c_e2"])

    def test_new_card_of_another_machine_lists_everything(self):
        self.cli.cards["p_a"].append(card("c_pc", "p_a", "b_a", owner="d_pc"))
        self.state.refresh(ids=["c_pc"])
        self.state.poll_fast()
        self.assertIn("project list", self.cli.kinds())
        self.assertIn("c_pc", self.cards())

    def test_archived_and_deleted_cards_leave_the_state_and_dreamfall(self):
        self.cli.cards["p_df"][0]["archived"] = True
        del self.cli.cards["p_a"][0]
        self.state.patch("c_e1", archived=True)
        self.state.refresh(ids=["c_a1"])
        self.state.poll_fast()
        self.assertEqual(sorted(self.cli.kinds()), ["card show", "card show"])
        self.assertNotIn("c_e1", self.cards())
        self.assertNotIn("c_a1", self.cards())
        self.assertEqual(self.df.cards_of("b_e"), [])
        self.assertEqual(self.state.patches, {})              # the re-read covered the patch

    def test_patch_stays_until_a_poll_read_its_card(self):
        self.state.patch("c_a1", lane="done")                  # e.g. `card move` answered, the daemon not yet
        with self.state.lock:
            self.state.want_ids = {"c_e1"}                     # a poll that re-reads another card only
        self.state.poll_fast()
        self.assertIn("c_a1", self.state.patches)
        self.assertEqual(self.cards()["c_a1"]["lane"], "done")
        self.cli.cards["p_a"][0]["lane"] = "done"
        self.state.refresh(ids=["c_a1"])
        self.state.poll_fast()
        self.assertNotIn("c_a1", self.state.patches)

    def test_too_many_changes_or_old_full_poll_list_everything(self):
        self.state.refresh(ids=[f"c_n{i}" for i in range(br.SHOW_MAX + 1)])
        self.state.poll_fast()
        self.assertIn("project list", self.cli.kinds())
        self.state.last_full -= br.IDLE_POLL_S + 1
        self.state.refresh(ids=["c_a1"])
        self.state.poll_fast()
        self.assertIn("project list", self.cli.kinds())

    def test_chat_change(self):
        self.cli.chats[0]["title"] = "new chat title"
        self.state.refresh(ids=["c_chat"])
        self.state.poll_fast()
        self.assertEqual(self.cli.kinds(), ["card show"])
        self.assertEqual(self.state.data["chats"][0]["title"], "new chat title")
        self.assertEqual(self.state.data["chats"][0]["kind"], "chat")


def rec(rid, value, kind="item"):
    return {"kind": kind, "id": rid, "versions": [{"valueJson": base64.b64encode(json.dumps(value).encode()).decode()}]}


class WatchFrames(unittest.TestCase):
    def setUp(self):
        self.w = br.Watch()

    def test_owned_card_token_and_time_updates_are_quiet(self):
        own = {"id": "c_1", "workspace": {"state": "ready"}, "tokenUsage": {"totalTokens": "1"}, "updatedAt": "t1"}
        self.assertEqual(self.w.classify({"ownedCards": [own]}), (False, {"c_1"}))
        self.assertEqual(self.w.classify({"ownedCards": [{**own, "tokenUsage": {"totalTokens": "2"}, "updatedAt": "t2"}]}), (False, set()))
        self.assertEqual(self.w.classify({"ownedCards": [{**own, "workspace": {"state": "dirty"}}]}), (False, {"c_1"}))

    def test_summary_activity_time_is_quiet(self):
        self.w.classify({"records": [rec("c_1.summary", {"runtime": "running", "lastActivityAt": "t1"})]})
        self.assertEqual(self.w.classify({"records": [rec("c_1.summary", {"runtime": "running", "lastActivityAt": "t2"})]}), (False, set()))
        self.assertEqual(self.w.classify({"records": [rec("c_1.summary", {"runtime": "idle", "lastActivityAt": "t3"})]}), (False, {"c_1"}))

    def test_card_records_name_the_card_structure_lists_everything(self):
        self.assertEqual(self.w.classify({"records": [rec("c_2.placement", {"lane": "review"})]}), (False, {"c_2"}))
        self.assertEqual(self.w.classify({"records": [rec("b_1.name", "Main", "board")]}), (True, set()))
        self.assertEqual(self.w.classify({"records": [rec("p_1.name", "x", "project")]}), (True, set()))
        self.assertEqual(self.w.classify({"resetRecords": True}), (True, set()))

    def test_kv_and_schedule_records_are_not_on_the_board(self):
        frame = {"records": [rec("c.c_1", {"stage": "enumeration"}, "kv.dreamfall"), rec("sch_1", {}, "schedule")]}
        self.assertEqual(self.w.classify(frame), (False, set()))

    def test_checkouts_and_activity_status(self):
        co = {"id": "co_1", "projectId": "p_1", "path": "/x"}
        self.assertEqual(self.w.classify({"ownedCheckouts": [co]}), (True, set()))
        self.assertEqual(self.w.classify({"ownedCheckouts": [co]}), (False, set()))
        self.assertEqual(self.w.classify({"activities": [{"cardId": "c_3", "status": "running"}]}), (False, {"c_3"}))
        self.assertEqual(self.w.classify({"activities": [{"cardId": "c_3", "status": "running", "messages": [1]}]}), (False, set()))


class NormCard(unittest.TestCase):
    def test_list_and_show_agree(self):
        listed = br.norm_card({"id": "c_1", "response_seq": 5, "token_usage": {"input_tokens": 3, "output_tokens": 4, "total_tokens": 7}})
        show = br.norm_card({"id": "c_1", "responseSeq": "5", "tokenUsage": {"inputTokens": "3", "outputTokens": "4", "totalTokens": "7"}})
        self.assertEqual(listed["tokens"], {"inputTokens": 3, "outputTokens": 4, "totalTokens": 7})
        self.assertEqual(listed, show)


if __name__ == "__main__":
    unittest.main()
