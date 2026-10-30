"""The bridge's live view of Dreamfall boards (until the state moves to the operator, docs/PLAN.md Step 2, area 9).

Dreamfall itself (pipelines, the person's actions and the agents' `dreamfall` command) is the operator's since
0.6.431.19 (host/…/operator/Dreamfall.kt). What stays here reads what it writes: every Dreamfall record through one
`dieter kv watch` subprocess, the cards of the stage boards (fed by the bridge's polls), the "dreamfall" block of the
state and the trail of a card. Records live in Dieter's account-wide kv store, namespace "dreamfall":

  p.<pipeline id>       the pipeline: name, project, goal, categories, stages (role, board, actor, schedule), agent
  c.<card id>           an idea card: stage, category, source card, transferred_to, verdict, created_by, events (trail)
  r.<card id>           a run of a stage's agent (its schedule card): status, times, counts, note
  l.<pipeline>.<role>   the latest run of a stage
"""
import base64
import datetime
import json
import os
import re
import shutil
import subprocess
import threading
import time

NS = "dreamfall"
DIETER = os.environ.get("DIETER_BIN") or shutil.which("dieter") or "/usr/local/bin/dieter"
MAX_EVENTS = 80            # per card record (kv values are at most 32 KiB)
CARD_RE = re.compile(r"^c_[A-Za-z0-9_-]{6,60}$")
MARKER_RE = re.compile(r"dreamfall-source: ([A-Za-z0-9_-]+)")   # the line in a copy's brief that names its source card
# The Navigation agent's default judgement for a new board (the page's form): the same text as Dreamfall.kt's.
DEFAULT_NAVIGATION_LOGIC = """Promote an idea only when all of these hold:
- clear, concrete value (money, safety, comfort, time, joy), not filler;
- realistic with what exists (or one cheap addition); the ingredients it names are real (check where cheap);
- genuinely new: not already done in the project and not a duplicate of an idea that already passed
  (among near-duplicates in this batch promote only the best one);
- acceptable risk: nothing unsafe without a fallback.
Be selective: about one idea in four should pass. When in doubt, release."""


class DfError(Exception):
    def __init__(self, msg, code=1):
        super().__init__(msg)
        self.code = code


def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_ts(s):
    try:
        return datetime.datetime.fromisoformat(str(s).replace("Z", "+00:00")).timestamp()
    except (TypeError, ValueError):
        return 0.0


