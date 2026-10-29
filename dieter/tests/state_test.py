"""Unit tests of the bridge's State that need no daemon: a card or chat created through the bridge is in the state at once
and stays there until a poll that started after the create has it from the daemon.
Run: python3 -m unittest discover -s dieter/tests -p 'state_test.py'"""
import os
import sys
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bridge"))
import dieter_bridge as bridge  # noqa: E402


def card(cid, kind="card", **kw):
    return {**bridge.norm_card({"id": cid, "title": cid, "projectId": "p_1", "boardId": "b_1", "lane": "todo"}, kind), **kw}


class AddedCards(unittest.TestCase):
    def setUp(self):
        self.st = bridge.State()
        self.listed = {"cards": [card("c_old")], "chats": []}
        self.st.fetch = lambda: ({}, [{"id": "p_1"}], [], [dict(c) for c in self.listed["cards"]], [dict(c) for c in self.listed["chats"]])
        for p in (mock.patch.object(bridge, "local_daemon_info", return_value=("", False)), mock.patch.object(bridge, "ha_event")):
            p.start()
            self.addCleanup(p.stop)
        self.st.poll_fast()

    def ids(self, key="cards"):
        return [c["id"] for c in self.st.snapshot()[1][key]]

    def test_shown_at_once_without_waiting_for_a_poll(self):
        rev = self.st.rev
        self.st.add(card("c_new", lane="running", runtime="running"))
        self.assertEqual(self.ids(), ["c_new", "c_old"])
        self.assertGreater(self.st.rev, rev)
        self.assertEqual(self.ids("chats"), [])

    def test_a_poll_that_started_before_keeps_it(self):
        fetching, release = threading.Event(), threading.Event()
        fetch = self.st.fetch

        def slow_fetch():
            out = fetch()            # the daemon answered before the create
            fetching.set()
            release.wait(5)
            return out
        self.st.fetch = slow_fetch
        t = threading.Thread(target=self.st.poll_fast)
        t.start()
        fetching.wait(5)
        self.st.add(card("c_new"))
        release.set()
        t.join(5)
        self.assertEqual(self.ids(), ["c_new", "c_old"])
        self.assertIn("c_new", self.st.added)

    def test_the_next_poll_has_the_daemons_copy(self):
        self.st.add(card("c_new", runtime="running"))
        self.listed["cards"] = [card("c_new", runtime="idle", title="Daemon's title"), card("c_old")]
        self.st.poll_fast()
        cards = self.st.snapshot()[1]["cards"]
        self.assertEqual([c["id"] for c in cards], ["c_new", "c_old"])
        self.assertEqual(cards[0]["title"], "Daemon's title")
        self.assertEqual(self.st.added, {})

    def test_gone_once_a_later_poll_does_not_list_it(self):
        self.st.add(card("c_new"))
        self.st.poll_fast()          # started after the add: the daemon's answer counts (e.g. deleted meanwhile)
        self.assertEqual(self.ids(), ["c_old"])

    def test_a_chat_goes_to_the_chats(self):
        self.st.add(card("c_chat", kind="chat", runtime="running"))
        self.assertEqual(self.ids("chats"), ["c_chat"])
        self.assertEqual(self.ids(), ["c_old"])

    def test_no_duplicate_when_the_state_already_has_it(self):
        self.listed["cards"] = [card("c_new"), card("c_old")]
        self.st.poll_fast()
        self.st.add(card("c_new", runtime="running"))
        self.assertEqual(self.ids(), ["c_new", "c_old"])


if __name__ == "__main__":
    unittest.main()
