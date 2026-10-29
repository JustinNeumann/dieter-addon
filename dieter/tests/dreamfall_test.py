"""Unit tests of bridge/dreamfall.py that need no daemon: records, titles, settings, prompts and the bridge's cache.
Run: python3 -m unittest discover -s dieter/tests -p 'dreamfall_test.py'   (the flows against a daemon are in e2e/dreamfall.spec.mjs)"""
import base64
import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bridge"))
import dreamfall as df  # noqa: E402


def entry(key, *values, deleted=False):
    enc = lambda v: base64.b64encode(json.dumps(v).encode()).decode()
    return {"namespace": df.NS, "key": key, "revision": "r", "deleted": deleted, "valueJson": enc(values[0]) if values else "",
            "versions": [{"valueJson": enc(v)} for v in values]}


PIPE = {"id": "df_0123456789", "name": "Dreamfall", "project_id": "p_1", "goal": "Ideas for the house", "categories": ["Energy", "Heating"],
        "per_category": 3, "enabled": True, "agent": {"provider": "claude-code", "model": "opus", "effort": "high"}, "timezone": "Europe/Berlin",
        "stages": [{"role": "enumeration", "name": "Enumeration", "board_id": "b_e", "actor": "agent", "cron": "0 2 * * *", "run_title": "Dreamfall · Enumeration run", "schedule_id": "sch_e"},
                   {"role": "navigation", "name": "Navigation", "board_id": "b_n", "actor": "agent", "cron": "0 5 * * *", "run_title": "Dreamfall · Navigation run", "schedule_id": "sch_n"},
                   {"role": "implementation", "name": "Implementation", "board_id": "b_i", "actor": "human"}]}


class Titles(unittest.TestCase):
    def test_category_prefix(self):
        self.assertEqual(df.split_category("[Heating] Shutters as night insulation"), ("Heating", "Shutters as night insulation"))
        self.assertEqual(df.split_category("No category"), ("", "No category"))

    def test_duplicate_key_ignores_category_case_punctuation(self):
        self.assertEqual(df.title_key("[Heating] Shutters as night insulation"), df.title_key("shutters as NIGHT insulation!"))
        self.assertNotEqual(df.title_key("Shutters at night"), df.title_key("Shutters as night insulation"))

    def test_run_cards(self):
        self.assertTrue(df.is_run_card(PIPE, {"board_id": "b_e", "title": "Dreamfall · Enumeration run"}))
        self.assertFalse(df.is_run_card(PIPE, {"board_id": "b_n", "title": "Dreamfall · Enumeration run"}))
        p = json.loads(json.dumps(PIPE))
        p["stages"][0]["old_run_titles"] = ["Ideas · Enumeration run"]
        self.assertTrue(df.is_run_card(p, {"board_id": "b_e", "title": "Ideas · Enumeration run"}))


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

    def test_rerun_events_are_not_duplicated(self):
        rec = {"events": []}
        df.add_events(rec, df.event("transferred", "agent", "Navigation agent", to="c_2"))
        df.add_events(rec, df.event("transferred", "agent", "Navigation agent", to="c_2"))
        df.add_events(rec, df.event("restored", "human", "you"))
        df.add_events(rec, df.event("restored", "human", "you"))
        self.assertEqual([e["kind"] for e in rec["events"]], ["transferred", "restored", "restored"])

    def test_event_text_is_clipped(self):
        e = df.event("judged", "agent", "x", rationale="word " * 400, score=None)
        self.assertLessEqual(len(e["rationale"]), df.MAX_TEXT)
        self.assertNotIn("score", e)