def cli(args, timeout=60, parse=True):
    try:
        p = subprocess.run([DIETER, *args], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise DfError(f"dieter {' '.join(args[:2])}: timeout")
    if p.returncode != 0:
        raise DfError((p.stderr or p.stdout).strip()[:500] or f"dieter {' '.join(args[:2])}: exit {p.returncode}")
    if not parse:
        return p.stdout
    out = p.stdout.strip()
    if not out or out == "null":
        return None
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return out


def _b64json(s):
    try:
        return json.loads(base64.b64decode(s or "") or b"null")
    except Exception:
        return None


def merge_versions(selected, versions):
    """Concurrent writes from two daemons leave causal siblings; the trail must not lose an event, so card records
    are merged: events united by id, the newest verdict, the first transfer target. Other records keep the value
    the store selected."""
    vals = [v for v in versions if isinstance(v, dict)]
    if not isinstance(selected, dict) or len(vals) < 2 or "events" not in selected:
        return selected
    out = dict(selected)
    seen, events = set(), []
    for v in vals:
        for e in v.get("events") or []:
            if isinstance(e, dict) and e.get("id") not in seen:
                seen.add(e.get("id"))
                events.append(e)
    events.sort(key=lambda e: (parse_ts(e.get("at")), e.get("id") or ""))
    out["events"] = events[-MAX_EVENTS:]
    verdicts = [v["verdict"] for v in vals if isinstance(v.get("verdict"), dict)]
    if verdicts:
        out["verdict"] = max(verdicts, key=lambda d: parse_ts(d.get("at")))
    for k in ("transferred_to", "source", "category"):
        if not out.get(k):
            out[k] = next((v.get(k) for v in vals if v.get(k)), out.get(k))
    return out


def decode_entry(e):
    """kv entry (get/list/watch) -> (value or None when deleted, revision)."""
    if not isinstance(e, dict):
        return None, ""
    if e.get("deleted"):
        return None, e.get("revision") or ""
    selected = _b64json(e.get("valueJson"))
    versions = [_b64json(v.get("valueJson")) for v in e.get("versions") or [] if isinstance(v, dict)]
    return merge_versions(selected, versions), e.get("revision") or ""


def norm(c):
    """`card show` (camelCase, under "card") and `card list` (snake_case) -> the fields Dreamfall uses."""
    if isinstance(c, dict) and isinstance(c.get("card"), dict):
        c = c["card"]
    c = c or {}
    g = lambda *ks: next((c[k] for k in ks if k in c and c[k] is not None), None)
    return {"id": c.get("id"), "title": c.get("title") or "", "board_id": g("board_id", "boardId"), "lane": c.get("lane"),
            "project_id": g("project_id", "projectId"), "prompt": g("initial_prompt", "initialPrompt") or "",
            "provider": c.get("provider"), "model": c.get("model"), "effort": c.get("effort"),
            "archived": bool(g("archived_at", "archivedAt", "archived")), "started": bool(g("initial_prompt_sent_at", "initialPromptSentAt")),
            "checkout_id": g("checkout_id", "checkoutId"), "updated_at": g("updated_at", "updatedAt"),
            "phase_changed_at": g("phase_changed_at", "phaseChangedAt"), "created_at": g("created_at", "createdAt")}


def card_show(cid):
    if not CARD_RE.match(cid or ""):
        raise DfError(f"not a card id: {cid!r}")
    return norm(cli(["card", "show", cid], timeout=30))


def stage_of_board(p, board_id):
    return next((s for s in p.get("stages") or [] if s.get("board_id") == board_id), None)


class Cache:
    """Live view for the page: every Dreamfall record (one `dieter kv watch` subprocess) and the cards of the stage
    boards. The project card lists stop at 200 cards and a busy pipeline outgrows that in a week, so the bridge lists
    the project of a pipeline whole and hands it over (feed): every lane of the stage boards, of Review and Done the
    newest ideas (moves append at the end of a lane). Changed cards come one by one (put_cards). The cache made no
    `card list` calls of its own: in 0.6.429.42-.45 it listed every lane of every stage board every 10 s, and since the
    daemon reads all card files for each list whatever its filters, that alone kept a Pi's daemon busy."""
    LANE_TAIL = 40          # newest released/transferred ideas per stage shown on the page

    def __init__(self, norm_card, on_change, on_boards=None, log=print):
        self.norm_card, self.on_change, self.log = norm_card, on_change, log
        self.on_boards = on_boards or (lambda: None)   # the stage boards changed: list their project again
        self.lock = threading.Lock()
        self.records = {}           # key -> value (live)
        self.staging = None         # records of a watch snapshot that has not caught up yet
        self.caught_up = False
        self.cards = {}             # board id -> {lane: (fetched at, [cards])}
        self.version = 0
        self.changed = threading.Condition(self.lock)

    def start(self):
        threading.Thread(target=self._watch, daemon=True, name="dreamfall-kv").start()

    # -- records
    def _watch(self):
        backoff = 2
        while True:
            try:
                proc = subprocess.Popen([DIETER, "kv", "watch", "--namespace", NS], stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, bufsize=1)
                for line in proc.stdout:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        frame = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    self._frame(frame)
                    backoff = 2
                rc = proc.wait()
                err = (proc.stderr.read() or "").strip()[:300]
                self.log(f"dreamfall: kv watch ended ({rc}) {err}")
            except Exception as e:
                self.log(f"dreamfall: kv watch: {e!r}")
            time.sleep(backoff)
            backoff = min(backoff * 2, 60)

    def _frame(self, frame):
        changed = False
        with self.lock:
            boards = self._stage_boards()
            if frame.get("reset"):
                self.staging = {}
            target = self.staging if self.staging is not None else self.records
            for e in frame.get("entries") or []:
                val, _ = decode_entry(e)
                key = e.get("key")
                if not key:
                    continue
                if val is None:
                    changed |= target.pop(key, None) is not None
                elif target.get(key) != val:
                    target[key] = val
                    changed = True
            if frame.get("caughtUp") and self.staging is not None:
                changed = changed or self.staging != self.records or not self.caught_up
                self.records, self.staging, self.caught_up = self.staging, None, True
            if changed and self.staging is None:
                self.version += 1
                self.changed.notify_all()
            boards_changed = self.staging is None and self._stage_boards() != boards
        if changed and self.staging is None:
            self.on_change()
        if boards_changed:          # a pipeline appeared (also: the snapshot arrived after the first poll) or went
            self.on_boards()

    def wait_for(self, key, test, timeout=4.0):
        """After a write through the bridge: wait until the watch delivered it (test(value) is true), so the next
        state already shows it."""
        end = time.time() + timeout
        with self.lock:
            while not test(self.records.get(key)):
                left = end - time.time()
                if left <= 0:
                    return False
                self.changed.wait(left)
        return True

    def pipelines(self):
        with self.lock:
            return [v for k, v in self.records.items() if k.startswith("p.") and isinstance(v, dict) and v.get("id")]

    def record(self, key):
        with self.lock:
            return self.records.get(key)

    def put_local(self, key, value):
        """A write made through the bridge, shown before the watch delivers it."""
        with self.lock:
            if value is None:
                self.records.pop(key, None)
            else:
                self.records[key] = value
            self.version += 1
        self.on_change()

    # -- stage boards
    def _pipelines(self):
        return [v for k, v in self.records.items() if k.startswith("p.") and isinstance(v, dict) and v.get("id")]

    def _stage_boards(self):
        return {s["board_id"] for p in self._pipelines() for s in p.get("stages") or [] if s.get("board_id")}

    def stage_projects(self):
        """The projects with stage boards: the bridge lists them whole and feeds their cards here."""
        with self.lock:
            return {p.get("project_id") for p in self._pipelines() if p.get("project_id")}

    def feed(self, project_id, cards):
        """Every card of a project as `card list` returned it (whole, see stage_projects): the lanes of its stage
        boards are taken from it. Returns whether anything changed."""
        changed = False
        with self.lock:
            stages = {s["board_id"] for p in self._pipelines() if p.get("project_id") == project_id
                      for s in p.get("stages") or [] if s.get("board_id")}
            lanes = {bid: {lane: [] for lane in ("todo", "running", "review", "done")} for bid in stages}
            for c in cards:
                if c.get("lane") in lanes.get(c.get("board_id"), {}):
                    lanes[c["board_id"]][c["lane"]].append(c)
            now = time.time()
            for bid, by_lane in lanes.items():
                for lane, lst in by_lane.items():
                    if lane in ("review", "done"):
                        lst.sort(key=lambda c: parse_ts(c.get("phase_changed_at") or c.get("updated_at")))
                        lst = lst[-(self.LANE_TAIL + 20):]   # the page shows LANE_TAIL ideas; run cards sit there too
                    prev = (self.cards.get(bid) or {}).get(lane)
                    if prev is None or prev[1] != lst:
                        changed = True
                    self.cards.setdefault(bid, {})[lane] = (now, lst)
            known = self._stage_boards()
            for bid in list(self.cards):     # boards that are no longer stages
                if bid not in known:
                    del self.cards[bid]
                    changed = True
            if changed:
                self.version += 1
        if changed:
            self.on_change()
        return changed

    def put_cards(self, cards=(), gone=()):
        """Cards the bridge re-read (`card show`) after a change: on a stage board they replace their old copy (in
        whichever lane it was), gone ones (archived, deleted) leave the cache."""
        with self.lock:
            boards = self._stage_boards()
            cards = [c for c in cards if c.get("board_id") in boards]
            gone = [cid for cid in gone if any(x["id"] == cid for lanes in self.cards.values() for _, lst in lanes.values() for x in lst)]
        changed = self.apply_local(cards=cards) if cards else False
        if gone:
            with self.lock:
                for by_lane in self.cards.values():
                    for lane, (at, lst) in list(by_lane.items()):
                        by_lane[lane] = (at, [x for x in lst if x["id"] not in gone])
                self.version += 1
            self.on_change()
            changed = True
        return changed


    def cards_of(self, board_id):
        """The cached cards of a stage board (every lane), or None when the board has not been read yet."""
        with self.lock:
            lanes = self.cards.get(board_id)
            if not lanes:
                return None
            return [c for (_, cards) in lanes.values() for c in cards]

    def known_copies(self):
        """{board: {source card: its copy}} for the stage boards the cache holds (every marker a brief carries: a copy of
        a copy names both), so the operator's transfer finds a copy made earlier without listing the board."""
        out = {}
        with self.lock:
            for bid, lanes in self.cards.items():
                if not lanes:
                    continue
                copies = out.setdefault(bid, {})
                for _, cards in lanes.values():
                    for c in cards:
                        for src in MARKER_RE.findall(c.get("initial_prompt") or c.get("prompt") or ""):
                            copies.setdefault(src, c["id"])
        return out

    def apply_local(self, lanes=None, cards=None):
        """A change made through the bridge, shown before the boards are read again: lanes = card id -> its lane
        now (the card moves between the cached lane lists of its board), cards = new or re-read cards (replace or
        add). The re-read of the moved cards (`card show`, put_cards) confirms it; this way the person's action is
        in the next state in milliseconds, not after a second round of `card list` calls."""
        changed = False
        with self.lock:
            for c in cards or []:
                bid, lane = c.get("board_id"), c.get("lane")
                if not bid or not lane:
                    continue
                prev = next((x for lst in (self.cards.get(bid) or {}).values() for x in lst[1] if x["id"] == c["id"]), None)
                if prev == c and prev.get("lane") == lane:
                    continue
                self.cards.setdefault(bid, {})
                for l, (at, lst) in list(self.cards[bid].items()):
                    rest = [x for x in lst if x["id"] != c["id"]]
                    if len(rest) != len(lst):
                        self.cards[bid][l] = (at, rest)
                at = (self.cards[bid].get(lane) or (time.time(), []))[0]
                self.cards[bid][lane] = (at, [*(self.cards[bid].get(lane) or (0, []))[1], c])
                changed = True
            for cid, lane in (lanes or {}).items():
                for bid, by_lane in self.cards.items():
                    for l, (at, lst) in list(by_lane.items()):
                        hit = next((x for x in lst if x["id"] == cid), None)
                        if hit is None or l == lane:
                            continue
                        by_lane[l] = (at, [x for x in lst if x["id"] != cid])
                        moved = {**hit, "lane": lane, "phase_changed_at": now_iso(), "updated_at": now_iso()}
                        at2, dest = by_lane.get(lane) or (time.time(), [])
                        by_lane[lane] = (at2, [*[x for x in dest if x["id"] != cid], moved])
                        changed = True
                        break
            if changed:
                self.version += 1
        if changed:
            self.on_change()
        return changed

    # -- what the page gets
    def decorate(self, data):
        """The state plus "dreamfall": pipelines, compact records of the cards on the page, latest runs; the stage
        boards' cards are merged into data["cards"] (newer copy wins)."""
        if not data.get("ok"):
            return data
        with self.lock:
            pipelines = [v for k, v in self.records.items() if k.startswith("p.") and isinstance(v, dict) and v.get("id")]
            stage_boards = {s["board_id"] for p in pipelines for s in p.get("stages") or []}
            extra = [c for bid in stage_boards for lane, (at, cards) in (self.cards.get(bid) or {}).items() for c in cards]
            records = dict(self.records)
        cards = {c["id"]: c for c in data.get("cards") or []}
        for c in extra:
            have = cards.get(c["id"])
            if have is None or parse_ts(c.get("updated_at")) > parse_ts(have.get("updated_at")):
                cards[c["id"]] = {**c, "orphaned": (have or {}).get("orphaned", False)}
        # a card that left a lane is still in the older list of that lane until its next read: keep the newer copy only
        merged = list(cards.values())
        ids = set(cards)
        compact = {}
        for cid in ids:
            r = records.get("c." + cid)
            if isinstance(r, dict):
                v = r.get("verdict") if isinstance(r.get("verdict"), dict) else None
                src = records.get("c." + r["source"]) if r.get("source") else None
                sv = src.get("verdict") if isinstance(src, dict) and isinstance(src.get("verdict"), dict) else None
                compact[cid] = {"source_verdict": {k: sv.get(k) for k in ("kind", "rationale", "score", "actor", "name", "at", "run")} if sv else None,"stage": r.get("stage"), "category": r.get("category") or "", "source": r.get("source"),
                                "transferred_to": r.get("transferred_to"), "created_by": r.get("created_by"),
                                "verdict": {k: v.get(k) for k in ("kind", "rationale", "score", "actor", "name", "at", "run")} if v else None,
                                "events": len(r.get("events") or []), "u": (r.get("events") or [{}])[-1].get("at")}
        runs = {}
        for k, r in records.items():
            if k.startswith("r.") and isinstance(r, dict) and r.get("pipeline"):
                runs.setdefault(r["pipeline"], []).append(r)
        for pid in runs:
            runs[pid] = sorted(runs[pid], key=lambda r: parse_ts(r.get("started_at")))[-12:]
        # one idea, however many stages it reached: count the cards an idea started as (no source), not its copies
        ideas = {}
        for k, r in records.items():
            if k.startswith("c.") and isinstance(r, dict) and r.get("pipeline") and not r.get("source"):
                ideas[r["pipeline"]] = ideas.get(r["pipeline"], 0) + 1
        return {**data, "cards": merged, "dreamfall": {"pipelines": sorted(pipelines, key=lambda p: p.get("created_at") or ""),
                                                        "records": compact, "runs": runs, "ready": self.caught_up,
                                                        "ideas": ideas, "defaults": {"navigation_logic": DEFAULT_NAVIGATION_LOGIC}}}

    def detail(self, cid, cards):
        """Full trail of a card and its provenance chain (source cards back to the first stage, copies forward)."""
        with self.lock:
            records = dict(self.records)
        by_id = {c["id"]: c for c in cards}

        def node(x):
            r = records.get("c." + x) or {}
            c = by_id.get(x) or {}
            return {"card": x, "title": c.get("title") or "", "lane": c.get("lane"), "board_id": c.get("board_id"),
                    "stage": r.get("stage"), "created_by": r.get("created_by"), "created_at": r.get("created_at") or c.get("created_at"),
                    "verdict": r.get("verdict"), "known": bool(c)}
        rec = records.get("c." + cid)
        chain, seen = [], {cid}
        x = (rec or {}).get("source")
        while x and x not in seen and len(chain) < 10:
            seen.add(x)
            chain.insert(0, node(x))
            x = (records.get("c." + x) or {}).get("source")
        chain.append({**node(cid), "self": True})
        x = (rec or {}).get("transferred_to")
        while x and x not in seen and len(chain) < 12:
            seen.add(x)
            chain.append(node(x))
            x = (records.get("c." + x) or {}).get("transferred_to")
        events = []
        for n in chain:
            for e in (records.get("c." + n["card"]) or {}).get("events") or []:
                events.append({**e, "card": n["card"], "card_stage": n["stage"]})
        # one copy's "created" and its source's "transferred" describe the same step: keep the source's
        events = [e for e in events if not (e.get("kind") == "created" and e.get("from") and e["from"] in seen)]
        events.sort(key=lambda e: parse_ts(e.get("at")))
        return {"card": cid, "record": rec, "chain": chain, "events": events, "brief": (by_id.get(cid) or {}).get("initial_prompt")}
