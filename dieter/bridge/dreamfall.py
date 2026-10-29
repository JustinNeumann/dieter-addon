#!/usr/bin/env python3
"""Dreamfall: a multi-stage idea pipeline on Dieter boards.

A Dreamfall board is three ordinary Dieter boards of one project, tied together in order by a pipeline record:

  Enumeration     an agent (nightly schedule) writes new ideas as Todo cards
  Navigation      an agent (later schedule) judges every idea in the Enumeration queue: it transfers the strong ones
                  (a copy on Navigation, the original moves to Done) and releases the rest (Enumeration · Review)
  Implementation  the human gate: only the person picks Navigation cards (copied here as Todo drafts)

Records live in Dieter's account-wide kv store, namespace "dreamfall" (keys allow letters, digits, . _ -):

  p.<pipeline id>       the pipeline: name, project, goal, categories, stages (role, board, actor, schedule), agent
  c.<card id>           an idea card: stage, category, source card, transferred_to, verdict, created_by, events (trail)
  r.<card id>           a run of a stage's agent (its schedule card): status, times, counts, note
  l.<pipeline>.<role>   the latest run of a stage (ordering: Navigation waits for Enumeration)

The same file is the bridge's module (pipelines, human actions, the live cache of records and stage boards) and
the `dreamfall` command the agents call from their run cards (DIETER_CARD_ID): it creates, transfers and releases
cards, keeps the trail and refuses everything a stage's actor may not do (no agent writes to the human stage).
"""
import argparse
import base64
import concurrent.futures
import datetime
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time

NS = "dreamfall"
DIETER = os.environ.get("DIETER_BIN") or shutil.which("dieter") or "/usr/local/bin/dieter"
ROLES = ("enumeration", "navigation", "implementation")
STAGES = {
    "enumeration": {"name": "Enumeration", "actor": "agent", "icon": "auto_awesome", "color": "#8a62c3",
                    "queue": "Waiting for Navigation", "lanes": {"todo": "Queue", "review": "Released", "done": "Transferred"}},
    "navigation": {"name": "Navigation", "actor": "agent", "icon": "balance", "color": "#478dc5",
                   "queue": "Waiting for you", "lanes": {"todo": "Waiting for you", "review": "Parked", "done": "Picked"}},
    "implementation": {"name": "Implementation", "actor": "human", "icon": "lock_person", "color": "#3e9970",
                       "queue": "To build", "lanes": {"todo": "Todo", "running": "Running", "review": "Review", "done": "Done"}},
}
MAX_EVENTS = 80            # per card record (kv values are at most 32 KiB)
MAX_TEXT = 600             # rationale / note length kept in a record
KEY_RE = re.compile(r"^[A-Za-z0-9_.-]{1,200}$")
PID_RE = re.compile(r"^df_[a-f0-9]{10}$")
CARD_RE = re.compile(r"^c_[A-Za-z0-9_-]{6,60}$")
TIME_RE = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")
MARKER = "dreamfall-source:"   # the line in a copy's brief that names its source card (idempotent transfers)
WAIT_EXIT = 75                 # `run begin` of a later stage while the earlier stage's run still works
# The Navigation agent judges by this text; the person sets it per Dreamfall board (New board, Settings).
DEFAULT_NAVIGATION_LOGIC = """Promote an idea only when all of these hold:
- clear, concrete value (money, safety, comfort, time, joy), not filler;
- realistic with what exists (or one cheap addition); the ingredients it names are real (check where cheap);
- genuinely new: not already done in the project and not a duplicate of an idea that already passed
  (among near-duplicates in this batch promote only the best one);
- acceptable risk: nothing unsafe without a fallback.
Be selective: about one idea in four should pass. When in doubt, release."""
MAX_LOGIC = 4000


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


def clip(s, n=MAX_TEXT):
    s = re.sub(r"\s+", " ", str(s or "")).strip()
    return s if len(s) <= n else s[: n - 1].rstrip() + "…"


# --------------------------------------------------------------------------- dieter CLI
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


# --------------------------------------------------------------------------- kv records
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


def kv_get(key):
    try:
        e = cli(["kv", "get", "--namespace", NS, "--key", key], timeout=20)
    except DfError as err:
        if "not found" in str(err).lower():
            return None, ""
        raise
    return decode_entry(e)