class Settings(unittest.TestCase):
    def test_defaults_and_cron(self):
        st = df.clean_settings({"name": "Ideas", "goal": "g", "categories": "- Energy\n\n* Heating\n"})
        self.assertEqual(st["categories"], ["Energy", "Heating"])
        self.assertEqual(st["enumeration_cron"], "0 2 * * *")
        self.assertEqual(st["navigation_cron"], "0 5 * * *")
        self.assertEqual(df.cron_of("4:45"), "45 4 * * *")
        self.assertEqual(df.hhmm_of("45 4 * * *"), "04:45")

    def test_refusals(self):
        for bad in ({"name": "", "categories": "a"}, {"name": "x"}, {"name": "x", "categories": "a", "timezone": "Not a zone!"},
                    {"name": "x", "categories_file": "../secret"}, {"name": "x", "categories": "a", "enumeration_time": "25:00"}):
            with self.assertRaises(df.DfError, msg=bad):
                df.clean_settings(bad)

    def test_update_keeps_times(self):
        st = df.clean_settings({"name": "Renamed"}, PIPE)
        self.assertEqual(st["enumeration_cron"], "0 2 * * *")
        self.assertEqual(st["categories"], ["Energy", "Heating"])


class Prompts(unittest.TestCase):
    def test_enumeration_prompt(self):
        t = df.enumeration_prompt(PIPE)
        for s in ("dreamfall run begin", "dreamfall ideas", "dreamfall add --category", "at least 3 new ideas", "- Energy", "dreamfall run end",
                  "Ideas for the house", "do not touch the Navigation or Implementation boards"):
            self.assertIn(s, t)

    def test_navigation_prompt_steps_and_mail(self):
        t = df.navigation_prompt(PIPE)
        self.assertIn("dreamfall promote <card>", t)
        self.assertIn("never create, move or start cards there", t)
        self.assertIn("\n7. Report briefly", t)
        self.assertNotIn("mail send", t)
        t = df.navigation_prompt({**PIPE, "mail": True})
        self.assertIn("dieter mail send", t)
        self.assertIn("\n8. Report briefly", t)

    def test_schedule_args(self):
        a = df.schedule_args({**PIPE, "enabled": False}, "navigation")
        self.assertIn("--enabled=false", a)
        self.assertEqual(a[a.index("--cron") + 1], "0 5 * * *")
        self.assertEqual(a[a.index("--open-card") + 1], "skip_if_open")
        self.assertEqual(a[a.index("--title") + 1], "Dreamfall · Navigation run")


class NavigationLogic(unittest.TestCase):
    def test_default_for_a_new_board(self):
        st = df.clean_settings({"name": "x", "categories": "a"})
        self.assertEqual(st["navigation_logic"], df.DEFAULT_NAVIGATION_LOGIC)

    def test_custom_logic_reaches_the_prompt_quoted(self):
        st = df.clean_settings({"name": "x", "categories": "a", "navigation_logic": "Only ideas under 50 EUR.\r\n\r\n\r\nNever lighting."})
        self.assertEqual(st["navigation_logic"], "Only ideas under 50 EUR.\n\nNever lighting.")
        t = df.navigation_prompt({**PIPE, "navigation_logic": st["navigation_logic"]})
        self.assertIn("   > Only ideas under 50 EUR.\n   >\n   > Never lighting.", t)
        self.assertNotIn("about one idea in four", t)
        self.assertIn("it decides, not\n   your own taste", t)

    def test_settings_keep_the_logic_unless_given(self):
        cur = {**PIPE, "navigation_logic": "Keep me."}
        self.assertEqual(df.clean_settings({"name": "Renamed"}, cur)["navigation_logic"], "Keep me.")
        self.assertEqual(df.clean_settings({"navigation_logic": "New."}, cur)["navigation_logic"], "New.")

    def test_older_records_get_the_default_with_their_pass_hint(self):
        self.assertIn("about one idea in five should pass", df.navigation_logic({**PIPE, "pass_hint": "about one idea in five"}))
        self.assertEqual(df.navigation_logic(PIPE), df.DEFAULT_NAVIGATION_LOGIC)

    def test_refusals(self):
        for bad in ("", "   \n ", "x" * (df.MAX_LOGIC + 1)):
            with self.assertRaises(df.DfError):
                df.clean_settings({"name": "x", "categories": "a", "navigation_logic": bad})

    def test_topic_is_optional(self):
        st = df.clean_settings({"name": "x", "categories": "a"})
        self.assertEqual(st["goal"], "")
        self.assertIn("improvements of this project", df.enumeration_prompt({**PIPE, "goal": ""}))


