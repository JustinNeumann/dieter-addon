"""Unit tests of the bridge's State against the fake `dieter` CLI of bridge_poll_test: a card or chat created through
the bridge is in the state at once and stays there until a poll that started after the create has read it.
Run: python3 -m unittest discover -s dieter/tests -p 'state_test.py'"""
import os
import sys
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bridge_poll_test import PIPE, FakeCli, br, card  # noqa: E402


def created(cid, pid="p_a", board="b_a", kind="card", **kw):
    """What the create handler puts into the state: the `card create` answer through norm_card."""
    raw = {"id": cid, "title": cid, "projectId": pid, "boardId": board if kind == "card" else None, "lane": "running" if kind == "card" else None,
           "ownerDaemonId": "d_here", "runtime": "idle", "scope": "board" if kind == "card" else "chat"}
    return {**br.norm_card(raw, kind), **kw}


class AddedCards(unittest.TestCase):
    def setUp(self):
        self.cli = FakeCli()
        self.state = br.State()
        self.df = br.dreamfall_view.Cache(br.norm_card, on_change=lambda: None, log=lambda *a: None)
        self.df.records = {"p." + PIPE["id"]: PIPE}
        for p in (mock.patch.object(br, "run", self.cli), mock.patch.object(br, "STATE", self.state),
                  mock.patch.object(br, "DREAMFALL", self.df)):
            p.start()
            self.addCleanup(p.stop)
        self.state.poll_fast()
        self.cli.kinds()

    def ids(self, key="cards"):
        return [c["id"] for c in self.state.snapshot()[1][key]]

    def test_shown_at_once_without_waiting_for_a_poll(self):
        rev = self.state.rev
        self.state.add(created("c_new", runtime="running"))
        self.assertEqual(self.ids()[0], "c_new")
        self.assertGreater(self.state.rev, rev)
        self.assertEqual(self.cli.kinds(), [])                  # no CLI call before the page has it
        self.assertNotIn("c_new", self.ids("chats"))

    def test_the_next_poll_reads_only_the_new_card(self):
        self.cli.cards["p_a"].append(card("c_new", "p_a", "b_a", "running", title="Daemon's title"))
        self.state.add(created("c_new", runtime="running"))
        self.assertTrue(self.state.cheap())
        self.state.poll_fast()
        self.assertEqual(self.cli.kinds(), ["card show"])
        cards = {c["id"]: c for c in self.state.snapshot()[1]["cards"]}
        self.assertEqual(cards["c_new"]["title"], "Daemon's title")
        self.assertEqual(self.state.added, {})

    def test_a_poll_that_started_before_keeps_it(self):
        fetching, release = threading.Event(), threading.Event()
        fetch_all = self.state.fetch_all

        def slow_fetch():
            out = fetch_all()            # the daemon answered before the create
            fetching.set()
            release.wait(5)
            return out
        self.state.fetch_all = slow_fetch
        self.state.refresh()
        t = threading.Thread(target=self.state.poll_fast)
        t.start()
        fetching.wait(5)
        self.state.add(created("c_new"))
        release.set()
        t.join(5)
        self.assertIn("c_new", self.ids())
        self.assertIn("c_new", self.state.added)

    def test_a_later_poll_that_did_not_read_it_keeps_it(self):
        self.state.add(created("c_new"))
        self.state.want_ids = set()                         # the re-read of c_new is still to come
        self.cli.cards["p_a"][0]["lane"] = "review"
        self.state.refresh(ids=["c_a1"])
        self.state.want_ids.discard("c_new")
        self.state.poll_fast()
        self.assertEqual(self.cli.kinds(), ["card show"])   # c_a1 only
        self.assertIn("c_new", self.ids())
        self.assertIn("c_new", self.state.added)

    def test_gone_once_a_later_poll_read_it_and_the_daemon_has_no_such_card(self):
        self.state.add(created("c_new"))
        self.state.poll_fast()                              # `card show c_new`: not found (e.g. deleted meanwhile)
        self.assertNotIn("c_new", self.ids())
        self.assertEqual(self.state.added, {})

    def test_a_chat_goes_to_the_chats(self):
        self.state.add(created("c_chat2", kind="chat", runtime="running"))
        self.assertEqual(self.ids("chats")[0], "c_chat2")
        self.assertNotIn("c_chat2", self.ids())

    def test_no_duplicate_when_the_state_already_has_it(self):
        self.state.add(created("c_a1", runtime="running"))
        self.assertEqual(self.ids().count("c_a1"), 1)

    def test_patches_still_wait_for_their_card(self):
        """The created-card overlay must not disturb the patch overlay (both decide with the same poll)."""
        self.state.add(created("c_new"))
        self.state.patch("c_a1", lane="done")
        self.state.poll_fast()
        cards = {c["id"]: c for c in self.state.snapshot()[1]["cards"]}
        self.assertEqual(cards["c_a1"]["lane"], "todo")     # read from the (unchanged) fake daemon: the patch ended
        self.assertNotIn("c_a1", self.state.patches)


if __name__ == "__main__":
    unittest.main()
