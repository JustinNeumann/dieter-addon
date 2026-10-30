"""Unit tests of bridge/dreamfall_view.py, the bridge's live view of Dreamfall boards: decoding the kv watch, the cache
of stage cards, the state's "dreamfall" block and the trail. Dreamfall itself is tested in host/…/DreamfallTest.kt.
Run: python3 -m unittest discover -s dieter/tests -p 'dreamfall_view_test.py'   (against a daemon: e2e/dreamfall-api.spec.mjs)"""
import base64
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bridge"))
import dreamfall_view as df  # noqa: E402


def entry(key, *values, deleted=False):
    enc = lambda v: base64.b64encode(json.dumps(v).encode()).decode()
    return {"namespace": df.NS, "key": key, "revision": "r", "deleted": deleted, "valueJson": enc(values[0]) if values else "",
            "versions": [{"valueJson": enc(v)} for v in values]}


PIPE = {"id": "df_0123456789", "name": "Dreamfall", "project_id": "p_1", "goal": "Ideas for the house", "categories": ["Energy", "Heating"],
        "per_category": 3, "enabled": True, "agent": {"provider": "claude-code", "model": "opus", "effort": "high"}, "timezone": "Europe/Berlin",
        "stages": [{"role": "enumeration", "name": "Enumeration", "board_id": "b_e", "actor": "agent", "cron": "0 2 * * *", "run_title": "Dreamfall · Enumeration run", "schedule_id": "sch_e"},
                   {"role": "navigation", "name": "Navigation", "board_id": "b_n", "actor": "agent", "cron": "0 5 * * *", "run_title": "Dreamfall · Navigation run", "schedule_id": "sch_n"},
                   {"role": "implementation", "name": "Implementation", "board_id": "b_i", "actor": "human"}]}


class Records(unittest.TestCase):
    def test_siblings_merge_events_and_newest_verdict(self):
        a = {"card": "c_1", "events": [{"id": "1", "kind": "created", "at": "2026-10-28T01:00:00Z"}],
             "verdict": {"kind": "release", "at": "2026-10-28T02:00:00Z"}}
        b = {"card": "c_1", "events": [{"id": "1", "kind": "created", "at": "2026-10-28T01:00:00Z"}, {"id": "2", "kind": "judged", "at": "2026-10-28T03:00:00Z"}],
             "verdict": {"kind": "promote", "at": "2026-10-28T03:00:00Z"}, "transferred_to": "c_2"}
        val, _ = df.decode_entry(entry("c.c_1", a, b))
        self.assertEqual([e["id"] for e in val["events"]], ["1", "2"])
        self.assertEqual(val["verdict"]["kind"], "promote")
        self.assertEqual(val["transferred_to"], "c_2")

    def test_deleted_entry(self):
        self.assertEqual(df.decode_entry(entry("p.x", {"id": "x"}, deleted=True))[0], None)