class ImplementationExtras(unittest.TestCase):
    def test_nothing_by_default(self):
        st = df.clean_settings({"name": "x", "categories": "a"})
        self.assertEqual((st["implementation_instructions"], st["implementation_mail"]), ("", False))
        self.assertEqual(df.implementation_extras({**PIPE, **st}), ("", []))
        self.assertNotIn("For every implementation", df.copy_brief("idea", "Picked", "Navigation", "you", "", "c_1"))

    def test_instructions_and_mail_go_on_picked_cards(self):
        st = df.clean_settings({"name": "x", "categories": "a", "implementation_instructions": "Add a chart.\r\n\r\n\r\nMail it.", "implementation_mail": True})
        self.assertEqual(st["implementation_instructions"], "Add a chart.\n\nMail it.")
        text, args = df.implementation_extras({**PIPE, **st})
        self.assertEqual(args, ["--mail"])
        brief = df.copy_brief("**Idea:** X", "Picked", "Navigation", "you", "now", "c_1", text)
        self.assertIn("## For every implementation on this Dreamfall board\n\nAdd a chart.\n\nMail it.", brief)
        self.assertIn("dieter mail send", brief)
        self.assertTrue(brief.rstrip().endswith("<!-- dreamfall-source: c_1 -->"))   # the marker stays last

    def test_mail_without_instructions_still_explains_how(self):
        text, args = df.implementation_extras({**PIPE, "implementation_mail": True})
        self.assertEqual(args, ["--mail"])
        self.assertIn("vega-lite", text)

    def test_settings_keep_them_unless_given(self):
        cur = {**PIPE, "implementation_instructions": "Keep.", "implementation_mail": True}
        st = df.clean_settings({"name": "Renamed"}, cur)
        self.assertEqual((st["implementation_instructions"], st["implementation_mail"]), ("Keep.", True))
        st = df.clean_settings({"implementation_instructions": "", "implementation_mail": False}, cur)
        self.assertEqual((st["implementation_instructions"], st["implementation_mail"]), ("", False))
        with self.assertRaises(df.DfError):
            df.clean_settings({"name": "x", "categories": "a", "implementation_instructions": "x" * (df.MAX_LOGIC + 1)})


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

    def test_transfer_scans_known_cards_instead_of_listing(self):
        calls = []
        def fake_cli(args, timeout=60, parse=True):
            calls.append(args[:2])
            if args[:2] == ["kv", "get"]:
                raise df.DfError("not found")
            if args[:2] == ["kv", "put"]:
                return {}
            if args[:2] == ["card", "move"]:
                return ""
            raise AssertionError(args)
        src = {"id": "c_1", "title": "Energy: idea", "board_id": PIPE["stages"][1]["board_id"], "lane": "todo", "prompt": "brief", "provider": None, "model": None, "effort": None, "checkout_id": None}
        copy = {"id": "c_9", "board_id": PIPE["stages"][2]["board_id"], "lane": "todo", "initial_prompt": f"brief\n<!-- {df.MARKER} c_1 -->"}
        with mock.patch.object(df, "cli", fake_cli):
            new_id, done = df.transfer(PIPE, src, "implementation", "human", "you", "Picked", known=lambda bid: [copy])
        self.assertEqual(new_id, "c_9")                          # the copy the cache knows is reused, nothing created
        self.assertNotIn(["card", "list"], calls)
        self.assertNotIn(["card", "create"], calls)
        self.assertIn(["card", "move"], calls)
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
