"""Unit tests of the bridge's titles for conversations the new page creates (POST /api/titles), and what it does after
the operator changed the settings.
Run: python3 -m unittest discover -s dieter/tests -p 'titles_test.py'"""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bridge_poll_test import br  # noqa: E402


def settings(model):
    return {"title_model": model, "worktree_retention_days": 14, "board_order": []}


class Titles(unittest.TestCase):
    def test_after_the_operator_changed_settings_the_bridge_follows(self):
        # titles_daemon_id and the settings themselves are the operator's now (host/…/operator/Settings.kt, SettingsTest)
        br.HOUSEKEEPING_WAKE.clear()
        br.TITLER["at"] = 123
        with mock.patch.object(br, "settings", lambda: settings("ollama")):
            br.after_operator(("settings",), settings("openai"), {})
            self.assertEqual(0, br.TITLER["at"])                        # the entity is looked up again
            self.assertFalse(br.HOUSEKEEPING_WAKE.is_set())             # same retention: no sweep
            br.after_operator(("settings",), {**settings("openai"), "worktree_retention_days": 30}, {})
            self.assertTrue(br.HOUSEKEEPING_WAKE.is_set())
        orders = []
        with mock.patch.object(br.STATE, "reorder_boards", orders.append):
            br.after_operator(("boards", "order"), settings("openai"), {"ok": True, "board_order": ["b2", "b1"]})
            br.after_operator(("prompts",), settings("openai"), {"board_order": ["x"]})
        self.assertEqual([["b2", "b1"]], orders)

    def test_a_named_card_is_renamed_in_the_background_like_one_created_here(self):
        started = []

        class Thread:
            def __init__(self, target, args, daemon):
                started.append((target, args))

            def start(self):
                pass

        with mock.patch.object(br, "title_entity", lambda: "ai_task.ollama"), mock.patch.object(br.threading, "Thread", Thread):
            self.assertEqual({"ok": True}, br.name_later({"kind": "chat", "id": "c_new", "prompt": "Fix it\nnow", "provisional": "Fix it"}))
            self.assertEqual("Fix it", br.TITLE_PENDING.pop("c_new"))
            self.assertEqual([(br.retitle, ("chat", "c_new", "Fix it\nnow", "ai_task.ollama"))], started)
            for bad in ({"kind": "board", "id": "c", "prompt": "p", "provisional": "p"}, {"kind": "card", "id": "../x", "prompt": "p", "provisional": "p"},
                        {"kind": "card", "id": "c", "prompt": " ", "provisional": "p"}):
                self.assertRaises(ValueError, br.name_later, bad)
        with mock.patch.object(br, "title_entity", lambda: None):
            self.assertRaises(ValueError, br.name_later, {"kind": "card", "id": "c_1", "prompt": "p", "provisional": "p"})
        self.assertEqual(1, len(started))


if __name__ == "__main__":
    unittest.main()
