"""Unit tests of what the bridge still does for titles and settings: Home Assistant's /api/card asks the operator
whether Ollama names new tasks (the renaming itself is the operator's, host/…/operator/Titles.kt, TitlesTest), and a
new board order reaches the pages after the operator saved it.
Run: python3 -m unittest discover -s dieter/tests -p 'titles_test.py'"""
import os
import sys
import unittest
import urllib.error
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bridge_poll_test import br  # noqa: E402


class Titles(unittest.TestCase):
    def test_ollama_names_new_tasks_while_the_operator_names_a_daemon_for_it(self):
        for answer, on in (((200, b'{"titles_daemon_id": "d_1"}'), True), ((200, b'{"titles_daemon_id": ""}'), False),
                           ((502, b'{"error": "x"}'), False)):
            with mock.patch.object(br, "operator_request", lambda *a, **k: answer):
                self.assertEqual(on, br.ollama_titles())

        def down(*a, **k):
            raise urllib.error.URLError("refused")
        with mock.patch.object(br, "operator_request", down):
            self.assertFalse(br.ollama_titles())

    def test_the_provisional_title_is_the_first_line(self):
        self.assertEqual("Fix the heater", br.first_line_title("\n# Fix the   heater\nin the bathroom"))
        self.assertEqual("New task", br.first_line_title(" "))

    def test_a_new_board_order_reaches_the_pages(self):
        orders = []
        with mock.patch.object(br.STATE, "reorder_boards", orders.append):
            br.after_operator(("boards", "order"), {"ok": True, "board_order": ["b2", "b1"]})
            br.after_operator(("prompts",), {"board_order": ["x"]})
        self.assertEqual([["b2", "b1"]], orders)


if __name__ == "__main__":
    unittest.main()