class CacheView(unittest.TestCase):
    def setUp(self):
        self.calls = 0
        self.c = df.Cache(lambda x: x, on_change=self.bump, log=lambda *a: None)

    def bump(self):
        self.calls += 1

    def test_snapshot_published_only_when_caught_up(self):
        self.c._frame({"reset": True})
        self.c._frame({"entries": [entry("p." + PIPE["id"], PIPE)]})
        self.assertEqual(self.c.pipelines(), [])
        self.c._frame({"entries": [entry("c.c_1", {"stage": "enumeration", "events": [{"id": "1", "kind": "created", "at": "2026-10-28T01:00:00Z"}]})], "caughtUp": True})
        self.assertEqual([p["id"] for p in self.c.pipelines()], [PIPE["id"]])
        self.assertGreaterEqual(self.calls, 1)
        self.c._frame({"entries": [entry("c.c_1", deleted=True)]})
        self.assertIsNone(self.c.record("c.c_1"))

    def test_decorate_merges_stage_cards_and_records(self):
        self.c._frame({"reset": True})
        self.c._frame({"entries": [entry("p." + PIPE["id"], PIPE), entry("c.c_2", {"stage": "navigation", "source": "c_1", "verdict": {"kind": "promote", "rationale": "good", "at": "2026-10-28T05:00:00Z"},
                                                                                      "events": [{"id": "e", "kind": "created", "at": "2026-10-28T05:00:00Z"}]}),
                                   entry("r.c_run", {"pipeline": PIPE["id"], "stage": "navigation", "started_at": "2026-10-28T05:00:00Z", "status": "done"})], "caughtUp": True})
        self.c.cards = {"b_n": {"todo": (0, [{"id": "c_2", "board_id": "b_n", "lane": "todo", "updated_at": "2026-10-28T05:00:00Z"}])}}
        data = {"ok": True, "cards": [{"id": "c_2", "board_id": "b_n", "lane": "review", "updated_at": "2026-10-28T04:00:00Z", "orphaned": False},
                                      {"id": "c_other", "board_id": "b_x", "lane": "todo"}]}
        out = self.c.decorate(data)
        cards = {c["id"]: c for c in out["cards"]}
        self.assertEqual(cards["c_2"]["lane"], "todo")              # the newer copy wins
        self.assertIn("c_other", cards)
        self.assertEqual(out["dreamfall"]["records"]["c_2"]["verdict"]["kind"], "promote")
        self.assertIsNone(out["dreamfall"]["records"]["c_2"]["source_verdict"])   # its source c_1 has no record here
        self.assertEqual(out["dreamfall"]["runs"][PIPE["id"]][0]["status"], "done")
        self.assertTrue(out["dreamfall"]["ready"])

    def test_apply_local_moves_and_adds_without_listing(self):
        self.c.records = {"p." + PIPE["id"]: PIPE}
        self.c.cards = {"b_n": {"todo": (1, [{"id": "c_2", "board_id": "b_n", "lane": "todo", "updated_at": "2026-10-28T05:00:00Z"}]), "done": (1, [])},
                        "b_i": {"todo": (1, [])}}
        self.assertEqual([c["id"] for c in self.c.cards_of("b_n")], ["c_2"])
        self.assertIsNone(self.c.cards_of("b_x"))
        new = {"id": "c_3", "board_id": "b_i", "lane": "todo", "updated_at": "2026-10-28T19:00:00Z"}
        self.assertTrue(self.c.apply_local({"c_2": "done"}, [new]))
        self.assertEqual(self.c.cards["b_n"]["todo"][1], [])
        moved = self.c.cards["b_n"]["done"][1][0]
        self.assertEqual(moved["lane"], "done")
        self.assertEqual(self.c.cards["b_i"]["todo"][1], [new])
        self.assertGreaterEqual(self.calls, 1)
        out = self.c.decorate({"ok": True, "cards": [{"id": "c_2", "board_id": "b_n", "lane": "todo", "updated_at": "2026-10-28T05:00:00Z"}]})
        cards = {c["id"]: c for c in out["cards"]}
        self.assertEqual(cards["c_2"]["lane"], "done")             # the local move wins over the older state copy
        self.assertIn("c_3", cards)
        self.assertFalse(self.c.apply_local({"c_2": "done"}, []))  # already there: nothing to do

    def test_feed_takes_stage_lanes_from_the_project_list(self):
        self.c.records = {"p." + PIPE["id"]: PIPE}
        self.c.cards = {"b_gone": {"todo": (1, [])}}
        old = [{"id": f"c_d{i}", "board_id": "b_n", "lane": "done", "phase_changed_at": f"2026-10-{1 + i // 24:02}T{i % 24:02}:00:00Z"} for i in range(80)]
        cards = [{"id": "c_1", "board_id": "b_e", "lane": "todo"}, {"id": "c_2", "board_id": "b_n", "lane": "running"},
                 {"id": "c_3", "board_id": "b_x", "lane": "todo"}, *old]
        self.assertTrue(self.c.feed("p_1", cards))
        self.assertEqual([c["id"] for c in self.c.cards_of("b_e")], ["c_1"])
        self.assertEqual(self.c.cards_of("b_i"), [])                       # read, empty
        self.assertNotIn("b_x", self.c.cards)                               # not a stage board
        self.assertNotIn("b_gone", self.c.cards)                            # no longer a stage board
        done = self.c.cards["b_n"]["done"][1]
        self.assertEqual(len(done), df.Cache.LANE_TAIL + 20)
        self.assertEqual(done[-1]["id"], "c_d79")                           # the newest stay
        self.assertFalse(self.c.feed("p_1", cards))                         # nothing new
        self.assertFalse(self.c.feed("p_other", cards))                     # another project's list
        self.assertEqual(self.c.stage_projects(), {"p_1"})

    def test_put_cards_replaces_moves_and_drops(self):
        self.c.records = {"p." + PIPE["id"]: PIPE}
        self.c.feed("p_1", [{"id": "c_1", "board_id": "b_e", "lane": "todo"}, {"id": "c_2", "board_id": "b_n", "lane": "todo"}])
        calls = self.calls
        self.assertTrue(self.c.put_cards([{"id": "c_1", "board_id": "b_e", "lane": "review"}, {"id": "c_9", "board_id": "b_x", "lane": "todo"}], ["c_2", "c_unknown"]))
        self.assertEqual([(c["id"], c["lane"]) for c in self.c.cards_of("b_e")], [("c_1", "review")])
        self.assertEqual(self.c.cards_of("b_n"), [])
        self.assertNotIn("b_x", self.c.cards)
        self.assertGreater(self.calls, calls)
        self.assertFalse(self.c.put_cards([{"id": "c_1", "board_id": "b_e", "lane": "review"}], ["c_unknown"]))   # same copy: no change

    def test_new_pipeline_asks_for_its_boards(self):
        asked = []
        c = df.Cache(lambda x: x, on_change=lambda: None, on_boards=lambda: asked.append(1), log=lambda *a: None)
        c._frame({"reset": True})
        c._frame({"entries": [entry("c.c_1", {"stage": "enumeration"})], "caughtUp": True})
        self.assertEqual(asked, [])
        c._frame({"entries": [entry("p." + PIPE["id"], PIPE)]})
        self.assertEqual(asked, [1])
        c._frame({"entries": [entry("c.c_2", {"stage": "navigation"})]})   # an idea's record: the boards stay
        self.assertEqual(asked, [1])

    def test_known_copies_name_their_sources(self):
        self.c.records = {"p." + PIPE["id"]: PIPE}
        self.c.cards = {"b_n": {"todo": (1, [{"id": "c_2", "board_id": "b_n", "initial_prompt": "brief\n<!-- dreamfall-source: c_1 -->\n"}])},
                        "b_i": {"todo": (1, [{"id": "c_3", "board_id": "b_i", "initial_prompt": "brief\n<!-- dreamfall-source: c_1 -->\n"
                                                                                       "more\n<!-- dreamfall-source: c_2 -->\n"}]), "done": (1, [])},
                        "b_e": {}}
        self.assertEqual(self.c.known_copies(), {"b_n": {"c_1": "c_2"}, "b_i": {"c_1": "c_3", "c_2": "c_3"}})   # b_e: not read yet

    def test_ideas_are_counted_once_not_per_copy(self):
        pid = PIPE["id"]
        recs = {"p." + pid: PIPE,
                "c.c_1": {"pipeline": pid, "stage": "enumeration", "transferred_to": "c_2"},          # promoted ...
                "c.c_2": {"pipeline": pid, "stage": "navigation", "source": "c_1", "transferred_to": "c_3"},
                "c.c_3": {"pipeline": pid, "stage": "implementation", "source": "c_2"},             # ... and picked: 1 idea
                "c.c_4": {"pipeline": pid, "stage": "enumeration"},                                  # released: 1 idea
                "c.c_5": {"pipeline": pid, "stage": "implementation", "created_by": {"actor": "human"}},  # added by hand: 1 idea
                "c.c_9": {"pipeline": "df_other00000", "stage": "enumeration"}}
        self.c.records = recs
        out = self.c.decorate({"ok": True, "cards": []})
        self.assertEqual(out["dreamfall"]["ideas"], {pid: 3, "df_other00000": 1})

    def test_detail_chain_and_trail(self):
        recs = {"c.c_1": {"stage": "enumeration", "transferred_to": "c_2", "events": [{"id": "a", "kind": "created", "at": "2026-10-28T02:00:00Z"},
                                                                                       {"id": "b", "kind": "transferred", "to": "c_2", "at": "2026-10-28T05:00:00Z"}]},
                "c.c_2": {"stage": "navigation", "source": "c_1", "transferred_to": "c_3", "events": [{"id": "c", "kind": "created", "from": "c_1", "at": "2026-10-28T05:00:00Z"}]},
                "c.c_3": {"stage": "implementation", "source": "c_2", "events": [{"id": "d", "kind": "created", "from": "c_2", "at": "2026-10-28T19:00:00Z"}]}}
        self.c.records = recs
        recs["c.c_1"]["verdict"] = {"kind": "promote", "rationale": "good", "at": "2026-10-28T05:00:00Z"}
        out = self.c.decorate({"ok": True, "cards": [{"id": "c_2", "board_id": "b_n", "lane": "todo"}]})
        self.assertEqual(out["dreamfall"]["records"]["c_2"]["source_verdict"]["rationale"], "good")   # the copy shows why it came
        d = self.c.detail("c_2", [{"id": "c_2", "title": "Idea", "initial_prompt": "brief"}])
        self.assertEqual([n["card"] for n in d["chain"]], ["c_1", "c_2", "c_3"])
        self.assertTrue(d["chain"][1]["self"])
        self.assertEqual([e["id"] for e in d["events"]], ["a", "b"])   # a copy's "created" duplicates its source's "transferred"
        self.assertEqual(d["brief"], "brief")


if __name__ == "__main__":
    unittest.main()