def kv_put(key, value, revision=""):
    if not KEY_RE.match(key):
        raise DfError(f"bad kv key {key!r}")
    if isinstance(value, dict) and isinstance(value.get("events"), list):
        value["events"] = value["events"][-MAX_EVENTS:]
    body = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    while len(body.encode()) > 30000 and isinstance(value, dict) and len(value.get("events") or []) > 5:
        value["events"] = value["events"][len(value["events"]) // 4:]
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    fd, path = tempfile.mkstemp(prefix="dreamfall-", suffix=".json")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(body)
        args = ["kv", "put", "--namespace", NS, "--key", key, "--file", path]
        if revision:
            args += ["--revision", revision]
        return cli(args, timeout=20)
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def kv_delete(key):
    val, rev = kv_get(key)
    if val is None or not rev:
        return False
    cli(["kv", "delete", "--namespace", NS, "--key", key, "--revision", rev], timeout=20)
    return True


def kv_update(key, fn, attempts=8):
    """Read-modify-write with the observed revision; a concurrent write is read again and the change re-applied."""
    for i in range(attempts):
        cur, rev = kv_get(key)
        new = fn(json.loads(json.dumps(cur)) if cur is not None else None)
        if new is None:
            return cur
        try:
            kv_put(key, new, rev)
            return new
        except DfError as e:
            msg = str(e).lower()
            if ("conflict" in msg or "revision" in msg or "exists" in msg) and i < attempts - 1:
                time.sleep(0.15 * (i + 1))
                continue
            raise
    raise DfError(f"kv {key}: too many concurrent writes")


def event(kind, actor, name="", **extra):
    e = {"id": secrets.token_hex(4), "kind": kind, "actor": actor, "name": name, "at": now_iso()}
    for k, v in extra.items():
        if v not in (None, ""):
            e[k] = clip(v) if k in ("note", "rationale") else v
    return e


def add_events(rec, *events):
    """Append events, skipping one already there (same kind and same target/source: a rerun of the same step)."""
    have = {(e.get("kind"), e.get("to") or e.get("from") or e.get("run") or "") for e in rec.get("events") or []}
    for e in events:
        sig = (e.get("kind"), e.get("to") or e.get("from") or e.get("run") or "")
        if e["kind"] in ("created", "transferred", "picked") and sig in have:
            continue
        rec.setdefault("events", []).append(e)
        have.add(sig)
    return rec


# --------------------------------------------------------------------------- cards and boards
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


def board_cards(board_id, lane=None, archived=False, limit=2000):
    args = ["card", "list", "--board", board_id, "--format", "json", "--limit", str(limit)]
    if lane:
        args += ["--lane", lane]
    if archived:
        args.append("--archived")
    out = cli(args, timeout=60)
    if isinstance(out, dict):
        out = out.get("cards") or []
    return [norm(c) for c in out or []]


def split_category(title):
    """"[Heating] Shutters as …" -> ("Heating", "Shutters as …"): the category travels in the title, so it shows in
    every Dieter app and survives a copy; the record keeps it too."""
    m = re.match(r"^\s*\[([^\]]{1,60})\]\s*(.*)$", title or "")
    return (m.group(1).strip(), m.group(2).strip()) if m else ("", (title or "").strip())


def title_key(title):
    """Normalized title for duplicate checks: no category, case, punctuation or extra spaces."""
    t = split_category(title)[1].lower()
    t = re.sub(r"[^\w]+", " ", t, flags=re.UNICODE)
    return re.sub(r"\s+", " ", t).strip()


# --------------------------------------------------------------------------- pipelines
def pipeline_key(pid):
    return "p." + pid


def list_pipelines():
    out = cli(["kv", "list", "--namespace", NS, "--prefix", "p."], timeout=20) or {}
    res = []
    for e in out.get("entries") or []:
        v, _ = decode_entry(e)
        if isinstance(v, dict) and v.get("id"):
            res.append(v)
    return res


def load_pipeline(pid):
    if not PID_RE.match(pid or ""):
        raise DfError(f"not a Dreamfall id: {pid!r}")
    p, _ = kv_get(pipeline_key(pid))
    if not p:
        raise DfError(f"Dreamfall {pid} not found")
    return p


def stage(p, role):
    return next((s for s in p.get("stages") or [] if s.get("role") == role), None)


def stage_of_board(p, board_id):
    return next((s for s in p.get("stages") or [] if s.get("board_id") == board_id), None)


def stage_index(p, role):
    return next((i for i, s in enumerate(p.get("stages") or []) if s.get("role") == role), -1)


def pipeline_for_board(board_id, pipelines=None):
    for p in pipelines if pipelines is not None else list_pipelines():
        if stage_of_board(p, board_id):
            return p
    return None


def is_run_card(p, c):
    """A stage agent's own card (its schedule's run): not an idea."""
    s = stage_of_board(p, c.get("board_id"))
    t = (c.get("title") or "").strip()
    return bool(s and t and (t == s.get("run_title") or t in (s.get("old_run_titles") or [])))


def cron_of(hhmm):
    m = TIME_RE.match(str(hhmm or "").strip())
    if not m:
        raise DfError(f"time must be HH:MM, not {hhmm!r}")
    return f"{int(m.group(2))} {int(m.group(1))} * * *"


def hhmm_of(cron):
    parts = str(cron or "").split()
    try:
        return f"{int(parts[1]):02d}:{int(parts[0]):02d}"
    except (IndexError, ValueError):
        return ""


def categories_text(p):
    cats = [c for c in p.get("categories") or [] if c.strip()]
    lines = []
    if p.get("categories_file"):
        lines.append(f"The categories are listed in the file `{p['categories_file']}` of the project (read it first).")
        if cats:
            lines.append("Also cover these categories:")
    if cats:
        lines += [f"- {c}" for c in cats]
    return "\n".join(lines)


def enumeration_prompt(p):
    s = stage(p, "enumeration")
    n = int(p.get("per_category") or 3)
    return f"""# Dreamfall · {s['name']} run ({p['name']})

You are the {s['name']} agent of the Dreamfall board "{p['name']}" ({p['id']}): a funnel in which your ideas are judged by
a second agent tonight, and only the person picks what gets built. Your job is the wide end of the funnel.

**What the ideas are about:** {p.get('goal') or 'improvements of this project'}

## Rules
- Ideas, not bug fixes or code fixes: new, genuine improvements a person would be glad to have.
- Work through the `dreamfall` command only (`dreamfall --help`). Do not create, move or start cards with `dieter card`
  yourself, do not touch the {stage(p, 'navigation')['name']} or {stage(p, 'implementation')['name']} boards, do not edit files and do not commit.

## Steps
1. `dreamfall run begin`
2. Learn the project as it is today: read its CLAUDE.md / README and skim the parts the ideas are about.
3. Categories:
{categories_text(p)}
4. `dreamfall ideas` lists every idea this Dreamfall has ever had (all stages, released and transferred ones too). Do not
   repeat any of them in substance, not even reworded.
5. For every category write at least {n} new ideas. For each idea write a brief to a file, in Markdown with the sections
   **Idea**, **Why it is valuable**, **Ingredients** (what exists already that it would use) and **Rough effort / risk**, then:
   `dreamfall add --category "<category>" --title "<short, concrete title>" --brief-file <file>`
   (`dreamfall add` refuses a title that already exists; then think of another idea.)
6. `dreamfall run end --note "<how many ideas in how many categories>"`
7. Report briefly: the number of ideas per category and the three you find most promising.
"""


def navigation_prompt(p):
    s, up, impl = stage(p, "navigation"), stage(p, "enumeration"), stage(p, "implementation")
    mail = ""
    if p.get("mail"):
        mail = f"""
7. Mail the result to the person (always, also when nothing was promoted):
   `dieter mail send --subject "{p['name']}: <n> new ideas waiting (<YYYY-MM-DD>)" --body-file <file> --operation-id dreamfall-{p['id']}-<YYYY-MM-DD>`
   Body: one line with the counts, then one `### <title>` section per promoted idea with its reason and the Idea /
   Why it is valuable text of its brief, then the most notable releases with one-line reasons."""
    return f"""# Dreamfall · {s['name']} run ({p['name']})

You are the {s['name']} agent of the Dreamfall board "{p['name']}" ({p['id']}). Tonight the {up['name']} agent filled the
{up['name']} queue; you judge every idea in it. The strong ones go on to {s['name']}, where the person picks what gets built
({impl['name']} is the person's stage: never create, move or start cards there).

**What the ideas are about:** {p.get('goal') or 'improvements of this project'}

## Rules
- Work through the `dreamfall` command only (`dreamfall --help`). Do not create, move or start cards with `dieter card`
  yourself, do not implement anything, do not edit files and do not commit.
- No idea may stay undecided: when you finish, the {up['name']} queue is empty.

## Steps
1. `dreamfall run begin`. If it reports that the {up['name']} run is still working, wait 5 minutes (`sleep 300`) and call
   it again. After 2 hours of waiting, end with `dreamfall run end --failed --note "{up['name']} did not finish"`.
2. Learn the project as it is today (CLAUDE.md / README, the parts the ideas touch), and see what already passed:
   `dreamfall ideas --stage {s['role']} --stage {impl['role']}`.
3. `dreamfall queue` prints every idea waiting in the {up['name']} queue with its brief.
4. Judge each idea by the navigation logic below. The person wrote it for this Dreamfall board: it decides, not
   your own taste. Whatever it says, never promote an idea that is already in {s['name']} or {impl['name']} in substance.

{indent(navigation_logic(p), '   > ')}

5. For every idea exactly one of:
   `dreamfall promote <card> --score <1-10> --rationale "<one sentence why>"`
   `dreamfall release <card> --score <1-10> --rationale "<one sentence why not>"`
6. `dreamfall queue` must now be empty (ideas that arrived after your run began may stay; mention them).
   Then `dreamfall run end --note "<judged / promoted / released>"`.{mail}
{8 if p.get('mail') else 7}. Report briefly: the counts, the promoted titles with their reasons, the two or three most notable releases.
"""


def navigation_logic(p):
    """The pipeline's navigation logic; records from before the setting existed get the default (with their pass hint)."""
    text = str(p.get("navigation_logic") or "").strip()
    if text:
        return text
    hint = p.get("pass_hint")
    return DEFAULT_NAVIGATION_LOGIC.replace("about one idea in four", hint) if hint else DEFAULT_NAVIGATION_LOGIC


def indent(text, prefix):
    return "\n".join(prefix + line if line.strip() else prefix.rstrip() for line in text.splitlines())


def schedule_args(p, role):
    s = stage(p, role)
    prompt = enumeration_prompt(p) if role == "enumeration" else navigation_prompt(p)
    a = p.get("agent") or {}
    args = ["--project", p["project_id"], "--board", s["board_id"], "--name", f"{p['name']} · {s['name']}",
            "--cron", s["cron"], "--timezone", p.get("timezone") or "UTC", "--title", s["run_title"], "--prompt", prompt,
            "--action", "run", "--workspace", "project", "--open-card", "skip_if_open",
            f"--enabled={'true' if p.get('enabled', True) else 'false'}"]
    for k in ("provider", "model", "effort"):
        if a.get(k):
            args += [f"--{k}", str(a[k])]
    if role == "navigation" and p.get("mail"):
        args.append("--mail=true")
    return args


def sync_schedules(p):
    """Create or replace the two agent schedules from the pipeline record (prompt, time, agent, enabled)."""
    for role in ("enumeration", "navigation"):
        s = stage(p, role)
        if s.get("schedule_id"):
            try:
                cli(["schedule", "update", *schedule_args(p, role), s["schedule_id"]], timeout=60)
                continue
            except DfError as e:
                if "not found" not in str(e).lower():
                    raise
        out = cli(["schedule", "create", *schedule_args(p, role)], timeout=60)
        sch = out.get("schedule") if isinstance(out, dict) and "schedule" in out else out
        s["schedule_id"] = (sch or {}).get("id") if isinstance(sch, dict) else None
        if not s["schedule_id"]:
            raise DfError(f"schedule for {s['name']} was not created")


# --------------------------------------------------------------------------- creating and changing a pipeline
def clean_settings(b, cur=None):
    cur = cur or {}
    out = {}
    name = str(b.get("name", cur.get("name") or "Dreamfall")).strip()
    if not name or len(name) > 80:
        raise DfError("name required (max 80 characters)")
    out["name"] = name
    out["goal"] = clip(b.get("goal", cur.get("goal") or ""), 1200)
    cats = b.get("categories", cur.get("categories") or [])
    if isinstance(cats, str):
        cats = cats.splitlines()
    out["categories"] = [clip(re.sub(r"^\s*[-*]\s*", "", c), 80) for c in cats if str(c).strip()][:40]
    out["categories_file"] = str(b.get("categories_file", cur.get("categories_file") or "")).strip()[:200]
    if out["categories_file"] and (out["categories_file"].startswith("/") or ".." in out["categories_file"].split("/")):
        raise DfError("categories file must be a path inside the project")
    if not out["categories"] and not out["categories_file"]:
        raise DfError("categories required: one per line, or a categories file of the project")
    try:
        out["per_category"] = max(1, min(10, int(b.get("per_category", cur.get("per_category") or 3))))
    except (TypeError, ValueError):
        raise DfError("ideas per category must be a number")
    logic = b.get("navigation_logic")
    logic = navigation_logic(cur) if logic is None else str(logic)
    logic = re.sub(r"\n{3,}", "\n\n", logic.replace("\r\n", "\n")).strip()
    if not logic:
        raise DfError("navigation logic required: how the Navigation agent decides which ideas go on")
    if len(logic) > MAX_LOGIC:
        raise DfError(f"navigation logic too long (max {MAX_LOGIC} characters)")
    out["navigation_logic"] = logic
    impl = b.get("implementation_instructions", cur.get("implementation_instructions") or "")
    impl = re.sub(r"\n{3,}", "\n\n", str(impl or "").replace("\r\n", "\n")).strip()
    if len(impl) > MAX_LOGIC:
        raise DfError(f"implementation instructions too long (max {MAX_LOGIC} characters)")
    out["implementation_instructions"] = impl
    out["implementation_mail"] = bool(b.get("implementation_mail", cur.get("implementation_mail", False)))
    out["timezone"] = str(b.get("timezone", cur.get("timezone") or "UTC")).strip() or "UTC"
    if not re.match(r"^[A-Za-z_]+(/[A-Za-z0-9_+-]+){0,2}$|^UTC$", out["timezone"]):
        raise DfError("timezone must be an IANA name such as Europe/Berlin")
    out["enabled"] = bool(b.get("enabled", cur.get("enabled", True)))
    out["mail"] = bool(b.get("mail", cur.get("mail", False)))
    a = b.get("agent") if isinstance(b.get("agent"), dict) else {}
    ca = cur.get("agent") or {}
    out["agent"] = {k: str(a.get(k, ca.get(k)) or "") for k in ("provider", "model", "effort")}
    times = {"enumeration": b.get("enumeration_time"), "navigation": b.get("navigation_time")}
    for role, t in times.items():
        if t in (None, ""):
            t = hhmm_of((stage(cur, role) or {}).get("cron")) or ("02:00" if role == "enumeration" else "05:00")
        out[role + "_cron"] = cron_of(t)
    return out


def create_pipeline(b, actor_name="you"):
    """Create the stage boards (or adopt existing boards of the project), the pipeline record and the two schedules.
    On a failure everything created so far is removed again."""
    project = str(b.get("project") or "")
    if not project.startswith("p_"):
        raise DfError("project required")
    st = clean_settings(b)
    boards = cli(["board", "list", "--format", "json"], timeout=30)
    boards = boards.get("boards") if isinstance(boards, dict) else boards or []
    mine = {x.get("id"): x for x in boards if (x.get("project_id") or x.get("projectId")) == project}
    taken = {bid for p in list_pipelines() for bid in (s.get("board_id") for s in p.get("stages") or [])}
    names = {x.get("name") for x in mine.values()}
    pid = "df_" + secrets.token_hex(5)
    created_boards, created = [], None
    try:
        stages = []
        adopt = b.get("boards") if isinstance(b.get("boards"), dict) else {}
        for role in ROLES:
            d = STAGES[role]
            sname = clip(str((b.get("stage_names") or {}).get(role) or d["name"]), 40)
            bid = str(adopt.get(role) or "")
            if bid:
                if bid not in mine:
                    raise DfError(f"board {bid} is not a board of this project")
                if bid in taken or bid in (s["board_id"] for s in stages):
                    raise DfError(f"board {mine[bid].get('name')} is already a stage of a Dreamfall board")
                if "review" not in [l.get("id") for l in mine[bid].get("lanes") or []]:
                    raise DfError(f"board {mine[bid].get('name')} has no Review lane (a Dreamfall stage needs one)")
            else:
                bname = sname if sname not in names else f"{st['name']} · {sname}"
                out = cli(["board", "create", "--project", project, "--name", bname, "--workflow", "review",
                           "--description", f"{sname} stage of the Dreamfall board {st['name']}"], timeout=30)
                bd = (out.get("board") or out) if isinstance(out, dict) else {}
                bid = bd.get("id")
                if not bid:
                    raise DfError(f"board {bname} was not created")
                created_boards.append(bid)
                names.add(bname)
            stages.append({"role": role, "name": sname, "board_id": bid, "actor": d["actor"], "icon": d["icon"], "color": d["color"],
                           **({"cron": st[role + "_cron"], "run_title": f"{st['name']} · {sname} run"} if d["actor"] == "agent" else {})})
        p = {"v": 1, "id": pid, "project_id": project, "created_at": now_iso(), "updated_at": now_iso(), "created_by": actor_name,
             **{k: v for k, v in st.items() if not k.endswith("_cron")}, "stages": stages}
        kv_put(pipeline_key(pid), p)
        created = p
        sync_schedules(p)
        kv_update(pipeline_key(pid), lambda cur: {**(cur or p), "stages": p["stages"], "updated_at": now_iso()})
        return p
    except Exception:
        for role in ("enumeration", "navigation"):
            sid = (stage(created, role) or {}).get("schedule_id") if created else None
            if sid:
                try:
                    cli(["schedule", "delete", sid], timeout=30)
                except DfError:
                    pass
        if created:
            try:
                kv_delete(pipeline_key(pid))
            except DfError:
                pass
        for bid in created_boards:
            retire_board(bid)
        raise


def retire_board(bid):
    try:
        shown = cli(["board", "show", bid], timeout=30) or {}
        bd = shown.get("board") if isinstance(shown, dict) and "board" in shown else shown
        rev = (bd or {}).get("retirementRevision") or (shown or {}).get("retirementRevision")
        if rev:
            cli(["board", "retire", "--revision", rev, "--operation", "df-" + secrets.token_hex(6), bid], timeout=30)
    except DfError:
        pass


def update_pipeline(pid, b):
    p = load_pipeline(pid)
    st = clean_settings(b, p)
    def apply(cur):
        cur = cur or p
        cur.update({k: v for k, v in st.items() if not k.endswith("_cron")})
        for s in cur.get("stages") or []:
            if s.get("actor") == "agent":
                s["cron"] = st[s["role"] + "_cron"]
                title = f"{cur['name']} · {s['name']} run"
                if s.get("run_title") and s["run_title"] != title:   # the earlier runs keep their titles: still runs, not ideas
                    s["old_run_titles"] = ([t for t in s.get("old_run_titles") or [] if t != title] + [s["run_title"]])[-10:]
                s["run_title"] = title
        cur["updated_at"] = now_iso()
        return cur
    p = kv_update(pipeline_key(pid), apply)
    sync_schedules(p)
    return kv_update(pipeline_key(pid), lambda cur: {**(cur or p), "stages": p["stages"]})


def delete_pipeline(pid):
    """Remove the Dreamfall view: the schedules go, the boards and their cards stay as ordinary Kanban boards."""
    p = load_pipeline(pid)
    for s in p.get("stages") or []:
        if s.get("schedule_id"):
            try:
                cli(["schedule", "delete", s["schedule_id"]], timeout=30)
            except DfError as e:
                if "not found" not in str(e).lower():
                    raise
    kv_delete(pipeline_key(pid))
    return {"ok": True, "boards": [s["board_id"] for s in p.get("stages") or []]}


def run_now(pid, role):
    p = load_pipeline(pid)
    s = stage(p, role)
    if not s or s.get("actor") != "agent" or not s.get("schedule_id"):
        raise DfError(f"{role} has no agent schedule")
    out = cli(["schedule", "run", s["schedule_id"]], timeout=60)
    return {"ok": True, "run": out}


# --------------------------------------------------------------------------- moving ideas (agents and the person)
def copy_brief(src_prompt, verb, from_name, who, rationale, src_id, extra=""):
    note = f"**{verb} from {from_name}** by {who}, {datetime.date.today().isoformat()}" + (f": {clip(rationale)}" if rationale else ".")
    extra = f"\n\n{extra.strip()}" if extra and extra.strip() else ""
    return f"{(src_prompt or '').rstrip()}\n\n---\n{note}{extra}\n\n<!-- {MARKER} {src_id} -->\n"


MAIL_HOWTO = ("You may mail the person (this card has mail access): write a Markdown report to a file and run "
              "`dieter mail send --subject \"<one line>\" --body-file <file>`. A ```vega-lite``` (inline JSON data) or "
              "```mermaid``` fence in the body is shown as a chart in Dieter Mail.")


# Dieter's board instructions move a card to Review only once its pull request exists, so in a project without a
# git remote a finished card stayed in Running (idle, "Running" on the page) for good.
FINISH_HOWTO = ("When the work is committed and its checks pass, move this card to Review (`dieter card move <this card> "
                "--lane review`). If the project has no git remote (`dieter workspace scm <this card>` says so), there is "
                "no pull request to open: say so in your reply and move the card to Review anyway, do not leave it in Running.")


def implementation_extras(p):
    """What a card picked for the person's stage carries besides the idea: the Dreamfall board's implementation
    instructions and how to finish (appended to its brief) and, with the mail switch on, mail access (`--mail`,
    fixed at creation)."""
    text = str(p.get("implementation_instructions") or "").strip()
    mail = bool(p.get("implementation_mail"))
    parts = ["## For every implementation on this Dreamfall board"]
    if text:
        parts.append(text)
    if mail:
        parts.append(MAIL_HOWTO)
    parts.append(FINISH_HOWTO)
    return "\n\n".join(parts), (["--mail"] if mail else [])


def transfer(p, src, dest_role, actor, name, verb, rationale="", score=None, run="", note="", settings=None, start=False, known=None):
    """Copy an idea to the next stage (provenance both ways) and release the original (Done). Idempotent: a copy
    recorded on the source, or a card on the destination board whose brief names the source, is reused.
    known(board_id) -> the board's cards as something already holds them (the bridge's cache), or None: then the
    board is listed through the CLI (two `card list` calls of ~1 s each on a Pi; the agents' command has no cache)."""
    s_from, s_to = stage_of_board(p, src["board_id"]), stage(p, dest_role)
    if not s_from or not s_to:
        raise DfError("the card is not on a stage of this Dreamfall board")
    rec, _ = kv_get("c." + src["id"])
    new_id, created = None, False
    if rec and rec.get("transferred_to"):
        try:
            if card_show(rec["transferred_to"])["board_id"] == s_to["board_id"]:
                new_id = rec["transferred_to"]
        except DfError:
            new_id = None
    if not new_id:
        mark = f"{MARKER} {src['id']}"
        cards = known(s_to["board_id"]) if known else None
        if cards is None:
            cards = board_cards(s_to["board_id"]) + board_cards(s_to["board_id"], archived=True)
        for c in cards:
            if mark in (c.get("prompt") or c.get("initial_prompt") or ""):
                new_id = c["id"]
                break
    if not new_id:
        settings = settings or {}
        args = ["card", "create", "--project", p["project_id"], "--board", s_to["board_id"], "--lane", "todo",
                "--title", src["title"], "--workspace", settings.get("workspace") or "project", "--format", "json"]
        extra, extra_args = implementation_extras(p) if s_to.get("actor") == "human" else ("", [])
        args += extra_args
        fd, path = tempfile.mkstemp(prefix="dreamfall-brief-", suffix=".md")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(copy_brief(src["prompt"], verb, s_from["name"], name, rationale, src["id"], extra))
        try:
            for k in ("provider", "model", "effort"):
                v = settings.get(k) or src.get(k)
                if v:
                    args += [f"--{k}", v]
            if src.get("checkout_id"):
                args += ["--checkout", src["checkout_id"]]
            out = cli(args + ["--prompt-file", path], timeout=120)
        finally:
            os.unlink(path)
        c = out.get("card") if isinstance(out, dict) and "card" in out else out
        new_id = (c or {}).get("id")
        if not new_id:
            raise DfError("the copy was not created")
        created = True
    category = (rec or {}).get("category") or split_category(src["title"])[0]
    kind = "picked" if dest_role == "implementation" else "judged"
    verdict = {"kind": "pick" if dest_role == "implementation" else "promote", "rationale": clip(rationale or note), "actor": actor, "name": name, "at": now_iso()}
    if score is not None:
        verdict["score"] = score
    if run:
        verdict["run"] = run

    def dest(cur):
        cur = cur or {"v": 1, "card": new_id, "created_at": now_iso(), "events": []}
        cur.update({"pipeline": p["id"], "stage": dest_role, "source": src["id"], "category": category or cur.get("category", "")})
        cur.setdefault("created_by", {"actor": actor, "name": name, **({"run": run} if run else {})})
        add_events(cur, event("created", actor, name, run=run, **{"from": src["id"]}, stage=dest_role))
        return cur

    def source(cur):
        cur = cur or {"v": 1, "card": src["id"], "pipeline": p["id"], "stage": s_from["role"], "category": category, "events": []}
        cur.setdefault("stage", s_from["role"])
        if not created and cur.get("transferred_to") == new_id and any(e.get("kind") == "transferred" and e.get("to") == new_id for e in cur.get("events") or []):
            return None    # a rerun of a recorded transfer: nothing to add
        cur["transferred_to"] = new_id
        if created or (cur.get("verdict") or {}).get("kind") not in ("promote", "pick"):
            cur["verdict"] = verdict
        add_events(cur, event(kind, actor, name, run=run, verdict=verdict["kind"], rationale=rationale, note=note, score=score, stage=dest_role),
                   event("transferred", actor, name, run=run, to=new_id, stage=dest_role))
        return cur

    kv_update("c." + new_id, dest)
    kv_update("c." + src["id"], source)
    if src["lane"] != "done":
        cli(["card", "move", "--lane", "done", src["id"]], timeout=30, parse=False)
    if start:
        cli(["card", "start", new_id], timeout=60, parse=False)
        kv_update("c." + new_id, lambda cur: add_events(cur or {"events": []}, event("started", actor, name)))
    return new_id, created or (rec or {}).get("transferred_to") != new_id   # True: this call completed the transfer


def release(p, src, actor, name, kind="release", rationale="", score=None, run=""):
    """Take an idea out of its stage's queue without deleting it: Review (released / parked)."""
    s = stage_of_board(p, src["board_id"])
    verdict = {"kind": kind, "rationale": clip(rationale), "actor": actor, "name": name, "at": now_iso()}
    if score is not None:
        verdict["score"] = score
    if run:
        verdict["run"] = run

    def apply(cur):
        cur = cur or {"v": 1, "card": src["id"], "pipeline": p["id"], "stage": s["role"],
                      "category": split_category(src["title"])[0], "events": []}
        cur.setdefault("stage", s["role"])
        if (cur.get("verdict") or {}).get("kind") == kind and src["lane"] == "review" and cur.get("events"):
            return None    # rerun: already released with this verdict
        cur["verdict"] = verdict
        add_events(cur, event("released" if kind == "release" else "parked", actor, name, run=run, verdict=kind, rationale=rationale, score=score))
        return cur

    kv_update("c." + src["id"], apply)
    if src["lane"] != "review":
        cli(["card", "move", "--lane", "review", src["id"]], timeout=30, parse=False)


def restore(p, src, actor, name, note=""):
    def apply(cur):
        cur = cur or {"v": 1, "card": src["id"], "pipeline": p["id"], "events": []}
        cur["verdict"] = None
        add_events(cur, event("restored", actor, name, note=note))
        return cur
    kv_update("c." + src["id"], apply)
    if src["lane"] != "todo":
        cli(["card", "move", "--lane", "todo", src["id"]], timeout=30, parse=False)


def record_created(p, c, actor, name, run="", category=""):
    s = stage_of_board(p, c["board_id"])
    cat = category or split_category(c["title"])[0]

    def apply(cur):
        cur = cur or {"v": 1, "card": c["id"], "created_at": now_iso(), "events": []}
        cur.update({"pipeline": p["id"], "stage": s["role"], "category": cat or cur.get("category", "")})
        cur.setdefault("created_by", {"actor": actor, "name": name, **({"run": run} if run else {})})
        add_events(cur, event("created", actor, name, run=run, stage=s["role"]))
        return cur
    return kv_update("c." + c["id"], apply)


def act(pid, b, actor_name="you", known=None):
    """A person's decision on the Dreamfall board: promote/release (first stage), pick/park (second stage),
    restore (a released or parked idea back to its queue), start (an Implementation card). The result names the
    boards touched and the lanes the cards are in now ("lanes": card id -> lane), so the caller can show the
    change without listing the boards again; known: see transfer()."""
    p = load_pipeline(pid)
    action, cid = str(b.get("action") or ""), str(b.get("card") or "")
    c = card_show(cid)
    s = stage_of_board(p, c["board_id"])
    if not s or c["archived"]:
        raise DfError("the card is not an open idea of this Dreamfall board")
    i = stage_index(p, s["role"])
    note = clip(b.get("note") or "")
    if action in ("promote", "pick"):
        nxt = (p.get("stages") or [])[i + 1] if i + 1 < len(p.get("stages") or []) else None
        if not nxt or (action == "pick") != (nxt["actor"] == "human"):
            raise DfError(f"{action} is not possible from {s['name']}")
        if c["lane"] not in ("todo", "review"):
            raise DfError(f"only waiting or {STAGES[s['role']]['lanes']['review'].lower()} ideas can be {action}ed")
        settings = {"workspace": b.get("workspace") if b.get("workspace") in ("project", "worktree") else "project"}
        for k in ("provider", "model", "effort"):
            if b.get(k):
                settings[k] = str(b[k])
        started = bool(b.get("start")) and action == "pick"
        new_id, created = transfer(p, c, nxt["role"], "human", actor_name, "Picked" if action == "pick" else "Promoted",
                                   rationale=note, note=note, settings=settings, start=started, known=known)
        return {"ok": True, "id": new_id, "created": created, "boards": [c["board_id"], nxt["board_id"]],
                "lanes": {cid: "done", new_id: "running" if started else "todo"}}
    if action in ("release", "park"):
        if c["lane"] != "todo" or s["actor"] == "human":
            raise DfError("only waiting ideas can be released or parked")
        release(p, c, "human", actor_name, kind="release" if s["role"] == "enumeration" else "park", rationale=note)
        return {"ok": True, "boards": [c["board_id"]], "lanes": {cid: "review"}}
    if action == "restore":
        if c["lane"] != "review" or s["actor"] == "human":
            raise DfError("only released or parked ideas go back to the queue")
        restore(p, c, "human", actor_name, note)
        return {"ok": True, "boards": [c["board_id"]], "lanes": {cid: "todo"}}
    if action == "start":
        if s["actor"] != "human" or c["lane"] != "todo" or c["started"]:
            raise DfError("only a Todo card of the person's stage is started here")
        cli(["card", "start", cid], timeout=60, parse=False)
        kv_update("c." + cid, lambda cur: add_events(cur or {"v": 1, "card": cid, "pipeline": pid, "stage": s["role"], "events": []},
                                                    event("started", "human", actor_name)))
        return {"ok": True, "boards": [c["board_id"]], "lanes": {cid: "running"}}
    raise DfError("action must be promote, release, pick, park, restore or start")


# --------------------------------------------------------------------------- the bridge's live cache
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


# --------------------------------------------------------------------------- the `dreamfall` command (agents)
class Ctx:
    """The calling agent: its run card, the Dreamfall board that card's board belongs to, and its stage."""
    def __init__(self, require_agent=True):
        me = os.environ.get("DIETER_CARD_ID", "")
        if not CARD_RE.match(me):
            raise DfError("run this from a Dreamfall run card (DIETER_CARD_ID is not set)")
        self.card = card_show(me)
        self.p = pipeline_for_board(self.card["board_id"])
        if not self.p:
            raise DfError(f"card {me} is not on a stage board of a Dreamfall board")
        self.stage = stage_of_board(self.p, self.card["board_id"])
        if require_agent and self.stage.get("actor") != "agent":
            raise DfError(f"{self.stage['name']} is the person's stage: agents do not create, move or judge cards there", 13)
        self.name = f"{self.stage['name']} agent"
        self.model = " · ".join(x for x in (self.card.get("provider"), self.card.get("model")) if x)

    def up(self):
        i = stage_index(self.p, self.stage["role"])
        return (self.p["stages"][i - 1]) if i > 0 else None

    def require(self, role, what):
        if self.stage["role"] != role:
            raise DfError(f"{what} belongs to the {stage(self.p, role)['name']} run, not to {self.stage['name']}", 13)

    def bump(self, field, n=1):
        def apply(cur):
            cur = cur or {"pipeline": self.p["id"], "stage": self.stage["role"], "card": self.card["id"], "started_at": now_iso(), "status": "running", "counts": {}}
            cur.setdefault("counts", {})[field] = int(cur["counts"].get(field) or 0) + n
            return cur
        kv_update("r." + self.card["id"], apply)


def all_ideas(p, roles=None):
    out = []
    for s in p.get("stages") or []:
        if roles and s["role"] not in roles:
            continue
        for c in board_cards(s["board_id"]) + board_cards(s["board_id"], archived=True):
            if not is_run_card(p, c):
                out.append({**c, "stage": s["role"], "stage_name": s["name"]})
    return out


def cmd_show(a):
    ctx = Ctx(require_agent=False)
    p = ctx.p
    print(json.dumps({"dreamfall": p["id"], "name": p["name"], "goal": p.get("goal"), "your_stage": ctx.stage["role"],
                      "your_card": ctx.card["id"], "categories": p.get("categories"), "categories_file": p.get("categories_file") or None,
                      "ideas_per_category": p.get("per_category"), "navigation_logic": navigation_logic(p),
                      "stages": [{"role": s["role"], "name": s["name"], "board": s["board_id"], "actor": s["actor"]} for s in p["stages"]]},
                     ensure_ascii=False, indent=2))


def cmd_run(a):
    ctx = Ctx()
    p, s, me = ctx.p, ctx.stage, ctx.card["id"]
    if a.action == "begin":
        up = ctx.up()
        if up and up.get("actor") == "agent":
            last, _ = kv_get(f"l.{p['id']}.{up['role']}")
            running = [c for c in board_cards(up["board_id"], lane="running") if is_run_card(p, c)]
            if (last and last.get("status") == "running" and time.time() - parse_ts(last.get("started_at")) < 6 * 3600) or running:
                who = (last or {}).get("card") or running[0]["id"]
                raise DfError(f"the {up['name']} run ({who}) is still working. Wait 5 minutes and call `dreamfall run begin` again.", WAIT_EXIT)
        rec = {"pipeline": p["id"], "stage": s["role"], "card": me, "started_at": now_iso(), "status": "running", "counts": {}, "model": ctx.model}
        kv_update("r." + me, lambda cur: {**rec, **({"counts": cur.get("counts") or {}} if cur else {})})
        kv_put_latest(p["id"], s["role"], {"card": me, "status": "running", "started_at": rec["started_at"]})
        print(f"Run {me} of {s['name']} begins ({p['name']}).")
        if s["role"] == "enumeration":
            print("Next: learn the project, `dreamfall ideas`, then `dreamfall add …` per idea, finally `dreamfall run end`.")
        else:
            print(f"Next: `dreamfall queue`, then `dreamfall promote` or `dreamfall release` for every idea, finally `dreamfall run end`.")
        return
    status = "failed" if a.failed else "done"
    def apply(cur):
        cur = cur or {"pipeline": p["id"], "stage": s["role"], "card": me, "started_at": now_iso(), "counts": {}}
        cur.update({"status": status, "finished_at": now_iso(), "note": clip(a.note or "")})
        return cur
    rec = kv_update("r." + me, apply)
    kv_put_latest(p["id"], s["role"], {"card": me, "status": status, "started_at": rec.get("started_at"), "finished_at": rec["finished_at"]})
    if s["role"] == "navigation" and status == "done":
        left = [c for c in board_cards(stage(p, "enumeration")["board_id"], lane="todo") if not is_run_card(p, c)]
        if left:
            print(f"Note: {len(left)} idea(s) still wait in the {stage(p, 'enumeration')['name']} queue (they arrived after this run began or were skipped).")
    if not a.keep_card:
        cli(["card", "move", "--lane", "done", me], timeout=30, parse=False)
    counts = rec.get("counts") or {}
    print(f"Run {me} {status}: " + (", ".join(f"{k} {v}" for k, v in sorted(counts.items())) or "nothing recorded") + ".")


def kv_put_latest(pid, role, val):
    kv_update(f"l.{pid}.{role}", lambda cur: {**val, "pipeline": pid, "stage": role})


def cmd_ideas(a):
    ctx = Ctx(require_agent=False)
    rows = all_ideas(ctx.p, set(a.stage) if a.stage else None)
    if a.json:
        print(json.dumps([{k: r[k] for k in ("id", "title", "stage", "lane", "archived")} for r in rows], ensure_ascii=False, indent=1))
        return
    lane_name = lambda r: STAGES[r["stage"]]["lanes"].get(r["lane"], r["lane"])
    print(f"{len(rows)} ideas in {ctx.p['name']} (stage / state: title):")
    for r in sorted(rows, key=lambda r: (ROLES.index(r["stage"]), r["title"].lower())):
        print(f"- {r['stage_name']} / {'archived' if r['archived'] else lane_name(r)}: {r['title']}")


def cmd_queue(a):
    ctx = Ctx()
    up = ctx.up()
    if not up:
        raise DfError(f"{ctx.stage['name']} is the first stage: it has no queue to judge", 13)
    rows = [c for c in board_cards(up["board_id"], lane="todo") if not is_run_card(ctx.p, c)]
    if a.json:
        print(json.dumps([{k: c[k] for k in ("id", "title", "prompt")} for c in rows], ensure_ascii=False, indent=1))
        return
    if not rows:
        print(f"The {up['name']} queue is empty.")
        return
    print(f"{len(rows)} idea(s) wait in the {up['name']} queue:\n")
    for c in rows:
        print(f"## {c['title']}\ncard: {c['id']}\n\n{c['prompt'].strip()}\n\n")


def cmd_add(a):
    ctx = Ctx()
    ctx.require("enumeration", "adding ideas")
    p, s = ctx.p, ctx.stage
    category = clip(a.category, 60).strip("[] ")
    title = clip(a.title, 160)
    if not title or not category:
        raise DfError("--title and --category are required")
    full = f"[{category}] {split_category(title)[1]}"
    brief = open(a.brief_file, encoding="utf-8").read() if a.brief_file else (a.brief or "")
    if not brief.strip():
        raise DfError("a brief is required (--brief-file FILE or --brief TEXT)")
    key = title_key(full)
    for c in all_ideas(p):
        if title_key(c["title"]) == key:
            raise DfError(f"exists: {c['id']} ({c['stage_name']}): {c['title']}. Think of a different idea.", 3)
    brief = f"{brief.rstrip()}\n\n---\nFound by the {s['name']} agent of the Dreamfall board {p['name']}, run `{ctx.card['id']}`, {datetime.date.today().isoformat()}" + (f" ({ctx.model})" if ctx.model else "") + ".\n"
    agent = p.get("agent") or {}
    args = ["card", "create", "--project", p["project_id"], "--board", s["board_id"], "--lane", "todo", "--title", full,
            "--workspace", "project", "--format", "json"]
    for k in ("provider", "model", "effort"):
        v = agent.get(k) or ctx.card.get(k)
        if v:
            args += [f"--{k}", v]
    if ctx.card.get("checkout_id"):
        args += ["--checkout", ctx.card["checkout_id"]]
    fd, path = tempfile.mkstemp(prefix="dreamfall-idea-", suffix=".md")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(brief)
    try:
        out = cli(args + ["--prompt-file", path], timeout=120)
    finally:
        os.unlink(path)
    c = out.get("card") if isinstance(out, dict) and "card" in out else out
    cid = (c or {}).get("id")
    if not cid:
        raise DfError("the card was not created")
    record_created(p, {"id": cid, "board_id": s["board_id"], "title": full}, "agent", ctx.name, run=ctx.card["id"], category=category)
    ctx.bump("created")
    print(f"added: {cid} {full}")


def judged_card(ctx, cid):
    up = ctx.up()
    if not up:
        raise DfError(f"{ctx.stage['name']} judges no earlier stage", 13)
    c = card_show(cid)
    if c["board_id"] != up["board_id"]:
        raise DfError(f"{cid} is not an idea of the {up['name']} board", 13)
    if is_run_card(ctx.p, c):
        raise DfError(f"{cid} is a run card, not an idea", 13)
    return c, up


def score_of(a):
    if a.score is None:
        return None
    if not 0 <= a.score <= 10:
        raise DfError("--score must be between 0 and 10")
    return a.score


def cmd_promote(a):
    ctx = Ctx()
    c, up = judged_card(ctx, a.card)
    if not (a.rationale or "").strip():
        raise DfError("--rationale is required: one sentence why this idea goes on")
    rec, _ = kv_get("c." + c["id"])
    if c["lane"] == "review" and ((rec or {}).get("verdict") or {}).get("kind") == "release":
        raise DfError(f"{c['id']} was released already; it is not promoted any more")
    if c["lane"] not in ("todo", "done"):
        raise DfError(f"{c['id']} is not waiting in the {up['name']} queue (lane {c['lane']})")
    new_id, created = transfer(ctx.p, c, ctx.stage["role"], "agent", ctx.name, "Promoted", rationale=a.rationale,
                               score=score_of(a), run=ctx.card["id"])
    if created:
        ctx.bump("promoted")
    print(("promoted" if created else "already promoted") + f": {c['id']} -> {new_id} ({ctx.stage['name']})")


def cmd_release(a):
    ctx = Ctx()
    c, up = judged_card(ctx, a.card)
    if not (a.rationale or "").strip():
        raise DfError("--rationale is required: one sentence why this idea stops here")
    rec, _ = kv_get("c." + c["id"])
    if c["lane"] == "done" or (rec or {}).get("transferred_to"):
        raise DfError(f"{c['id']} was promoted already")
    already = c["lane"] == "review"
    release(ctx.p, c, "agent", ctx.name, kind="release", rationale=a.rationale, score=score_of(a), run=ctx.card["id"])
    if not already:
        ctx.bump("released")
    print(("already released" if already else "released") + f": {c['id']} ({up['name']} · Review)")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="dreamfall", description="Dreamfall boards: the commands of the stage agents. Run from a Dreamfall "
                                 "run card (DIETER_CARD_ID). Every step is recorded on the cards' trail; reruns do not duplicate.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("show", help="the Dreamfall board of your run card: goal, categories, stages")
    r = sub.add_parser("run", help="`run begin` first, `run end` last")
    r.add_argument("action", choices=["begin", "end"])
    r.add_argument("--note", help="end: one line on what the run did")
    r.add_argument("--failed", action="store_true", help="end: the run could not do its job")
    r.add_argument("--keep-card", action="store_true", help="end: leave your run card in its lane (default: Done)")
    i = sub.add_parser("ideas", help="every idea the Dreamfall board ever had (for duplicate checks)")
    i.add_argument("--stage", action="append", choices=list(ROLES))
    i.add_argument("--json", action="store_true")
    q = sub.add_parser("queue", help="the ideas waiting for your judgement, with their briefs")
    q.add_argument("--json", action="store_true")
    ad = sub.add_parser("add", help="Enumeration: add one idea as a Todo card")
    ad.add_argument("--title", required=True)
    ad.add_argument("--category", required=True)
    ad.add_argument("--brief-file")
    ad.add_argument("--brief")
    for name, hlp in (("promote", "copy an idea to your stage and release the original (Done)"), ("release", "keep an idea out (Review)")):
        x = sub.add_parser(name, help=hlp)
        x.add_argument("card")
        x.add_argument("--rationale", required=True, help="one sentence, kept on the card")
        x.add_argument("--score", type=int, help="0-10")
    a = ap.parse_args(argv)
    try:
        {"show": cmd_show, "run": cmd_run, "ideas": cmd_ideas, "queue": cmd_queue, "add": cmd_add,
         "promote": cmd_promote, "release": cmd_release}[a.cmd](a)
        return 0
    except DfError as e:
        print(f"dreamfall: {e}", file=sys.stderr)
        return e.code
    except OSError as e:
        print(f"dreamfall: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
