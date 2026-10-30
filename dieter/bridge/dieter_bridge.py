#!/usr/bin/env python3
"""Dieter bridge: a small HTTP service that wraps the `dieter` CLI for the add-on's page (Dieter's Compose UI
in next/, with its web host) and for HA sensors.

Two homes, detected by /data/options.json:
  * Home Assistant add-on (dieter-addon/): the bridge also runs the Dieter daemon
    (`dieter daemon start`, store /data/dieter, local API 127.0.0.1:4242), listens on
    0.0.0.0:8099 behind Supervisor Ingress, clones the configured projects into
    /data/projects and registers them, and fires the HA events dieter_turn_finished /
    dieter_card_review through the Supervisor. Reads and writes are trusted from the
    Supervisor network 172.30.32.0/23 only: Ingress forwards requests of logged-in HA
    users (X-Ingress-Path set), HA core calls /api/ha and the rest_commands from there.
  * PC (dieter-bridge/): systemd user service dieter-bridge on 0.0.0.0:4250, Caddy on
    the Tailscale name for the phone. Reads from loopback, LAN and Tailscale; writes from
    Home Assistant (HA_IP), Tailscale devices whose `tailscale whois` login is the PC
    owner's, or with X-API-Key (~/.config/dieter-bridge/api_key, created on first start).

Endpoints:
  GET  /                      the add-on's root (index.html next to this file): forwards to next/, with deep links
  GET  /api/state             everything the board needs (from the CLI: the cards `dieter watch changes` reports
                              changed are re-read one by one, everything is listed every IDLE_POLL_S, or POLL_S
                              while that stream is down)
  GET  /api/events            SSE: "state" whenever the board changed, heartbeats
  GET  /api/ha                compact summary for HA sensors
  GET  /api/dreamfall/card/ID  Dreamfall: a card's trail and provenance chain
  POST /api/dreamfall          Dreamfall: create a pipeline (stage boards, records, schedules)
  POST /api/dreamfall/ID/{update|delete|run|act}   settings, removal, run a stage now, a person's decision
  GET  /api/{card|chat}/ID    conversation (last 200 messages) + detail
  GET  /api/{card|chat}/ID/stream?after=SEQ&delta=1   SSE of `dieter ... watch` frames: "conversation" (complete),
                              with delta=1 then "delta" (the changed messages and the conversation's other fields)
  GET  /api/card/ID/changes   changed files of the card's checkout
  GET  /api/project/ID/changes
  GET  /api/card/ID/diff?path=FILE
  GET  /api/{card|chat}/ID/image?path=REL   an image file of the checkout (the page shows Markdown ![](path) of a reply)
  GET  /api/{card|chat}/ID/tool-image?message=M&tool=T&i=N   N-th image in a tool call's output (Read of a PNG, screenshots)
  GET  /api/machines          enrolled machines (also part of /api/state)
  GET  /api/machine/ID/info   live host telemetry of one enrolled machine (`dieter machine info ID`)
  GET  /next/...              the new Compose page (Kotlin/Wasm bundle in /opt/web, DIETER_WEB_DIR): hashed files
                              immutable, index.html and resources no-cache + ETag; *.wasm.gz raw for the loader,
                              .br/.gz by Accept-Encoding only outside Ingress (Ingress strips that header)
  GET  /next/core             WebSocket to the web host (the page's core; relayed with the per-boot secret)
  GET  /next/signin?code=…    the gateway's redirect after GitHub sign-in, relayed to the web host
  GET  /api/ws/echo           WebSocket echo (checks that WebSockets pass Ingress; Phase 1 of the Compose page)
  POST /api/card              create {project, board, title, prompt, provider, model, effort, workspace, branch?, base?, publish?, vault?, mail?, lane, attachments?}
                              without a title: --auto-title, or with Settings → Ollama (HA) renamed in the background (title_entity)
  POST /api/chat              create {project, title, prompt, provider, model, effort}
  POST /api/{card|chat}/ID/send   {message, model?, effort?, attachments?: [upload id]}
  POST /api/upload            raw file body (Content-Type, X-Filename) -> {id, name, size}; up to 20 MB, kept until sent
  POST /api/card/ID/move      {lane}
  POST /api/card/ID/start
  POST /api/{card|chat}/ID/cancel     stop the running turn
  POST /api/{card|chat}/ID/archive
  POST /api/card/ID/read      {seq}  mark the displayed reply as seen (response_seq)
  POST /api/project           {git_url, name?, branch?} | {folder}  a repository or folder: answered by the operator
                              {folder}  new empty Git repository /data/projects/<folder> (existing folder adopted)
  POST /api/board             {project, name, workflow?: review|direct, publish?: manual|pull_request|push_base}  a new board of the project (like the app's New board)
  POST /api/board/ID/git      {publish: manual|pull_request|push_base}  the board's remote publish mode (cards inherit it)
  POST /api/boards/order      {ids: [board id, ...]}  the boards' order on the page (dragged): answered by the operator
  POST /api/focus             {id}   remember which card the next page load should open (push deep link)
  GET  /api/focus?consume=1   {id|null} and forget it (valid 15 min)
  GET|POST /api/settings      title model, work folder retention, board order: answered by the operator (Settings.kt)
  POST /api/titles            {kind: card|chat, id, prompt, provisional}  a card or chat the new page created on this
                              daemon under its placeholder title: Ollama names it in the background (as POST /api/card)
  GET|POST /api/prompts       Agent instructions: answered by the operator (OPERATOR_PATHS), forwarded as they are
  POST /api/page-load         the page's start on a device (time, files downloaded): one line in the log, by the operator
  GET  /api/claude/login      add-on only: state of a running `claude auth login` {running, url, done, auth}
  POST /api/claude/login      start the sign-in; returns the link the user opens on claude.com
  POST /api/claude/login/code {code}  hand the code from claude.com to the CLI; writes /data/.claude/.credentials.json
  POST /api/claude/login/cancel, POST /api/claude/logout
  GET  /api/questions         questions the agents wait on {questions: [{id, session, input, created_at}]} (also in /api/state)
  POST /api/questions         {id, session, input}  the harness worker registers a question (DIETER_QUESTIONS_URL)
  GET  /api/questions/ID      {done: false} | {done: true, output}  the worker polls for the answer
  POST /api/questions/ID/answer  {action: answered|partially-answered|declined, answers: {qid: {optionIds, freeform?}}}
  GET  /api/ssh               add-on only: the add-on's SSH public key {public_key, fingerprint}
  POST /api/ssh/test          {target: "user@host"}  BatchMode ssh with that key; {ok, output|error}
  GET  /api/mail/ID           one message of Dieter Mail {header, body} (the headers are in /api/state: mail)
  POST /api/mail/mark         {ids, read?, flagged?, folder?: inbox|archive|trash}  dieter mail mark
  POST /api/mail/delete       {ids} | {empty_trash: true}  delete for good
Only stdlib; no pip dependencies.
"""
import base64
import concurrent.futures
import hashlib
import ipaddress
import mimetypes
import pty
import json
import os
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import dreamfall   # Dreamfall boards (bridge/dreamfall.py): pipelines, records, the agents' `dreamfall` command
import housekeeping   # work folders of finished worktree cards (bridge/housekeeping.py)

HERE = os.path.dirname(os.path.abspath(__file__))
OPTIONS_FILE = "/data/options.json"              # written by the Supervisor from the add-on's options
ADDON = os.path.exists(OPTIONS_FILE)
try:
    OPTS = json.load(open(OPTIONS_FILE)) if ADDON else {}
except (OSError, ValueError):
    OPTS = {}
DIETER_HOME = os.environ.get("DIETER_HOME") or ("/data/dieter" if ADDON else os.path.expanduser("~/.dieter"))
os.environ["DIETER_HOME"] = DIETER_HOME           # every `dieter` call and the daemon use this store
DIETER = os.environ.get("DIETER_BIN", "/usr/local/bin/dieter" if ADDON else os.path.expanduser("~/.local/bin/dieter"))
BIND = os.environ.get("BRIDGE_BIND", "0.0.0.0")
PORT = int(os.environ.get("BRIDGE_PORT", "8099" if ADDON else "4250"))
HA_IP = os.environ.get("BRIDGE_HA_IP", "192.168.0.10")
POLL_S = float(os.environ.get("BRIDGE_POLL_S", "8"))               # board poll while the change stream is down
IDLE_POLL_S = float(os.environ.get("BRIDGE_IDLE_POLL_S", "60"))     # full poll (safety net) while `dieter watch changes` runs
POLL_GAP_S = float(os.environ.get("BRIDGE_POLL_GAP_S", "1"))        # rest between two polls (a burst of changes is one poll)
POLL_REST = float(os.environ.get("BRIDGE_POLL_REST", "4"))          # after a poll that listed: rest this many times as long as it took
PUSH_GAP_S = float(os.environ.get("BRIDGE_PUSH_GAP_S", "0.3"))    # /api/events: at most one state push per this many seconds
SHOW_MAX = 24                                     # changed cards re-read one by one (`card show`); more, and the poll lists everything
CARD_LIMIT = 200                                  # cards per project in the state (the daemon sorts by lane name, then position)
SLOW_POLL_S = float(os.environ.get("BRIDGE_SLOW_POLL_S", "60"))
MAIL_POLL_S = float(os.environ.get("BRIDGE_MAIL_POLL_S", "10"))      # Dieter Mail: the change stream does not carry it
KEY_FILE = os.environ.get("BRIDGE_KEY_FILE", "/data/bridge_api_key" if ADDON else os.path.expanduser("~/.config/dieter-bridge/api_key"))
SUPERVISOR = "http://supervisor"
SUPERVISOR_TOKEN = os.environ.get("SUPERVISOR_TOKEN")
PROJECTS_DIR = "/data/projects"
ENV_FILE = "/data/dieter.env"                    # provider credentials for the daemon and its harnesses
# The harness runtime (~800 MB) is staged by the operator (host/…/operator/Harness.kt) for the fork release in the
# image (DIETER_VERSION); the bridge runs its Claude quota probe.
RUNTIME_DIR = os.path.join(DIETER_HOME, "harness-staged", os.environ.get("DIETER_VERSION", ""), "runtime")
ANTIGRAVITY_TOKEN_FILE = "/data/.gemini/antigravity-cli/antigravity-oauth-token"   # HOME=/data
CLAUDE_CRED_FILE = "/data/.claude/.credentials.json"       # written by `claude auth login` (HOME=/data)
CLAUDE_OAUTH_CLIENT = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"   # Claude Code's public OAuth client id
CLAUDE_TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
HASSIO_NETS = [ipaddress.ip_network("172.30.32.0/23")]   # Supervisor (ingress proxy) and HA core
LOOPBACK = [ipaddress.ip_network(n) for n in ("127.0.0.0/8", "::1/128")]
READ_NETS = LOOPBACK + HASSIO_NETS + [ipaddress.ip_network(n) for n in ("192.168.0.0/24", "100.64.0.0/10", "fd7a:115c:a1e0::/48")]
TS_NETS = [ipaddress.ip_network(n) for n in ("100.64.0.0/10", "fd7a:115c:a1e0::/48")]
LANES = ["todo", "running", "review", "done"]
FOCUS = {"id": None, "at": 0}                     # card the next page load should open (set by HA before a push)
FOCUS_TTL_S = 900
# Questions the agents ask the user (Claude Code's AskUserQuestion): the harness worker of the fork
# release registers them here (DIETER_QUESTIONS_URL) and polls for the answer given on the page.
QUESTIONS = {}                                    # tool call id -> {"id", "session", "input", "created_at", "output"}
QUESTIONS_LOCK = threading.Lock()
QUESTION_TTL_S = 24 * 3600
QUESTION_ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
QUESTION_ACTIONS = ("answered", "partially-answered", "declined", "cancelled")


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def api_key():
    try:
        return open(KEY_FILE).read().strip()
    except FileNotFoundError:
        os.makedirs(os.path.dirname(KEY_FILE), mode=0o700, exist_ok=True)
        k = secrets.token_urlsafe(32)
        with open(os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT, 0o600), "w") as f:
            f.write(k)
        return k


API_KEY = api_key()


# --------------------------------------------------------------------------- CLI
class CliError(Exception):
    pass


def run(args, timeout=30, parse=True):
    """Run `dieter ARGS` and return parsed JSON (or text)."""
    try:
        p = subprocess.run([DIETER, *args], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise CliError(f"dieter {' '.join(args[:2])}: timeout")
    if p.returncode != 0:
        raise CliError((p.stderr or p.stdout).strip()[:500] or f"exit {p.returncode}")
    if not parse:
        return p.stdout
    out = p.stdout.strip()
    if not out or out == "null":
        return None
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return out


def unwrap(v, key):
    """CLI commands return either a bare list or {key: [...]}."""
    if isinstance(v, dict) and key in v:
        return v[key] or []
    return v or []


def b64json(s):
    try:
        return json.loads(base64.b64decode(s or "") or b"{}")
    except Exception:
        return {}


def local_daemon_info():
    """(daemon id, enrolled) from the store's runtime file; ("", False) if unknown. An unenrolled daemon
    owns every record it holds, so orphan detection only applies to an enrolled one."""
    try:
        with open(os.path.join(DIETER_HOME, "runtime", "daemon.json"), encoding="utf-8") as f:
            d = json.load(f)
        return str(d.get("daemonId") or ""), bool(d.get("enrolled"))
    except Exception:
        return "", False


def norm_card(c, kind="card"):
    """snake_case (list) and camelCase (show/create) variants -> one shape."""
    g = lambda *ks: next((c[k] for k in ks if k in c and c[k] is not None), None)
    usage = g("token_usage", "tokenUsage") or {}
    seq = lambda v: int(v) if str(v or "").isdigit() else v          # `card list` sends numbers, `card show` strings
    return {
        "kind": kind,
        "id": c.get("id"),
        "title": c.get("title") or "(ohne Titel)",
        "lane": c.get("lane") if kind == "card" else None,
        "project_id": g("project_id", "projectId"),
        "board_id": g("board_id", "boardId"),
        "provider": c.get("provider"),
        "model": c.get("model"),
        "effort": c.get("effort"),
        "runtime": c.get("runtime") or "idle",
        "workspace_mode": g("workspace_mode", "workspaceMode"),
        "branch": g("worktree_branch", "worktreeBranch", "branch"),
        "initial_prompt": g("initial_prompt", "initialPrompt"),
        "last_activity_at": g("last_activity_at", "lastActivityAt"),
        "phase_changed_at": g("phase_changed_at", "phaseChangedAt"),
        "created_at": g("created_at", "createdAt"),
        "updated_at": g("updated_at", "updatedAt"),
        "archived": bool(g("archived", "archivedAt", "archived_at")),
        "owner_daemon_id": g("owner_daemon_id", "ownerDaemonId"),
        "summary": c.get("summary") or "",
        "initial_prompt_sent": bool(g("initial_prompt_sent_at", "initialPromptSentAt")),
        "response_seq": seq(g("response_seq", "responseSeq")),
        "seen_response_seq": seq(g("seen_response_seq", "seenResponseSeq")),
        "label_ids": g("label_ids", "labelIds") or [],
        # `card list` has snake_case keys here (input_tokens), `card show` camelCase
        "tokens": {k: int(usage.get(k) or usage.get(snake) or 0) for k, snake in
                   (("inputTokens", "input_tokens"), ("outputTokens", "output_tokens"), ("totalTokens", "total_tokens"))} if usage else None,
    }


def norm_quota(q):
    out = []
    for grp in (q or {}).get("groups", []) or []:
        prov = (grp.get("provider") or "").replace("PROVIDER_QUOTA_PROVIDER_", "").lower()
        for a in grp.get("accounts", []) or []:
            out.append({
                "provider": prov,
                "plan": a.get("plan"),
                "email": a.get("displayEmail"),
                "available": "AVAILABLE" in (a.get("availability") or ""),
                "windows": [{
                    "id": w.get("id"), "label": w.get("label"),
                    "used": w.get("usedPercent"), "remaining": w.get("remainingPercent"),
                    "resets_at": w.get("resetsAt"),
                } for w in a.get("windows", []) or []],
                "refreshed_at": a.get("refreshedAt"),
            })
    return out


def norm_machine(m):
    if not isinstance(m, dict):
        return None
    gpu = None
    devs = ((m.get("gpu") or {}).get("devices") or [])
    if devs:
        d = devs[0]
        gpu = {"name": d.get("name"), "util": d.get("utilizationPercent"), "mem_used": int(d.get("memoryUsedBytes") or 0),
               "mem_total": int(d.get("memoryTotalBytes") or 0), "temp": d.get("temperatureCelsius"), "power": d.get("powerWatts")}
    return {
        "hostname": m.get("hostname"), "os": f"{m.get('osName')} {m.get('osVersion')}", "cpu": m.get("processor"),
        "cpu_percent": m.get("cpuUsagePercent"), "load1": m.get("load1"), "cores": m.get("logicalCpuCount"),
        "mem_used": int(m.get("memoryUsedBytes") or 0), "mem_total": int(m.get("memoryTotalBytes") or 0),
        "disk_free": int(m.get("diskFreeBytes") or 0), "disk_total": int(m.get("diskTotalBytes") or 0),
        "uptime_s": int(m.get("uptimeSeconds") or 0), "gpu": gpu, "daemon_version": (m.get("daemonBuild") or {}).get("releaseVersion"),
        "collected_at": m.get("collectedAt"),
    }


def machine_info(mid):
    """Telemetry of one enrolled machine. The local daemon's own figures come from the slow poll (already
    collected, no CLI round trip); any other machine is asked through the gateway."""
    if mid in ("local", local_daemon_info()[0]):
        return STATE.slow.get("machine") or {"error": "no telemetry yet"}
    try:
        return norm_machine(run(["machine", "info", mid], timeout=20)) or {"error": "no telemetry"}
    except CliError as e:
        return {"error": str(e)}


DIAG_NOISE = re.compile(r"\s*HarnessAgent: `sandbox` is deprecated\. Supply `sandboxSession` to createSession\(\) instead\.\s*")
DIAG_LINES = re.compile(r"^(\[harness:[^\]]*\].*|AGY_ERROR:.*|\s+at .*)$", re.M)


def clean_text(text):
    """Failure texts carry the worker's stderr: drop the AI SDK deprecation warning, the host's
    [harness:…] stack lines, agy's AGY_ERROR JSON and the duplicated 'error: <message>' echo."""
    if not text or not any(k in text for k in ("HarnessAgent:", "[harness:", "AGY_ERROR:", "error: ")):
        return text or ""
    out = DIAG_LINES.sub("", DIAG_NOISE.sub(" ", text))
    m = re.search(r"\s*error: (.+?)\s*$", out, re.S)
    if m and m.group(1).strip()[:60] in out[:m.start()]:
        out = out[:m.start()]
    return re.sub(r"\n{2,}", "\n", out).strip()


def norm_conversation(snapshot):
    """`card poll`/`card watch` snapshot -> {status, last_seq, messages[], card}."""
    conv = snapshot.get("conversation") or {}
    msgs = []
    for m in conv.get("messages", []) or []:
        meta = b64json(m.get("metadataJson"))
        parts = []
        for p in m.get("parts", []) or []:
            t = p.get("type")
            if t == "text":
                parts.append({"type": "text", "text": clean_text(p.get("text", "")), "state": p.get("state")})
            else:
                slim = {}
                for k, v in p.items():
                    if k == "url" and isinstance(v, str) and v.startswith("data:"):
                        # attachments: small images stay inline for the page's preview, larger ones show a pill
                        slim[k] = v if v.startswith("data:image/") and len(v) <= 600_000 else ""
                        slim["size"] = len(v) * 3 // 4
                    elif isinstance(v, str):
                        slim[k] = v[:2000]
                    elif isinstance(v, (int, float, bool)) or v is None:
                        slim[k] = v
                    else:
                        slim[k] = json.dumps(v, ensure_ascii=False, default=str)[:2000]
                slim["type"] = t or "other"
                if (p.get("hasOutput") or p.get("has_output")) and IMAGE_HINT.search(str(p.get("outputPreview") or p.get("output_preview") or "")):
                    slim["image"] = True
                parts.append(slim)
        msgs.append({"id": m.get("id"), "role": m.get("role"), "created_at": meta.get("createdAt"),
                     "usage": (meta.get("usage") or {}).get("totalTokens"), "parts": parts})
    detail = snapshot.get("detail") or {}
    card = detail.get("card") or detail.get("chat") or {}
    return {
        "status": conv.get("status"), "last_seq": conv.get("lastSeq"), "updated_at": conv.get("updatedAt"),
        "messages": msgs, "page": snapshot.get("page"),
        "task_plans": conv.get("taskPlans") or conv.get("task_plans") or [],
        "queue": conv.get("queue") or [], "pending_tools": conv.get("pendingTools") or conv.get("pending_tools") or [],
        "subagents": conv.get("subagents") or [],
        "card": norm_card(card, "card" if "lane" in card else "chat") if card else None,
        "project": (detail.get("project") or {}).get("name"), "board": (detail.get("board") or {}).get("name"),
        "roots": checkout_roots(detail),
    }


def conversation_held(page):
    """What a page holds after a complete conversation: {"fields": {...}, "messages": {id: message}}."""
    return {"fields": {k: v for k, v in page.items() if k != "messages"}, "messages": {m["id"]: m for m in page["messages"]}}


def conversation_delta(held, page):
    """The "delta" event that brings a page holding `held` to `page` (its messages: the changed ones), or None.
    Fields only when they changed. A message the page has gets the parts from the first changed one,
    {..., "from": N, "parts": parts[N:]} with its other fields (the page keeps its first N parts). Updates `held`."""
    fields = {k: v for k, v in page.items() if k != "messages" and (k not in held["fields"] or held["fields"][k] != v)}
    held["fields"].update(fields)
    msgs = []
    for m in page["messages"]:
        old = held["messages"].get(m["id"])
        if old == m:
            continue
        held["messages"][m["id"]] = m
        if old:
            n = 0
            while n < min(len(old["parts"]), len(m["parts"])) and old["parts"][n] == m["parts"][n]:
                n += 1
            if n:
                m = {**m, "from": n, "parts": m["parts"][n:]}
        msgs.append(m)
    return {**fields, "messages": msgs} if fields or msgs else None


# a tool output that carries an image: Claude Code's Read of an image file ({"type":"image","file":{"base64":…}}),
# MCP results with image content blocks (Playwright screenshots), data: URLs
IMAGE_HINT = re.compile(r'"type"\s*:\s*"image"|"isImage"\s*:\s*true|data:image/|"(?:base64|data)"\s*:\s*"(?:iVBORw0KGgo|/9j/|R0lGOD|UklGR)')
IMAGE_MAGIC = (("iVBORw0KGgo", "image/png"), ("/9j/", "image/jpeg"), ("R0lGOD", "image/gif"), ("UklGR", "image/webp"))
TOOL_IMAGE_MAX = 4


def checkout_roots(detail):
    """Absolute directories of the card's checkout (worktree first): the page turns an absolute image path
    the agent writes in its reply into the path relative to the checkout that `dieter file` wants."""
    card = detail.get("card") or detail.get("chat") or {}
    proj = detail.get("project") or {}
    roots = []
    if (card.get("workspaceMode") or (card.get("workspace") or {}).get("mode")) == "worktree" and card.get("id"):
        roots.append(os.path.join(DIETER_HOME, "worktrees", card.get("projectId") or proj.get("id") or "", card["id"]))
    for c in proj.get("checkouts") or []:
        if c.get("path") and c.get("id") == card.get("checkoutId"):
            roots.append(c["path"])
    if proj.get("path"):
        roots.append(proj["path"])
    return [r.rstrip("/") for r in dict.fromkeys(roots) if r]


def find_images(v, out):
    """Images in a decoded tool output: dicts with base64 data and an image MIME type, data: URLs, bare base64."""
    if len(out) >= TOOL_IMAGE_MAX:
        return out
    if isinstance(v, dict):
        mime = next((x for x in (v.get(k) for k in ("mimeType", "mime_type", "media_type", "mediaType", "type"))
                     if isinstance(x, str) and x.startswith("image/")), None)
        data = next((x for x in (v.get(k) for k in ("base64", "data")) if isinstance(x, str) and len(x) > 64), None)
        if data and (mime or v.get("type") == "image"):
            out.append((mime or sniff_image(data) or "image/png", data))
            return out
        for x in v.values():
            find_images(x, out)
    elif isinstance(v, list):
        for x in v:
            find_images(x, out)
    elif isinstance(v, str) and len(v) > 64:
        m = re.match(r"data:(image/[\w.+-]+);base64,", v)
        if m:
            out.append((m.group(1), v[m.end():]))
        elif sniff_image(v):
            out.append((sniff_image(v), v))
        elif v[:1] in "[{":
            try:
                find_images(json.loads(v), out)   # MCP results nest their content as a JSON string
            except ValueError:
                pass
    return out


def sniff_image(b64):
    return next((mime for magic, mime in IMAGE_MAGIC if b64.startswith(magic)), None)


def tool_images(kind, cid, message, tool):
    """[(mime, bytes)] of one tool call's output (`card|chat tool-output`)."""
    out = run([kind, "tool-output", "--message", message, "--tool-call", tool, cid], timeout=60) or {}
    raw = out.get("outputJson") or ""
    try:
        decoded = json.loads(base64.b64decode(raw)) if raw else out.get("output")
    except ValueError:
        decoded = None
    imgs = []
    for mime, data in find_images(decoded, []):
        try:
            imgs.append((mime, base64.b64decode(data)))
        except ValueError:
            pass
    return imgs


# --------------------------------------------------------------------------- Home Assistant (add-on only)
def ha_event(name, data):
    """Fire an event in HA core through the Supervisor (needs homeassistant_api: true)."""
    if not (ADDON and SUPERVISOR_TOKEN):
        return
    try:
        rq = urllib.request.Request(f"{SUPERVISOR}/core/api/events/{name}", data=json.dumps(data, default=str).encode(),
                                    headers={"Authorization": f"Bearer {SUPERVISOR_TOKEN}", "Content-Type": "application/json"})
        urllib.request.urlopen(rq, timeout=10).read()
    except Exception as e:
        log("ha event", name, e)


def ha_api(path, body, timeout=10):
    """POST to HA core's REST API through the Supervisor; returns the response text."""
    rq = urllib.request.Request(f"{SUPERVISOR}/core/api/{path}", data=json.dumps(body).encode(),
                                headers={"Authorization": f"Bearer {SUPERVISOR_TOKEN}", "Content-Type": "application/json"})
    with urllib.request.urlopen(rq, timeout=timeout) as r:
        return r.read().decode()


# Task titles. Settings → Task titles picks who titles a card or chat created without a title:
#   "openai" (default): the daemon's --auto-title (saved at once, GPT Spark improves the title);
#   "ollama": saved at once under the first line of its task and renamed in the background with a title from
#   HA's AI Task (ai_task.generate_data) of the Ollama integration, whose first available ai_task entity is
#   looked up again every TITLE_DETECT_TTL_S, so an integration added later is picked up. Without such an
#   entity the daemon's --auto-title applies.
SETTINGS_FILE = os.environ.get("BRIDGE_SETTINGS_FILE", "/data/bridge_settings.json" if ADDON else os.path.expanduser("~/.config/dieter-bridge/settings.json"))
TITLE_MODELS = {"openai": "OpenAI", "ollama": "Ollama (HA)"}
RETENTION_DEFAULT_DAYS = 14                       # work folders of finished worktree cards, see housekeeping_loop()
RETENTION_MAX_DAYS = 3650
HOUSEKEEPING_WAKE = threading.Event()             # set when the retention changes: sweep now, not at the next hour
TITLE_DETECT_TTL_S = 600
TITLE_TIMEOUT_S = 120                             # a cold Ollama loads the model first
TITLE_MAX = 80
TITLER = {"entity": None, "at": 0}
TITLE_SLOTS = threading.Semaphore(2)              # a burst of new cards must not queue up the model server
TITLE_PENDING = {}                                # card/chat id -> provisional title, until renamed or edited


def settings():
    """The add-on's settings as the operator writes them (Settings.kt; this reads the file on every use)."""
    try:
        d = json.load(open(SETTINGS_FILE))
    except (OSError, ValueError):
        d = {}
    order = d.get("board_order")
    days = d.get("worktree_retention_days")
    return {"title_model": d.get("title_model") if d.get("title_model") in TITLE_MODELS else "openai",
            "board_order": [str(x) for x in order] if isinstance(order, list) else [],
            "worktree_retention_days": days if isinstance(days, int) and not isinstance(days, bool) and 0 <= days <= RETENTION_MAX_DAYS else RETENTION_DEFAULT_DAYS}


def order_boards(boards, order=None):
    """Boards in the order dragged on the page (settings' board_order); boards not in it (new ones) follow
    in the daemon's order, so a new board shows up last in its project."""
    rank = {bid: i for i, bid in enumerate(settings()["board_order"] if order is None else order)}
    return [b for _, b in sorted(enumerate(boards), key=lambda x: (rank.get(x[1].get("id"), len(rank)), x[0]))]


# The operator (Kotlin, host/…/operator, docs/PLAN.md Step 2) takes over the bridge one area at a time; run.sh
# starts it as this same user on loopback. Until it serves port 8099 itself, the bridge forwards the moved paths
# to it: (method, path below /api/). Agent instructions (api/prompts) moved first.
OPERATOR_URL = os.environ.get("DIETER_OPERATOR_URL", "http://127.0.0.1:8096/")
OPERATOR_PATHS = {("GET", ("prompts",)), ("POST", ("prompts",)),
                  ("GET", ("settings",)), ("POST", ("settings",)), ("POST", ("boards", "order")),
                  ("POST", ("project",)), ("POST", ("page-load",))}


def operator_request(method, path, body=None, headers=(), timeout=660):
    """(status, raw answer) of the operator for /api/…; raises URLError/OSError while it does not answer.
    A clone may take its 10 minutes."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(OPERATOR_URL.rstrip("/") + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in headers:
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def after_operator(path, before, answer):
    """What the bridge still does after the operator changed something it reads: the title model's entity is looked
    up again, a new retention sweeps now instead of at the next hour, a new board order reaches the pages at once."""
    if path == ("settings",):
        TITLER["at"] = 0
        if settings()["worktree_retention_days"] != before.get("worktree_retention_days"):
            HOUSEKEEPING_WAKE.set()
    elif path == ("boards", "order") and isinstance(answer.get("board_order"), list):
        STATE.reorder_boards(answer["board_order"])
    elif path == ("project",):
        STATE.refresh(wait=True)


def ollama_entity():
    """First available ai_task entity of HA's Ollama integration (cached for TITLE_DETECT_TTL_S)."""
    if not (ADDON and SUPERVISOR_TOKEN):
        return None
    if time.time() - TITLER["at"] > TITLE_DETECT_TTL_S:
        TITLER["at"] = time.time()
        try:
            found = json.loads(ha_api("template", {"template": "{{ integration_entities('ollama') | select('match', 'ai_task\\\\.')"
                                                                 " | reject('is_state', 'unavailable') | list | tojson }}"}))
        except Exception as e:
            found = []
            log("title: ollama lookup", e)
        entity = found[0] if found else None
        if entity != TITLER["entity"]:
            log("title: ollama ai task", entity or "none")
        TITLER["entity"] = entity
    return TITLER["entity"]


def title_entity():
    """The AI Task entity that titles new cards, or None when the daemon's --auto-title does."""
    return ollama_entity() if settings()["title_model"] == "ollama" else None


def first_line_title(prompt):
    """Provisional title: the task's first non-empty line, cut at a word boundary."""
    line = next((l for l in prompt.splitlines() if l.strip()), "")
    line = re.sub(r"\s+", " ", line.strip().lstrip("#>*- ").strip())
    if len(line) <= TITLE_MAX:
        return line or "New task"
    cut = line[:TITLE_MAX + 1].rsplit(" ", 1)[0]
    return (cut if len(cut) >= TITLE_MAX // 2 else line[:TITLE_MAX]).rstrip(" ,;:-")


def clean_title(text):
    line = next((l for l in str(text or "").splitlines() if l.strip()), "")
    line = re.sub(r"^\s*(title|titel)\s*:\s*", "", line.strip(), flags=re.I)
    line = re.sub(r"\s+", " ", line.strip(" \"'`*#„“”‚‘’«»")).rstrip(" .")
    return line[:TITLE_MAX].strip() or None


def ai_title(entity, prompt):
    instructions = ("Write a short title for the task below, as a task list would show it: at most 8 words, no quotes, "
                    "no trailing period. Write the title in the same language as the task: a German task gets a German "
                    "title, an English task an English one. Reply with the title only.\n\nTask:\n"
                    + prompt.strip()[:4000])
    res = json.loads(ha_api("services/ai_task/generate_data?return_response",
                            {"entity_id": entity, "task_name": "Dieter task title", "instructions": instructions},
                            timeout=TITLE_TIMEOUT_S))
    return clean_title(((res or {}).get("service_response") or {}).get("data"))


def retitle(kind, cid, prompt, entity):
    """Background: rename a card/chat created under its provisional title, unless someone renamed it meanwhile."""
    try:
        with TITLE_SLOTS:
            title = ai_title(entity, prompt)
        provisional = TITLE_PENDING.get(cid)
        if not title or provisional is None or title == provisional:
            return
        cur = run([kind, "show", cid], timeout=15) or {}
        if ((cur.get(kind) if isinstance(cur, dict) else None) or {}).get("title") != provisional:
            return
        run([kind, "rename", "--title", title, cid], timeout=30, parse=False)
        log("title:", cid, repr(title))
        STATE.patch(cid, title=title)
    except Exception as e:
        log("title:", cid, entity, e)
    finally:
        TITLE_PENDING.pop(cid, None)


def name_later(b):
    """POST /api/titles: a card or chat another client (the new page) created on this daemon under its provisional
    title, without Dieter's auto-title, because Ollama names them here."""
    kind, cid = b.get("kind"), str(b.get("id") or "")
    prompt, provisional = str(b.get("prompt") or ""), str(b.get("provisional") or "")
    if kind not in ("card", "chat") or not QUESTION_ID_RE.match(cid) or not prompt.strip() or not provisional.strip():
        raise ValueError("titles: {kind: card|chat, id, prompt, provisional}")
    entity = title_entity()
    if not entity:
        raise ValueError("Ollama no longer names new tasks; this one keeps its first line as title")
    TITLE_PENDING[cid] = provisional
    threading.Thread(target=retitle, args=(kind, cid, prompt, entity), daemon=True).start()
    return {"ok": True}


# --------------------------------------------------------------------------- daemon (add-on only)
def daemon_wait_healthy(timeout=180):
    """Until `dieter status` reports the daemon healthy (the operator starts and supervises it)."""
    end = time.time() + timeout
    while time.time() < end:
        try:
            st = run(["status", "--format", "json"], timeout=10) or {}
            if (st.get("health") or {}).get("status") == "ok":
                log(f"daemon: healthy, release {(st.get('health') or {}).get('release_version')}")
                return True
        except CliError:
            pass
        time.sleep(2)
    log("daemon: not healthy after", timeout, "s")
    return False


def restart_daemon():
    """The operator stops the daemon and starts it again with fresh credentials (it rewrites the env file first)."""
    try:
        operator_request("POST", "/api/daemon/restart", {}, timeout=20)
    except (urllib.error.URLError, OSError) as e:
        log("daemon: restart: operator unreachable:", e)


def housekeeping_loop():
    """Remove the work folders of worktree cards that were Done (or archived; on a Dreamfall Implementation board: started)
    and untouched for the retention set in Settings (14 days; 0 keeps everything), once an hour and right after the setting changes. The daemon keeps every
    folder until someone cleans it up; see bridge/housekeeping.py."""
    sweeper = housekeeping.Sweeper(run, log, RETENTION_DEFAULT_DAYS, implementation_boards=lambda: {
        s["board_id"] for p in dreamfall.list_pipelines() for s in p.get("stages") or [] if s.get("role") == "implementation" and s.get("board_id")})
    time.sleep(600)   # the daemon and the first board poll come first
    while True:
        sweeper.days = settings()["worktree_retention_days"]
        HOUSEKEEPING_WAKE.clear()
        if sweeper.days > 0:
            try:
                sweeper.sweep()
            except Exception as e:
                log("housekeeping: sweep failed:", e)
        HOUSEKEEPING_WAKE.wait(3600)


QUOTA_CACHE = {}   # provider -> {"at", "gen", "item"} of the local probes
QUOTA_GEN = [0]   # bumped when provider credentials change: a poll that started earlier must not refill the cache
QUOTA_TTL_S = 300


def env_file_vars():
    out = {}
    try:
        for line in open(ENV_FILE):
            if "=" in line and not line.startswith("#"):
                k, v = line.rstrip("\n").split("=", 1)
                out[k] = v
    except OSError:
        pass
    return out


def claude_credentials():
    """The container's own Claude Code login (`claude auth login`), or None."""
    try:
        with open(CLAUDE_CRED_FILE) as f:
            d = json.load(f)
        o = d.get("claudeAiOauth") or {}
        return o if o.get("accessToken") else None
    except (OSError, ValueError):
        return None


def claude_auth_status():
    o = claude_credentials()
    if o:
        return {"signed_in": True, "method": "login", "email": None, "plan": o.get("subscriptionType"),
                "expires_at": o.get("expiresAt")}
    if (OPTS.get("claude_code_oauth_token") or "").strip():
        return {"signed_in": True, "method": "token", "email": None, "plan": None, "expires_at": None}
    return {"signed_in": False, "method": None, "email": None, "plan": None, "expires_at": None}


def claude_refresh_if_due(margin_s=300):
    """Renew the login's access token shortly before it expires (what the CLI does on its own runs),
    so the quota probe keeps working while no Claude Code turn runs. Writes the file back."""
    o = claude_credentials()
    if not o or not o.get("refreshToken"):
        return False
    exp = (o.get("expiresAt") or 0) / 1000
    if exp - time.time() > margin_s:
        return False
    body = json.dumps({"grant_type": "refresh_token", "refresh_token": o["refreshToken"], "client_id": CLAUDE_OAUTH_CLIENT}).encode()
    rq = urllib.request.Request(CLAUDE_TOKEN_URL, data=body, headers={"Content-Type": "application/json", "User-Agent": "dieter-bridge/1"})
    try:
        with urllib.request.urlopen(rq, timeout=15) as r:
            t = json.load(r)
    except Exception as e:
        log("claude: token refresh failed:", str(e)[:120])
        return False
    with open(CLAUDE_CRED_FILE) as f:
        d = json.load(f)
    d["claudeAiOauth"].update(accessToken=t["access_token"], refreshToken=t.get("refresh_token") or o["refreshToken"],
                              expiresAt=int((time.time() + int(t.get("expires_in") or 3600)) * 1000))
    tmp = CLAUDE_CRED_FILE + ".tmp"
    with open(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        json.dump(d, f)
    os.replace(tmp, CLAUDE_CRED_FILE)
    log("claude: access token renewed")
    return True


class ClaudeLogin:
    """Drives `claude auth login` in a pty: the CLI prints the sign-in link and waits for the code the
    user gets after signing in on claude.com. One attempt at a time."""
    lock = threading.Lock()
    proc = None
    fd = None
    url = None
    output = ""
    done = None        # {"ok": bool, "error": str|None} once the CLI exited
    started = 0

    @classmethod
    def start(cls):
        with cls.lock:
            cls.cancel()
            cls.url, cls.output, cls.done, cls.started = None, "", None, time.time()
            env = {**os.environ, "HOME": "/data", "TERM": "dumb", "BROWSER": "/bin/false", "NO_COLOR": "1"}
            env.pop("CLAUDE_CODE_OAUTH_TOKEN", None)
            pid, fd = pty.fork()
            if pid == 0:  # child
                try:
                    os.chdir("/data")
                    os.execvpe("claude", ["claude", "auth", "login", "--claudeai"], env)
                finally:
                    os._exit(127)
            cls.proc, cls.fd = pid, fd
            threading.Thread(target=cls._reader, args=(pid, fd), daemon=True).start()
        end = time.time() + 20
        while time.time() < end and not cls.url and cls.done is None:
            time.sleep(0.2)
        return cls.status()

    @classmethod
    def _reader(cls, pid, fd):
        while True:
            try:
                chunk = os.read(fd, 4096)
            except OSError:
                chunk = b""
            if not chunk:
                break
            cls.output += chunk.decode(errors="replace")
            if not cls.url:
                m = re.search(r"https://claude\.com/[^\s\x1b\x07]+", cls.output)
                if m:
                    cls.url = m.group(0)
        try:
            _, st = os.waitpid(pid, 0)
            rc = os.waitstatus_to_exitcode(st)
        except ChildProcessError:
            rc = -1
        clean = re.sub(r"\x1b\][^\x07\x1b]*(\x07|\x1b\\)|\x1b\[[0-9;?]*[A-Za-z]", "", cls.output)
        tail = " ".join(l.strip() for l in clean.splitlines()[-4:] if l.strip())
        ok = rc == 0 and claude_credentials() is not None
        err = None if ok else re.sub(r"\bcode=\S+", "code=…", tail)[-200:] or f"claude auth login exited with {rc}"
        cls.done = {"ok": ok, "error": err}
        try:
            os.close(fd)
        except OSError:
            pass
        with cls.lock:
            if cls.proc == pid:
                cls.proc, cls.fd = None, None
        if ok:
            log("claude: signed in via claude auth login")
            on_claude_login_changed()

    @classmethod
    def code(cls, code):
        if cls.fd is None or cls.done is not None:
            return {"ok": False, "error": "no sign-in in progress; start again"}
        code = code.strip()
        if not code:
            return {"ok": False, "error": "code required"}
        try:
            os.write(cls.fd, (code + "\n").encode())
        except OSError as e:
            return {"ok": False, "error": f"could not hand the code to claude: {e}"}
        end = time.time() + 45
        while time.time() < end and cls.done is None:
            time.sleep(0.3)
        if cls.done is None:
            return {"ok": False, "error": "claude did not finish the sign-in in time"}
        return cls.done

    @classmethod
    def cancel(cls):
        if cls.proc is not None:
            try:
                os.kill(cls.proc, 9)
            except OSError:
                pass

    @classmethod
    def status(cls):
        return {"running": cls.proc is not None and cls.done is None, "url": cls.url, "done": cls.done,
                "auth": claude_auth_status()}


class Enroll:
    """Drives `dieter daemon enroll` (blocks until the user authorizes the daemon with GitHub at the
    gateway): prints the verification URL and user code, then "Enrolled NAME as ID.". One at a time;
    on success the daemon is restarted so it connects to the gateway."""
    lock = threading.Lock()
    proc = None
    url = None
    code = None
    output = ""
    done = None
    started = 0
    gateway = ""

    @classmethod
    def start(cls, gateway, name):
        gateway = (gateway or "").strip().rstrip("/")
        name = re.sub(r"[^A-Za-z0-9 ._-]", "", (name or "").strip())[:40] or "home-assistant"
        if not re.match(r"^https://[A-Za-z0-9.-]+(:\d{2,5})?$", gateway):
            raise CliError("the gateway must be an https origin such as https://gateway.example.net:4443")
        with cls.lock:
            if cls.proc is not None and cls.done is None:
                return cls.status()
            cls.url, cls.code, cls.output, cls.done, cls.started, cls.gateway = None, None, "", None, time.time(), gateway
            cls.proc = subprocess.Popen([DIETER, "--store", DIETER_HOME, "daemon", "enroll", "--gateway", gateway, "--name", name, "--no-open"],
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env={**os.environ, "HOME": "/data"})
            threading.Thread(target=cls._reader, args=(cls.proc,), daemon=True).start()
        end = time.time() + 25
        while time.time() < end and not cls.url and cls.done is None:
            time.sleep(0.2)
        return cls.status()

    @classmethod
    def _reader(cls, proc):
        for line in proc.stdout:
            cls.output += line
            if not cls.url:
                m = re.search(r"(https://\S+)", cls.output.split("Authorize this daemon with GitHub:")[-1]) if "Authorize this daemon" in cls.output else None
                if m:
                    cls.url = m.group(1)
            if not cls.code:
                m = re.search(r"Code:\s*([A-Za-z0-9-]{4,})", cls.output)
                if m:
                    cls.code = m.group(1)
        rc = proc.wait()
        ok = rc == 0 and "Enrolled " in cls.output
        m = re.search(r"Enrolled (.+?) as (\S+?)\.", cls.output)
        tail = " ".join(l.strip() for l in cls.output.splitlines()[-3:] if l.strip())[-300:]
        cls.done = {"ok": ok, "error": None if ok else (tail or f"enroll exited with {rc}"), "daemon_name": m.group(1) if m else "", "daemon_id": m.group(2) if m else ""}
        with cls.lock:
            if cls.proc is proc:
                cls.proc = None
        if ok:
            log("gateway: enrolled as", cls.done["daemon_id"], "at", cls.gateway)
            restart_daemon()
            threading.Thread(target=lambda: (time.sleep(3), daemon_wait_healthy(), STATE.touch()), daemon=True).start()

    @classmethod
    def unenroll(cls):
        """`dieter daemon unenroll`: drop the enrollment key and credential (the daemon was removed from the
        account's roster, so it cannot be approved again); the next enrollment makes a new key. Projects stay."""
        with cls.lock:
            if cls.proc is not None and cls.done is None:
                raise CliError("an enrollment is in progress")
        p = subprocess.run([DIETER, "--store", DIETER_HOME, "daemon", "unenroll"], capture_output=True, text=True, timeout=60,
                           env={**os.environ, "HOME": "/data"})
        if p.returncode != 0:
            raise CliError((p.stderr or p.stdout).strip()[:300] or f"unenroll exited with {p.returncode}")
        cls.url, cls.code, cls.output, cls.done = None, None, "", None
        log("gateway: unenrolled")
        restart_daemon()
        threading.Thread(target=lambda: (time.sleep(3), daemon_wait_healthy(), STATE.touch()), daemon=True).start()
        return {"ok": True}

    @classmethod
    def cancel(cls):
        if cls.proc is not None:
            try:
                cls.proc.kill()
            except OSError:
                pass

    @classmethod
    def status(cls):
        return {"running": cls.proc is not None and cls.done is None, "url": cls.url, "code": cls.code, "done": cls.done, "gateway": cls.gateway}


def gateway_status():
    """Enrollment of the add-on's daemon: from `dieter daemon status --format json`, plus the options."""
    out = {"option_url": (OPTS.get("gateway_url") or "").strip(), "host_ip": (OPTS.get("gateway_host_ip") or "").strip(),
           "name": (OPTS.get("machine_name") or "home-assistant").strip() or "home-assistant", "enrolled": False}
    try:
        st = run(["--store", DIETER_HOME, "daemon", "status", "--format", "json"], timeout=20) or {}
        if isinstance(st, dict):
            out.update({"enrolled": bool(st.get("enrolled")), "daemon_id": st.get("daemonId") or "", "daemon_name": st.get("daemonName") or "",
                        "url": st.get("gatewayUrl") or "", "state": st.get("gatewayState") or "", "last_error": st.get("gatewayLastError") or "",
                        "connected_at": st.get("gatewayConnectedAt")})
    except CliError as e:
        out["error"] = str(e)
    if out["enrolled"]:
        try:
            out["trust"] = _enum((run(["--store", DIETER_HOME, "device", "status", "--format", "json"], timeout=20) or {}).get("state"), "TRUST_STATE_")
        except CliError:
            pass
    out["enroll"] = Enroll.status()
    return out


def on_claude_login_changed():
    """After a sign-in or sign-out: the operator restarts the daemon with a rewritten env file (the login takes
    precedence over the option token), so the harness picks it up; the quota is probed again."""
    QUOTA_GEN[0] += 1
    QUOTA_CACHE.clear()
    restart_daemon()
    threading.Thread(target=lambda: (time.sleep(3), daemon_wait_healthy(), STATE.poll_slow()), daemon=True).start()


def claude_logout():
    ClaudeLogin.cancel()
    try:
        os.remove(CLAUDE_CRED_FILE)
    except OSError:
        pass
    log("claude: signed out")
    on_claude_login_changed()
    return claude_auth_status()


def cached_quota(provider, probe):
    """A local probe's quota item (or None), run at most every QUOTA_TTL_S per provider."""
    now, gen = time.time(), QUOTA_GEN[0]
    hit = QUOTA_CACHE.get(provider)
    if hit and now - hit["at"] < QUOTA_TTL_S and hit["gen"] == gen:
        return hit["item"]
    item = probe()
    if QUOTA_GEN[0] == gen:
        QUOTA_CACHE[provider] = {"at": now, "gen": gen, "item": item}
    return item


def local_quota(have=()):
    """Provider quotas the daemon's `quota list` lacks (it is gateway-scoped and knows no Antigravity): Claude via
    Dieter's probe quota-claude.mjs (reads CLAUDE_CODE_OAUTH_TOKEN), Antigravity via `agy --print=/quota`."""
    items = []
    if "anthropic_claude" not in have:
        items.append(cached_quota("anthropic_claude", claude_quota))
    if "antigravity" not in have and os.path.exists(ANTIGRAVITY_TOKEN_FILE):
        items.append(cached_quota("antigravity", agy_quota))
    return [i for i in items if i]


def claude_quota():
    env = {**os.environ, **env_file_vars(), "HOME": "/data"}
    probe = os.path.join(RUNTIME_DIR, "quota-claude.mjs")
    if claude_credentials():
        env.pop("CLAUDE_CODE_OAUTH_TOKEN", None)   # the probe reads /data/.claude/.credentials.json instead
        claude_refresh_if_due()
    if not (env.get("CLAUDE_CODE_OAUTH_TOKEN") or claude_credentials()) or not os.path.exists(probe):
        return None
    try:
        p = subprocess.run(["node", probe], capture_output=True, text=True, timeout=40, env=env, cwd=os.path.dirname(probe))
        a = json.loads(p.stdout.strip() or "{}")
        if p.returncode != 0 or not p.stdout.strip():
            log("quota: claude probe exited", p.returncode, (p.stderr.strip().splitlines() or ["(no output)"])[-1][:160])
        if not a.get("windows") and not claude_credentials():
            # setup-token logins are "unsupported" for Dieter's probe: fetch the usage API directly
            p = subprocess.run(["node", os.path.join(HERE, "quota_claude_token.mjs"), probe], capture_output=True, text=True, timeout=40, env=env, cwd=HERE)
            err = next((l for l in p.stderr.splitlines() if "Error" in l), (p.stderr.strip().splitlines() or ["?"])[-1])
            a = json.loads(p.stdout.strip() or "{}") if p.returncode == 0 else {"availability": err.strip()[:120]}
        if a.get("windows"):
            return {"provider": "anthropic_claude", "plan": a.get("plan"), "email": a.get("displayEmail"),
                    "available": a.get("availability") == "available",
                    "windows": [{"id": w.get("id"), "label": w.get("label"), "used": w.get("usedPercent"),
                                 "remaining": w.get("remainingPercent"), "resets_at": w.get("resetsAt")} for w in a["windows"]],
                    "refreshed_at": datetime_now()}
        if a.get("availability"):
            log("quota: claude:", a.get("availability"), "" if not a.get("displayEmail") else f"({a.get('displayEmail')})")
        elif claude_credentials():
            log("quota: claude: the probe returned no windows for the page sign-in")
    except Exception as e:
        log("quota: claude probe:", e)
    return None


def agy_quota():
    """Antigravity's quota groups: within a group the models share a weekly and a 5-hour window. A group names its
    model families in the description ("Models within this group: Gemini Flash, Gemini Pro"), Argon only itself."""
    try:
        p = subprocess.run(["agy", "--print=/quota", "--output-format", "json"], capture_output=True, text=True, timeout=60,
                           env={**os.environ, "HOME": "/data"}, cwd="/data")
        try:
            groups = ((json.loads(p.stdout).get("command") or {}).get("data") or {}).get("groups") or []
        except ValueError:
            groups = []
        windows = []
        for g in groups:
            name = (g.get("name") or "").strip()
            m = re.search(r"Models within this group:\s*(.+)", g.get("description") or "")
            families = [f.strip() for f in m.group(1).strip().rstrip(".").split(",") if f.strip()] if m else [name]
            for b in g.get("buckets") or []:
                if b.get("remaining_fraction") is None:
                    continue
                remaining = round(max(0.0, min(1.0, float(b["remaining_fraction"]))) * 100)
                span = {"weekly": "Week", "5h": "5h"}.get(b.get("window"), (b.get("name") or "").replace(" Limit Remaining", "").replace(" Limit", ""))
                windows.append({"id": b.get("id") or re.sub(r"[^a-z0-9]+", "_", f"{name} {span}".lower()).strip("_"),
                                "label": f"{name} · {span}", "used": 100 - remaining, "remaining": remaining,
                                "resets_at": b.get("reset_time"), "group": name, "models": families})
        if windows:
            return {"provider": "antigravity", "plan": None, "email": None, "available": all(w["remaining"] > 0 for w in windows),
                    "windows": windows, "refreshed_at": datetime_now()}
        log("quota: agy:", ((p.stderr or p.stdout).strip() or f"no quota groups (exit {p.returncode})")[-160:])
    except Exception as e:
        log("quota: agy:", e)
    return None


def name_words(s):
    return set(re.findall(r"[a-z0-9.]+", (s or "").lower()))


def agy_model_quota(harnesses, quota):
    """Attach to each Antigravity model its tightest quota window: those of the groups naming its family
    (all words of "Gemini Flash" in "Gemini 3.8 Flash (High)" / gemini-3.8-flash-high)."""
    windows = next((a["windows"] for a in quota if a.get("provider") == "antigravity"), [])
    for h in harnesses:
        if h.get("id") != "antigravity":
            continue
        for m in h["models"]:
            words = name_words(m.get("name")) | name_words(m.get("id"))
            mine = [w for w in windows if any(name_words(f) and name_words(f) <= words for f in w.get("models") or [])]
            low = min(mine, key=lambda w: w["remaining"]) if mine else None
            m["quota"] = {k: low[k] for k in ("remaining", "label", "resets_at", "group")} if low else None


def configured_backends():
    """The LLM backends this add-on has credentials for (the Inbox shows one usage bar per backend)."""
    out = []
    if claude_auth_status()["signed_in"]:
        out.append({"id": "anthropic_claude", "name": "Claude Code"})
    if os.path.exists(ANTIGRAVITY_TOKEN_FILE):
        out.append({"id": "antigravity", "name": "Antigravity"})
    if (OPTS.get("openai_api_key") or "").strip():
        out.append({"id": "openai_codex", "name": "OpenAI"})
    return out


def datetime_now():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def git(args, cwd=None, timeout=600):
    p = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError((p.stderr or p.stdout).strip()[:500])
    return p.stdout


UPLOADS_DIR = os.path.join(DIETER_HOME, "uploads")      # files attached on the page, handed to the CLI with --attach
UPLOAD_MAX = 20 * 1024 * 1024
UPLOAD_ID_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")


def upload_store(name, data):
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", (name or "file").strip()).strip(".-") or "file"
    uid = secrets.token_urlsafe(18)
    folder = os.path.join(UPLOADS_DIR, uid)
    os.makedirs(folder, mode=0o700, exist_ok=True)
    path = os.path.join(folder, safe[:120])
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "wb") as f:
        f.write(data)
    # forget uploads nobody sent (page closed) after a day
    now = time.time()
    for other in os.listdir(UPLOADS_DIR):
        op = os.path.join(UPLOADS_DIR, other)
        if other != uid and now - os.path.getmtime(op) > 86400:
            shutil.rmtree(op, ignore_errors=True)
    return {"id": uid, "name": os.path.basename(path), "size": len(data)}


def upload_paths(ids):
    """Upload ids from the page -> file paths for --attach (max four), or ValueError."""
    ids = [str(x) for x in (ids or []) if x]
    if len(ids) > 4:
        raise ValueError("at most four attachments")
    paths = []
    for uid in ids:
        if not UPLOAD_ID_RE.match(uid):
            raise ValueError("invalid attachment id")
        folder = os.path.join(UPLOADS_DIR, uid)
        names = os.listdir(folder) if os.path.isdir(folder) else []
        if not names:
            raise ValueError("attachment expired, add it again")
        paths.append(os.path.join(folder, names[0]))
    return paths


def upload_forget(paths):
    for path in paths:
        shutil.rmtree(os.path.dirname(path), ignore_errors=True)


def question_register(body):
    qid, session, inp = str(body.get("id") or ""), str(body.get("session") or ""), body.get("input")
    if not QUESTION_ID_RE.match(qid):
        raise ValueError("invalid question id")
    if not re.match(r"^[A-Za-z0-9._-]{1,80}$", session):
        raise ValueError("invalid session")
    if not isinstance(inp, dict) or not isinstance(inp.get("questions"), list) or not inp["questions"]:
        raise ValueError("input.questions required")
    if len(json.dumps(inp)) > 64_000:
        raise ValueError("question too large")
    q = {"id": qid, "session": session, "input": inp, "created_at": datetime_now(), "created": time.time(), "output": None}
    with QUESTIONS_LOCK:
        QUESTIONS[qid] = q
    rev, data = STATE.snapshot()
    card = next((c for c in data.get("cards", []) + data.get("chats", []) if c.get("id") == session), None)
    first = (inp["questions"][0] or {}).get("question") or ""
    log(f"question {qid}: {session} asks: {first[:80]}")
    STATE.touch()
    ha_event("dieter_question", {"id": qid, "card_id": session, "title": (card or {}).get("title") or session,
                                 "question": first[:300], "count": len(inp["questions"])})
    return {"ok": True}


def question_answer(qid, body):
    action = str(body.get("action") or "")
    if action not in QUESTION_ACTIONS:
        raise ValueError("action must be one of " + ", ".join(QUESTION_ACTIONS))
    output = {"action": action}
    if action in ("answered", "partially-answered"):
        answers = body.get("answers")
        if not isinstance(answers, dict):
            raise ValueError("answers required")
        clean = {}
        for key, a in answers.items():
            if not isinstance(a, dict):
                continue
            ids = [str(x) for x in (a.get("optionIds") or []) if isinstance(x, (str, int))]
            entry = {"optionIds": ids}
            if isinstance(a.get("freeform"), str) and a["freeform"].strip():
                entry["freeform"] = a["freeform"][:4000]
            if ids or "freeform" in entry:
                clean[str(key)] = entry
        output["answers"] = clean
    with QUESTIONS_LOCK:
        q = QUESTIONS.get(qid)
        if not q:
            raise ValueError("no such question (the agent may have moved on)")
        if q["output"] is not None:
            raise ValueError("already answered")
        q["output"] = output
    log(f"question {qid}: {action}")
    STATE.touch()
    return {"ok": True}


def question_poll(qid):
    """The worker's poll: hands the answer over once and forgets the question."""
    with QUESTIONS_LOCK:
        q = QUESTIONS.get(qid)
        if not q:
            return None
        if q["output"] is None:
            return {"done": False}
        del QUESTIONS[qid]
    STATE.touch()
    return {"done": True, "output": q["output"]}


def pending_questions():
    now = time.time()
    with QUESTIONS_LOCK:
        for qid, q in list(QUESTIONS.items()):
            if now - q["created"] > QUESTION_TTL_S:
                del QUESTIONS[qid]
        return [{k: q[k] for k in ("id", "session", "input", "created_at")} for q in QUESTIONS.values() if q["output"] is None]


SSH_DIR = "/data/.ssh"                                   # HOME=/data: ssh and git find it without any env
SSH_KEY = os.path.join(SSH_DIR, "id_ed25519")
SSH_TARGET_RE = re.compile(r"^(?:[A-Za-z0-9._-]{1,64}@)?[A-Za-z0-9][A-Za-z0-9.:-]{0,253}(?::\d{1,5})?$")


def setup_ssh():
    """One ed25519 key for this add-on in /data/.ssh (survives updates), generated on first start.
    Users authorize its public key on the machines Dieter should reach (Git remotes, other daemons);
    first connections record the host key (accept-new), later ones verify it."""
    os.makedirs(SSH_DIR, mode=0o700, exist_ok=True)
    os.chmod(SSH_DIR, 0o700)
    if not os.path.exists(SSH_KEY):
        subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "dieter@home-assistant", "-f", SSH_KEY],
                       check=True, capture_output=True, text=True, timeout=60)
        log("ssh: generated", SSH_KEY)
    cfg = os.path.join(SSH_DIR, "config")
    want = ("# written by the Dieter add-on\n"
            "Host *\n"
            f"  IdentityFile {SSH_KEY}\n"
            "  IdentitiesOnly yes\n"
            "  StrictHostKeyChecking accept-new\n"
            f"  UserKnownHostsFile {SSH_DIR}/known_hosts\n"
            "  ServerAliveInterval 30\n")
    if not os.path.exists(cfg) or open(cfg).read() != want:
        with open(os.open(cfg, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
            f.write(want)


def ssh_public_key():
    try:
        key = open(SSH_KEY + ".pub").read().strip()
    except FileNotFoundError:
        return {"public_key": None, "fingerprint": None}
    fp = subprocess.run(["ssh-keygen", "-l", "-f", SSH_KEY + ".pub"], capture_output=True, text=True, timeout=10)
    return {"public_key": key, "fingerprint": (fp.stdout.split() + [None, None])[1] if fp.returncode == 0 else None}


def ssh_test(target):
    """`ssh -o BatchMode=yes target uname -n` with the add-on's key: tells whether the key is authorized."""
    target = (target or "").strip()
    if not SSH_TARGET_RE.match(target):
        return {"ok": False, "error": "target must be user@host or host (optionally :port)"}
    host, _, port = target.rpartition(":") if re.search(r":\d{1,5}$", target) else (target, "", "")
    cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", *(["-p", port] if port else []), host, "uname", "-n"]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=30, env={**os.environ, "HOME": "/data"})
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "timeout after 30 s"}
    if p.returncode == 0:
        return {"ok": True, "output": p.stdout.strip()[:200]}
    return {"ok": False, "error": (p.stderr.strip().splitlines() or ["ssh failed"])[-1][:300]}


PUBLISH_MODES = ("manual", "pull_request", "push_base")   # Dieter's remote publish modes of a board or card


def publish_mode(v):
    v = str(v or "").strip()
    if v not in PUBLISH_MODES:
        raise ValueError("publish must be manual, pull_request or push_base")
    return v


def create_board(b):
    """A further board of a project (the app's project menu → New board): review workflow adds the Review lane;
    publish is the remote publish mode its cards inherit (manual unless chosen)."""
    project, name, workflow = str(b.get("project") or ""), str(b.get("name") or "").strip(), b.get("workflow") or "review"
    if not project.startswith("p_"):
        raise ValueError("project required")
    if not name or len(name) > 120:
        raise ValueError("name required (max 120 characters)")
    if workflow not in ("review", "direct"):
        raise ValueError("workflow must be review or direct")
    args = ["board", "create", "--project", project, "--name", name, "--workflow", workflow]
    if b.get("publish"):
        args += ["--remote-publish", publish_mode(b["publish"])]
    out = run(args, timeout=30)
    board = (out.get("board") or out) if isinstance(out, dict) else {}
    log(f"boards: created {name} ({workflow}) in {project} as {board.get('id')}")
    return {"ok": True, "id": board.get("id"), "name": board.get("name") or name}


def board_git(bid, b):
    """The remote publish mode of an existing board (`dieter board git`); cards created afterwards inherit it."""
    if not re.match(r"^b_[A-Za-z0-9]{6,40}$", bid or ""):
        raise ValueError("bad board")
    mode = publish_mode(b.get("publish"))
    run(["board", "git", "--remote-publish", mode, bid], timeout=30)
    log(f"boards: {bid} publishes {mode}")
    return {"ok": True, "publish": mode}


def setup_projects():
    """Projects from the add-on options (a convenience; more can be added from the page any time), cloned and
    registered by the operator (it may still be starting when the daemon is up: waits up to a minute for it)."""
    for pr in OPTS.get("projects") or []:
        body = {"git_url": pr.get("git_url") or "", "name": pr.get("name") or "", "branch": pr.get("branch") or ""}
        for attempt in range(30):
            try:
                status, raw = operator_request("POST", "/api/project", body)
            except (urllib.error.URLError, OSError) as e:
                if attempt == 29:
                    log(f"projects: {pr.get('name') or pr.get('git_url')}: operator unreachable ({e})")
                time.sleep(2)
                continue
            if status != 200:
                log(f"projects: {pr.get('name') or pr.get('git_url')}: {raw.decode(errors='replace')[:300]}")
            break


# --------------------------------------------------------------------------- workspace changes
DIFF_LIMIT = 4_000_000      # bytes of patch fetched per file for the line counts
COUNTED_FILES = 150         # files per changeset that get counts (the rest is listed without)


def workspace_cmd(args, cid, project_id):
    """`dieter workspace ...` for a card, or for its project when the daemon answers that a
    project-directory checkout's changes are project-scoped (and for project-only requests)."""
    if not cid:
        return run(args + ["--project", project_id], timeout=30)
    try:
        return run(args + [cid], timeout=30)
    except CliError as e:
        if project_id and "project-scoped" in str(e):
            return run(args + ["--project", project_id], timeout=30)
        raise


def patch_counts(patch):
    """Added / removed lines of a unified diff."""
    added = removed = 0
    for line in (patch or "").split("\n"):
        if line.startswith("+") and not line.startswith("+++"):
            added += 1
        elif line.startswith("-") and not line.startswith("---"):
            removed += 1
    return added, removed


DEVICE_ID_RE = re.compile(r"^dv_[a-z0-9]{1,64}$")
DEVICE_CODE_RE = re.compile(r"^[A-Za-z0-9]{16}$")


def _enum(v, prefix):
    v = str(v or "")
    return v[len(prefix):] if v.startswith(prefix) else v


def norm_device(d):
    return {"id": d.get("deviceId") or d.get("device_id") or "", "kind": _enum(d.get("kind"), "DEVICE_KIND_").lower(),
            "platform": d.get("platform") or "", "name": d.get("name") or "", "this": bool(d.get("thisDevice") or d.get("this_device")),
            "revoked": bool(d.get("revoked")), "added_by": d.get("addedBy") or d.get("added_by") or "", "requested_at": d.get("requestedAt") or d.get("requested_at"),
            "expires_at": d.get("expiresAt") or d.get("expires_at"), "request_id": d.get("id") if str(d.get("id") or "").startswith("pr_") else ""}


def device_status():
    """Trust state of the add-on's daemon, its devices and the devices waiting for approval.
    An add-on daemon that is not enrolled in a Dieter account has no roster: state NOT_ENROLLED."""
    try:
        st = run(["device", "status", "--format", "json"], timeout=30) or {}
    except CliError as e:
        msg = str(e)
        return {"state": "NOT_ENROLLED" if "enrolled" in msg.lower() else "UNAVAILABLE", "error": msg, "devices": [], "requests": []}
    if not isinstance(st, dict):
        return {"state": "UNAVAILABLE", "error": str(st)[:300], "devices": [], "requests": []}
    # The roster keeps revoked devices (flag "revoked"), e.g. every earlier pairing of a reinstalled app:
    # list the active ones, count the others.
    devs = [norm_device(d) for d in st.get("devices") or [] if isinstance(d, dict)]
    out = {"state": _enum(st.get("state"), "TRUST_STATE_") or "UNKNOWN", "device_id": st.get("deviceId") or "",
           "head": st.get("headSequence"), "synced_at": st.get("syncedAt"), "code": st.get("pairingCode") or st.get("code") or "",
           "devices": [d for d in devs if not d["revoked"]], "revoked_count": sum(d["revoked"] for d in devs), "requests": []}
    try:
        rq = run(["device", "requests", "--format", "json"], timeout=30) or {}
        out["requests"] = [norm_device(r) for r in unwrap(rq, "requests") if isinstance(r, dict)]
    except CliError as e:
        out["requests_error"] = str(e)
    return out


def device_approve(code):
    code = re.sub(r"[\s-]", "", str(code or "")).upper()
    if not DEVICE_CODE_RE.match(code):
        raise CliError("the code has 16 letters and digits, for example ABCD-EFGH-JKMN-PQRS")
    out = run(["device", "approve", "--format", "json", code], timeout=60)
    log("devices: approved a device")
    STATE.touch()
    return {"ok": True, "device": norm_device(out.get("device") or out) if isinstance(out, dict) else None}


def device_revoke(device_id):
    if not DEVICE_ID_RE.match(device_id):
        raise CliError("bad device id")
    run(["device", "revoke", "--format", "json", device_id], timeout=60)
    log("devices: revoked", device_id)
    STATE.touch()
    return {"ok": True}


SCHEDULE_ID_RE = re.compile(r"^sch_[a-z0-9]{1,64}$")
SCHEDULE_ACTIONS = ("run", "pause", "resume", "delete")


def _pick(d, *keys):
    for k in keys:
        if d.get(k) is not None:
            return d[k]
    return None


def norm_schedule(s):
    """One schedule as the page shows it; the CLI answers camelCase (list, create) or snake_case (show)."""
    s = s or {}
    return {"id": s.get("id"), "project_id": _pick(s, "projectId", "project_id"), "board_id": _pick(s, "boardId", "board_id"),
            "name": s.get("name") or "", "cron": s.get("cron") or "", "timezone": s.get("timezone") or "UTC",
            "enabled": bool(s.get("enabled")), "action": s.get("action") or "draft",
            "title": _pick(s, "titleTemplate", "title_template") or "", "prompt": _pick(s, "promptTemplate", "prompt_template") or "",
            "provider": s.get("provider") or "", "model": s.get("model") or "", "effort": s.get("effort") or "",
            "workspace": _pick(s, "workspaceMode", "workspace_mode") or "", "next_runs": _pick(s, "nextRuns", "next_runs") or [],
            "next_run_at": _pick(s, "nextRunAt", "next_run_at"), "last_run_at": _pick(s, "lastRunAt", "last_run_at"),
            "created_at": _pick(s, "createdAt", "created_at"), "open_card": _pick(s, "openCardPolicy", "open_card_policy") or "",
            "vault": bool(_pick(s, "vaultAccess", "vault_access")), "mail": bool(_pick(s, "mailAccess", "mail_access"))}


def norm_run(r):
    r = r or {}
    return {"id": r.get("id"), "card_id": _pick(r, "cardId", "card_id") or "", "scheduled_for": _pick(r, "scheduledFor", "scheduled_for"),
            "manual": bool(r.get("manual")), "action": r.get("action") or "", "status": r.get("status") or "", "message": r.get("message") or "",
            "started_at": _pick(r, "startedAt", "started_at"), "finished_at": _pick(r, "finishedAt", "finished_at"), "created_at": _pick(r, "createdAt", "created_at")}


def schedule_list(project_id):
    if project_id and not project_id.startswith("p_"):
        raise CliError("bad project")
    out = run(["schedule", "list", "--page-size", "100", "--format", "json"] + (["--project", project_id] if project_id else []), timeout=30) or {}
    items = [norm_schedule(s) for s in unwrap(out, "schedules") if isinstance(s, dict)]
    items.sort(key=lambda s: (not s["enabled"], s["name"].lower()))
    return {"schedules": items}


def schedule_show(sid):
    if not SCHEDULE_ID_RE.match(sid):
        raise CliError("bad id")
    out = run(["schedule", "show", "--runs", "30", sid], timeout=30) or {}
    sched = out.get("schedule") if isinstance(out, dict) and "schedule" in out else out
    runs = [norm_run(r) for r in (out.get("runs") or []) if isinstance(r, dict)] if isinstance(out, dict) else []
    runs.sort(key=lambda r: r["scheduled_for"] or "", reverse=True)
    return {"schedule": norm_schedule(sched), "runs": runs}


def schedule_preview(cron, tz):
    if not cron or len(cron) > 100 or len(tz) > 64:
        raise CliError("bad cron")
    out = run(["schedule", "preview", "--cron", cron, "--timezone", tz or "UTC", "--count", "5"], timeout=20) or {}
    return {"times": unwrap(out, "times")}


def schedule_create(b):
    for k in ("project", "board", "name", "cron", "prompt"):
        if not str(b.get(k) or "").strip():
            raise CliError(f"{k} required")
    if not str(b["project"]).startswith("p_") or not str(b["board"]).startswith("b_"):
        raise CliError("bad project or board")
    args = ["schedule", "create", "--project", b["project"], "--board", b["board"], "--name", str(b["name"]).strip()[:120],
            "--cron", str(b["cron"]).strip(), "--timezone", str(b.get("timezone") or "UTC")[:64],
            "--title", str(b.get("title") or b["name"]).strip()[:200], "--prompt", str(b["prompt"]),
            "--action", "run" if b.get("action") == "run" else "draft", "--workspace", "worktree" if b.get("workspace") == "worktree" else "project"]
    for k in ("provider", "model", "effort"):
        if b.get(k):
            args += [f"--{k}", str(b[k])]
    if b.get("enabled") is False:
        args.append("--enabled=false")
    if b.get("vault"):
        args.append("--vault=true")
    if b.get("mail"):
        args.append("--mail=true")
    out = run(args, timeout=60) or {}
    STATE.refresh(wait=True)   # the new card / schedule state must be in the next /api/state, not only after the next poll
    return {"ok": True, "schedule": norm_schedule(out.get("schedule") if isinstance(out, dict) and "schedule" in out else out)}


def schedule_action(sid, action):
    if not SCHEDULE_ID_RE.match(sid) or action not in SCHEDULE_ACTIONS:
        raise CliError("bad request")
    out = run(["schedule", action, sid], timeout=60)
    STATE.refresh(wait=True)   # the new card / schedule state must be in the next /api/state, not only after the next poll
    if action == "run":
        return {"ok": True, "run": norm_run(out if isinstance(out, dict) else {})}
    if action == "delete":
        return {"ok": True}
    return {"ok": True, "schedule": norm_schedule(out if isinstance(out, dict) else {})}


FILE_READ_MAX = 5 * 1024 * 1024


def file_scope(cid, project_id):
    """`dieter file …` scope: a card (its project directory or worktree) or a project."""
    if cid:
        return ["--card", cid]
    if not project_id or not project_id.startswith("p_"):
        raise CliError("project required")
    return ["--project", project_id]


def file_path_ok(path):
    return len(path) <= 1024 and "\0" not in path and ".." not in path.split("/")


def file_list(cid, project_id, path, hidden):
    """Entries of one directory of the checkout, directories first."""
    if not file_path_ok(path):
        raise CliError("bad path")
    args = ["file", "list", *file_scope(cid, project_id), "--format", "json"] + (["--hidden"] if hidden else [])
    out = run(args + ([path] if path else []), timeout=30) or {}
    entries = []
    for e in unwrap(out, "entries"):
        if not isinstance(e, dict):
            continue
        entries.append({"name": e.get("name") or os.path.basename(e.get("path") or ""), "path": e.get("path") or e.get("name") or "",
                        "kind": "dir" if (e.get("kind") or "").lower().startswith("dir") else "file",
                        "size": int(e.get("size") or 0), "modified_at": e.get("modifiedAt")})
    entries.sort(key=lambda e: (e["kind"] != "dir", e["name"].lower()))
    return {"path": path, "entries": entries}


def file_read(cid, project_id, path):
    """One file: text content, or base64 data for binary files; large files are only described."""
    if not path or not file_path_ok(path):
        raise CliError("bad path")
    scope = file_scope(cid, project_id)
    st = run(["file", "stat", *scope, path], timeout=30) or {}
    size = int(st.get("size") or 0)
    if (st.get("kind") or "").lower().startswith("dir"):
        raise CliError("that is a directory")
    if size > FILE_READ_MAX:
        return {"path": path, "name": os.path.basename(path), "size": size, "too_large": True}
    out = run(["file", "read", *scope, "--format", "json", path], timeout=60) or {}
    res = {"path": path, "name": out.get("name") or os.path.basename(path), "size": int(out.get("size") or size),
           "mime": out.get("mimeType") or mimetypes.guess_type(path)[0] or "", "modified_at": out.get("modifiedAt")}
    if out.get("binary"):
        res["data"] = out.get("data") or ""   # base64 (protobuf JSON)
    else:
        res["content"] = out.get("content") or ""
    return res


def changeset(cid, project_id):
    """`workspace changes` plus additions/deletions per file: the CLI lists paths and statuses only, so the
    page showed +0 −0 for everything. The counts come from each file's patch at the changeset's revision
    (a diff without the revision is refused once the checkout changed)."""
    out = workspace_cmd(["workspace", "changes"], cid, project_id)
    files = out.get("files") if isinstance(out, dict) else None
    if not files:
        return out
    rev = out.get("revision")
    args = ["workspace", "diff", "--limit", str(DIFF_LIMIT)] + (["--revision", rev] if rev else [])

    def count(f):
        try:
            d = workspace_cmd(args + ["--path", f["path"]], cid, project_id) or {}
            f["additions"], f["deletions"] = patch_counts(d.get("patch"))
            if d.get("truncated"):
                f["truncated"] = True
        except CliError as e:
            f["count_error"] = str(e)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        list(ex.map(count, [f for f in files[:COUNTED_FILES] if isinstance(f, dict) and f.get("path")]))
    return out


# --------------------------------------------------------------------------- state poller
def put_row(rows, cid, new):
    """rows with the row of id cid replaced by new in place (appended if missing, removed if new is None)."""
    out = [new if r["id"] == cid else r for r in rows if r["id"] != cid or new is not None]
    return out if new is None or any(r["id"] == cid for r in rows) else out + [new]


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.cond = threading.Condition(self.lock)
        self.rev = 0
        self.data = {"ok": False, "error": "noch nicht geladen"}
        self.slow = {"machine": None, "quota": [], "harnesses": [], "machines": [], "claude_auth": None, "backends": [], "at": 0}
        self.runtime_seen = {}       # card id -> runtime, to detect finished turns
        self.last_finished = None    # {"id","title","at","kind"}
        self.last_review = None
        self.polling = threading.Lock()   # one fast poll at a time: they would only queue up in the daemon
        self.wake = threading.Event()     # a change was reported: poll (after the rest, see loop)
        self.hurry = threading.Event()    # someone waits for that poll (refresh(wait=True)): rest POLL_GAP_S only
        self.wanted = 0                   # refresh() requests ...
        self.polled = 0                   # ... covered by a finished poll (one that started after them)
        self.want_full = False            # the next poll lists everything ...
        self.want_ids = set()             # ... or re-reads only these cards and chats
        self.base = None                  # the lists of the polls: status, projects, boards, cards (per project), chats
        self.where = {}                   # card/chat id -> (kind, project id, owner daemon id) as the last poll saw it
        self.last_full = 0
        self.project_ids = []             # the last poll's projects: their lists start without waiting for `project list`
        self.polled_lanes = {}            # card id -> lane as the last poll saw it (without patches), for dieter_card_review
        self.patches = {}                 # card id -> (fields, at): changes made here that a poll may not have seen yet
        self.added = {}                   # card id -> (card, at): cards and chats created here, the same for new ones
        self.mail = {"messages": [], "error": None, "machine": None}   # Mail.poll() keeps it; part of every /api/state

    def refresh(self, wait=False, timeout=30, ids=None):
        """Something changed: poll again soon. ids = the cards and chats that changed: the poll re-reads only these
        (`card show`, ~0.05 s for the daemon); without ids it lists everything (on a Pi with ~300 cards several
        CPU-seconds). wait=True returns once a poll that started after this call has finished, so the change is in
        the next /api/state."""
        ids = {i for i in ids or () if i} or None
        with self.lock:
            self.wanted += 1
            want = self.wanted
            if ids is None:
                self.want_full = True
            else:
                self.want_ids |= ids
        if wait:
            self.hurry.set()
        self.wake.set()
        if wait:
            with self.lock:
                self.cond.wait_for(lambda: self.polled >= want, timeout=timeout)

    def patch(self, cid, **fields):
        """Show a change made through the bridge (move, rename, archive) at once instead of after the next poll.
        Polls that started before it keep it applied, the first one that started after it has it from the daemon."""
        now = time.time()
        with self.lock:
            prev = self.patches.get(cid, ({}, 0))[0]
            self.patches[cid] = ({**prev, **fields}, now)
            if self.data.get("ok"):
                self.data = {**self.data, **{k: [{**c, **fields} if c["id"] == cid else c for c in self.data.get(k, [])]
                                             for k in ("cards", "chats")}}
                self.rev += 1
                self.cond.notify_all()
        self.refresh(ids=[cid])

    def add(self, card):
        """Show a card or chat created through the bridge at once: waiting for a poll that started after the
        create took 5-10 s (one poll is ~5 s on a Pi 5 with three projects, and a running one had to finish first).
        Polls that started before it keep it, the first one that started after it and read it has it from the daemon."""
        key = "chats" if card["kind"] == "chat" else "cards"
        with self.lock:
            self.added[card["id"]] = (card, time.time())
            if self.data.get("ok"):
                self.data = {**self.data, key: [card] + [c for c in self.data.get(key, []) if c["id"] != card["id"]]}
                self.rev += 1
                self.cond.notify_all()
        self.refresh(ids=[card["id"]])

    def snapshot(self):
        with self.lock:
            rev, data = self.rev, {**self.data, "questions": pending_questions()}
        return rev, DREAMFALL.decorate(data)   # outside the lock: the Dreamfall cache calls touch() from its threads

    def touch(self):
        """Something outside the polls changed (a question arrived or was answered): push to the page."""
        with self.lock:
            self.rev += 1
            self.cond.notify_all()

    def note(self, what, err):
        """Log a recurring poll error once per distinct message (unenrolled daemons have no quota/machines)."""
        noted = self.__dict__.setdefault("_noted", {})
        if noted.get(what) != str(err):
            noted[what] = str(err)
            log(f"{what}: {err}")

    def card_list(self, pid):
        """A project's cards. A project with Dreamfall stage boards is listed whole: the daemon reads every card file
        for each `card list` whatever its filters, so the stage boards' lanes come from this list (dreamfall.Cache.feed)
        instead of lists of their own; the state keeps CARD_LIMIT of them, as with --limit."""
        whole = pid in DREAMFALL.stage_projects()
        cards = [norm_card(c) for c in unwrap(run(["card", "list", "--project", pid, "--format", "json", "--limit",
                                                    "2000" if whole else str(CARD_LIMIT)], timeout=30), "cards")]
        if whole:
            DREAMFALL.feed(pid, cards)
        return cards[:CARD_LIMIT]

    def fetch_all(self):
        """status, projects, boards, cards and chats from the CLI. The calls run in parallel: one after the other
        they took ~7 s with three projects on a Pi 5. `card list` without --project answers for one project only
        (the CLI's default), so it runs per project and the lists are merged; the last poll's projects start at
        once, a project that is new since then once `project list` has answered. `chat list` without --project
        lists every project's chats, in ~0.4 s where each --project call took ~2 s."""
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
            f_status = ex.submit(run, ["status", "--format", "json"], timeout=15)
            f_projects = ex.submit(run, ["project", "list", "--format", "json"], timeout=15)
            f_boards = ex.submit(run, ["board", "list", "--format", "json"], timeout=15)
            f_chats = ex.submit(run, ["chat", "list", "--format", "json"], timeout=15)
            f_cards = {pid: ex.submit(self.card_list, pid) for pid in self.project_ids}
            projects = unwrap(f_projects.result(), "projects")
            for p in projects:
                if p["id"] not in f_cards:
                    f_cards[p["id"]] = ex.submit(self.card_list, p["id"])
            status = f_status.result() or {}
            boards = unwrap(f_boards.result(), "boards")
            cards = {p["id"]: f_cards[p["id"]].result() for p in projects}
            pids = {p["id"] for p in projects}
            chats = [norm_card(c, "chat") for c in unwrap(f_chats.result(), "chats")]
            chats = [c for c in chats if c["project_id"] in pids]   # as before: only the listed projects' chats
        self.project_ids = [p["id"] for p in projects]
        return {"status": status, "projects": projects, "boards": boards, "cards": cards, "chats": chats}

    def fetch_some(self, ids):
        """Re-read only the cards and chats that changed: this machine's one by one (`card show`, ~0.05 s for the
        daemon, where every list costs it 1-3 CPU-seconds on a Pi), another machine's (`card show` refuses those)
        with their project's list or the chat list. Returns (base, what it re-read) or None: list everything."""
        base, local_id = self.base, local_daemon_info()[0]
        show, projects, chats = [], set(), False
        for cid in ids:
            kind, pid, owner = self.where.get(cid) or (None, None, None)
            if kind and owner and local_id and owner != local_id:
                if kind == "chat":
                    chats = True
                elif pid in base["cards"]:
                    projects.add(pid)
                else:
                    return None
            else:
                show.append(cid)
        if len(show) > SHOW_MAX:
            return None
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
            f_show = {cid: ex.submit(run, ["card", "show", cid], timeout=15) for cid in show}
            f_lists = {pid: ex.submit(self.card_list, pid) for pid in projects}
            f_chats = ex.submit(run, ["chat", "list", "--format", "json"], timeout=15) if chats else None
            shown = {}
            for cid, f in f_show.items():
                try:
                    shown[cid] = unwrap(f.result(), "card") or None
                except CliError as e:
                    if "not found" not in str(e).lower():
                        return None      # e.g. a new card of another machine: `card show` refuses it, its project is unknown
                    shown[cid] = None    # deleted
            cards = {**base["cards"], **{pid: f.result() for pid, f in f_lists.items()}}
            chat_rows = base["chats"]
            if f_chats:
                chat_rows = [c for c in (norm_card(c, "chat") for c in unwrap(f_chats.result(), "chats")) if c["project_id"] in cards]
        gone, fresh = [], []
        for cid, raw in shown.items():
            kind = "chat" if raw and (raw.get("scope") == "chat" or not (raw.get("boardId") or raw.get("board_id"))) else "card"
            c = norm_card(raw, kind) if raw else None
            known = self.where.get(cid)
            if c is None or c["archived"]:
                if known and known[0] == "chat":
                    chat_rows = put_row(chat_rows, cid, None)
                elif known and known[1] in cards:
                    cards[known[1]] = put_row(cards[known[1]], cid, None)
                gone.append(cid)
            elif c["project_id"] not in cards:
                return None              # a project the last `project list` did not have
            elif kind == "chat":
                chat_rows = put_row(chat_rows, cid, c)
            else:
                cards[c["project_id"]] = put_row(cards[c["project_id"]], cid, c)
                fresh.append(c)
        DREAMFALL.put_cards(fresh, gone)
        return {**base, "cards": cards, "chats": chat_rows}, (set(shown), projects, chats)

    def cheap(self):
        """Whether the pending poll only re-reads cards and chats of this machine (`card show`, ~0.05 s each)."""
        with self.lock:
            if self.want_full or not self.want_ids or len(self.want_ids) > SHOW_MAX or time.time() - self.last_full > IDLE_POLL_S:
                return False
            ids = list(self.want_ids)
        local_id = local_daemon_info()[0]
        return all(not (w := self.where.get(i)) or not w[2] or not local_id or w[2] == local_id for i in ids)

    def poll_fast(self):
        """One poll: what refresh() asked for since the last one, everything when nothing was named (the timed
        poll) or the last full poll is IDLE_POLL_S old. Returns whether it listed (the expensive calls)."""
        with self.polling:
            with self.lock:
                want = self.wanted
                ids = self.want_ids
                if self.want_full or not ids or time.time() - self.last_full > IDLE_POLL_S or self.base is None:
                    ids = None
                self.want_full, self.want_ids = False, set()
            try:
                return self._poll_fast(ids)
            finally:
                with self.lock:
                    self.polled = max(self.polled, want)
                    self.cond.notify_all()

    def _poll_fast(self, ids=None):
        started = time.time()
        try:
            got = None
            if ids is not None:
                try:
                    got = self.fetch_some(ids)
                except CliError as e:
                    log(f"poll: re-reading {len(ids)} cards failed, listing everything: {e}")
            if got is None:
                base, (shown, listed, chats_listed) = self.fetch_all(), (set(), None, True)
                self.last_full = time.time()
            else:
                base, (shown, listed, chats_listed) = got
        except CliError as e:
            with self.lock:
                if self.data.get("ok") or self.data.get("error") != str(e):
                    self.data = {**self.data, "ok": False, "error": str(e), "generated_at": time.time()}
                    self.rev += 1
                    self.cond.notify_all()
            return True
        self.base = base
        status, projects, boards = base["status"], base["projects"], base["boards"]
        cards = [dict(c) for p in projects for c in base["cards"].get(p["id"], [])]   # copies: the lists are reused
        chats = [dict(c) for c in base["chats"]]
        self.where = {**{c["id"]: ("card", c["project_id"], c.get("owner_daemon_id")) for c in cards},
                      **{c["id"]: ("chat", c["project_id"], c.get("owner_daemon_id")) for c in chats}}

        def covered(cid):   # whether this poll read the card from the daemon
            w = self.where.get(cid)
            return listed is None or cid in shown or (w and (chats_listed if w[0] == "chat" else w[1] in listed))
        # Checkouts and cards of a machine identity the account no longer knows (e.g. the add-on's daemon
        # was removed and re-enrolled) cannot be used: hide such checkouts, mark such cards as orphaned.
        local_id, enrolled = local_daemon_info()
        known = {m.get("id") for m in (self.slow.get("machines") or []) if m.get("id")} if enrolled else set()
        if enrolled and local_id:
            known.add(local_id)
        for c in cards + chats:
            c["orphaned"] = bool(known) and bool(c.get("owner_daemon_id")) and c.get("owner_daemon_id") not in known
        pmap = {p["id"]: p for p in projects}
        now = time.time()
        finished = None
        review = None
        seq_seen = self.__dict__.setdefault("seq_seen", {})
        for c in cards + chats:
            prev = self.runtime_seen.get(c["id"])
            seq = c.get("response_seq")
            seq = int(seq) if str(seq or "").isdigit() else None
            prev_seq = seq_seen.get(c["id"])
            # a turn ended: the card left "running", or (turns shorter than a poll) its response counter advanced
            if c["runtime"] != "running" and ((prev == "running") or (seq is not None and prev_seq is not None and seq > prev_seq)):
                finished = {"id": c["id"], "kind": c["kind"], "title": c["title"], "at": now, "lane": c["lane"],
                            "project": (pmap.get(c["project_id"]) or {}).get("name")}
            self.runtime_seen[c["id"]] = c["runtime"]
            if seq is not None:
                seq_seen[c["id"]] = seq
        with self.lock:
            prev_lanes = self.polled_lanes if self.data.get("ok") else {}
        for c in cards:
            if c["lane"] == "review" and prev_lanes and prev_lanes.get(c["id"]) not in (None, "review"):
                review = {"id": c["id"], "title": c["title"], "at": now, "project": (pmap.get(c["project_id"]) or {}).get("name")}
        data = {
            "ok": True, "error": None, "generated_at": now,
            "daemon": {"health": (status.get("health") or {}).get("status"), "version": (status.get("health") or {}).get("release_version"),
                       "route": status.get("route"), "counts": {k: status.get(k) for k in ("projects", "boards", "cards", "chats")}},
            "projects": [{"id": p["id"], "name": p.get("name"), "path": p.get("path"), "boards": p.get("boardCount"),
                          "checkouts": [{"id": c.get("id"), "name": c.get("name"), "path": c.get("path"), "daemon_id": c.get("daemonId") or c.get("daemon_id")}
                                        for c in p.get("checkouts", []) or [] if not known or (c.get("daemonId") or c.get("daemon_id")) in known]}
                         for p in projects],
            "boards": [{"id": b.get("id"), "name": b.get("name"), "project_id": b.get("project_id") or b.get("projectId"),
                        "workflow": b.get("workflow"), "lanes": [l.get("id") for l in b.get("lanes", []) or []],
                        "publish": b.get("remotePublishMode") or b.get("remote_publish_mode") or "manual"} for b in boards],
            "cards": cards, "chats": chats, "addon": ADDON,
            "machine": self.slow["machine"], "quota": self.slow["quota"], "harnesses": self.slow["harnesses"],
            "machines": self.slow["machines"], "claude_auth": self.slow.get("claude_auth"), "backends": self.slow.get("backends", []),
        }
        if finished:
            ha_event("dieter_turn_finished", finished)
        if review:
            ha_event("dieter_card_review", review)
        with self.lock:
            if finished:
                self.last_finished = finished
            if review:
                self.last_review = review
            data["last_finished"] = self.last_finished
            data["last_review"] = self.last_review
            data["boards"] = order_boards(data["boards"])   # under the lock: a reorder during this poll is not undone
            data["mail"] = self.mail
            self.polled_lanes = {c["id"]: c["lane"] for c in cards}
            for cid, (card, at) in list(self.added.items()):
                if at < started and covered(cid):              # this poll asked the daemon after the create
                    del self.added[cid]
                    continue
                rows = cards if card["kind"] == "card" else chats
                if not any(c["id"] == cid for c in rows):
                    rows.insert(0, card)
            for cid, (fields, at) in list(self.patches.items()):
                if at < started and covered(cid):              # this poll asked the daemon after the change
                    del self.patches[cid]
                    continue
                for c in cards + chats:
                    if c["id"] == cid:
                        c.update(fields)
            changed = json.dumps({k: v for k, v in data.items() if k != "generated_at"}, sort_keys=True, default=str) != \
                      json.dumps({k: v for k, v in self.data.items() if k != "generated_at"}, sort_keys=True, default=str)
            self.data = data
            if changed:
                self.rev += 1
                self.cond.notify_all()
        return listed is None or bool(listed) or chats_listed

    def set_mail(self, mail):
        """A new mail listing (Mail.poll): push it to the pages if it differs."""
        with self.lock:
            if mail == self.mail:
                return
            self.mail = mail
            if self.data.get("ok"):
                self.data = {**self.data, "mail": mail}
                self.rev += 1
                self.cond.notify_all()

    def reorder_boards(self, order):
        """Apply a new board order to the current state at once (no CLI round trip) and push it to the pages."""
        with self.lock:
            if self.data.get("boards"):
                self.data = {**self.data, "boards": order_boards(self.data["boards"], order)}
                self.rev += 1
                self.cond.notify_all()

    def poll_slow(self):
        try:
            machine = norm_machine(run(["machine", "info"], timeout=20))
        except CliError as e:
            machine = {"error": str(e)}
            self.note("machine", e)
        try:
            quota = norm_quota(run(["quota", "list", "--format", "json"], timeout=20))
        except CliError as e:
            quota = []
            self.note("quota", e)
        if ADDON:
            try:
                quota = quota + local_quota({a["provider"] for a in quota})
            except Exception as e:
                self.note("quota-local", e)
        try:
            ml = run(["machine", "list", "--format", "json"], timeout=20) or {}
            machines = [{"id": d.get("id"), "name": d.get("name"), "online": bool(d.get("online")), "last_seen_at": d.get("lastSeenAt"),
                         "version": d.get("releaseVersion"),
                         "route": ", ".join(sorted({c.get("network", "") for c in d.get("directCandidates", []) or []} - {"", "loopback"})) or "relay"}
                        for d in unwrap(ml, "daemons")]
        except CliError as e:
            machines = []
            self.note("machines", e)
        try:
            h = run(["harness", "list", "--format", "json"], timeout=15) or {}
            # effort levels are declared per harness (effort.options) and only sometimes per model
            harnesses = [{"id": x.get("id"), "name": x.get("name"), "default_model": x.get("defaultModel"),
                          "efforts": [o.get("id") for o in ((x.get("effort") or {}).get("options") or []) if o.get("id")],
                          "models": [{"id": m.get("id"), "name": m.get("name"),
                                      "efforts": m.get("efforts") or [o.get("id") for o in ((x.get("effort") or {}).get("options") or []) if o.get("id")]}
                                     for m in x.get("models", []) or []]}
                         for x in unwrap(h, "harnesses")]
        except CliError as e:
            harnesses = []
            self.note("harness", e)
        agy_model_quota(harnesses, quota)
        if not machines and isinstance(machine, dict) and not machine.get("error"):
            # unenrolled daemon (no gateway): `machine list` is empty, so list this machine from `machine info`
            machines = [{"id": "local", "name": "Home Assistant" if ADDON else (machine.get("hostname") or "local"), "online": True,
                         "last_seen_at": machine.get("collected_at"), "version": machine.get("daemon_version"), "route": "local"}]
        claude_auth = claude_auth_status() if ADDON else None
        backends = configured_backends() if ADDON else [{"id": q["provider"], "name": q["provider"]} for q in quota]
        with self.lock:
            self.slow = {"machine": machine, "quota": quota, "harnesses": harnesses, "machines": machines, "claude_auth": claude_auth, "backends": backends, "at": time.time()}
            if self.data.get("ok"):
                self.data = {**self.data, "machine": machine, "quota": quota, "harnesses": harnesses, "machines": machines, "claude_auth": claude_auth, "backends": backends}
                self.rev += 1
                self.cond.notify_all()

    def loop(self):
        """Fast polls: after a reported change (refresh()), else every IDLE_POLL_S while the change stream runs and
        every POLL_S without it. A poll starts POLL_GAP_S after the previous one at the earliest (a burst of changes
        is one poll), and after a poll that listed, POLL_REST times as long as that one took, unless all it has to do
        is re-read this machine's cards or someone waits for it. On a Pi with ~300 cards every list costs the daemon
        1-3 CPU-seconds, and with 0.6.429.41-.45 a full poll after every change frame (one every few seconds per
        running agent) kept 2-4 of its cores busy."""
        threading.Thread(target=self.slow_loop, daemon=True).start()
        last, rest = 0, POLL_GAP_S
        while True:
            self.wake.wait(max(0, last + (IDLE_POLL_S if Watch.live else POLL_S) - time.time()))
            if not self.hurry.is_set() and not self.cheap():
                self.hurry.wait(max(0, last + rest - time.time()))
            time.sleep(max(0, last + POLL_GAP_S - time.time()))
            self.wake.clear()
            self.hurry.clear()
            began = time.time()
            listed = self.poll_fast()
            last = time.time()
            rest = max(POLL_GAP_S, (last - began) * POLL_REST) if listed else POLL_GAP_S

    def slow_loop(self):
        """Machine, quota, harnesses: on their own thread, so a slow quota probe (up to a minute) does not hold up the board."""
        while True:
            self.poll_slow()
            # the first `harness list` / `machine info` after a daemon start often time out: retry quickly while the
            # catalog or the machine list is empty (else Machines shows "No machines" for a whole slow round)
            time.sleep(SLOW_POLL_S if self.slow["harnesses"] and self.slow["machines"] else 10)

    def wait_change(self, rev, timeout):
        with self.lock:
            self.cond.wait_for(lambda: self.rev != rev, timeout=timeout)
            return self.rev


# --------------------------------------------------------------------------- Dieter Mail
MAIL_ID = re.compile(r"^ml_[0-9a-zA-Z]{1,64}$")
MAIL_FOLDERS = ("inbox", "archive", "trash")


def norm_mail(h):
    """A message header of `dieter mail list` (proto JSON: false and empty fields are left out)."""
    s = h.get("sender") or {}
    return {"id": h.get("id"), "subject": h.get("subject") or "", "preview": h.get("preview") or "",
            "important": _enum(h.get("importance"), "MAIL_IMPORTANCE_") == "HIGH", "sent_at": h.get("sentAt"),
            "read": bool(h.get("read")), "flagged": bool(h.get("flagged")),
            "folder": (_enum(h.get("folder"), "MAIL_FOLDER_").lower() or "inbox").replace("unspecified", "inbox"),
            "bytes": h.get("bodyBytes") or 0, "digest": h.get("bodyDigest") or "",
            "sender": {"agent": _enum(s.get("kind"), "MAIL_SENDER_KIND_") != "OPERATOR",
                       **{k: s.get(c) or "" for k, c in (("card_id", "cardId"), ("scope", "scope"), ("title", "title"),
                                                         ("project_id", "projectId"), ("project_name", "projectName"),
                                                         ("checkout_id", "checkoutId"), ("schedule_id", "scheduleId"),
                                                         ("schedule_name", "scheduleName"), ("harness", "harness"),
                                                         ("model", "model"), ("label", "label"), ("device_id", "deviceId"))}}}


class Mail:
    """This machine's Dieter Mail (`dieter mail`). The bridge runs outside agent turns, so it is the operator and may
    read and organize the mailbox. Messages live only on the machine that received them and the change stream does
    not carry them: a listing every MAIL_POLL_S, and at once after a change made here. One lock serializes listings
    and changes, so a listing that started before a change never overwrites it."""
    lock = threading.Lock()
    wake = threading.Event()

    @staticmethod
    def machine_name():
        local_id, _ = local_daemon_info()
        machines = STATE.slow.get("machines") or []
        m = next((m for m in machines if m.get("id") == local_id), None) or (machines[0] if len(machines) == 1 else None)
        return (m or {}).get("name") or ("Home Assistant" if ADDON else os.uname().nodename)

    @classmethod
    def poll(cls):
        try:
            out = run(["mail", "list", "--folder", "all", "--limit", "500", "--format", "json"], timeout=20)
            mail = {"messages": [norm_mail(h) for h in unwrap(out, "messages") if isinstance(h, dict) and h.get("id")],
                    "error": None, "machine": cls.machine_name()}
        except CliError as e:
            STATE.note("mail", e)
            mail = {**STATE.mail, "error": str(e)}
        STATE.set_mail(mail)

    @classmethod
    def loop(cls):
        while True:
            with cls.lock:
                cls.poll()
            cls.wake.wait(MAIL_POLL_S)
            cls.wake.clear()

    @classmethod
    def show(cls, mid):
        if not MAIL_ID.match(mid or ""):
            raise ValueError("bad mail id")
        out = run(["mail", "show", "--format", "json", mid], timeout=20) or {}
        return {"header": norm_mail(out.get("header") or {}), "body": out.get("body") or ""}

    @classmethod
    def ids(cls, b):
        ids = [str(i) for i in (b.get("ids") or [])]
        if not ids or len(ids) > 500 or not all(MAIL_ID.match(i) for i in ids):
            raise ValueError("ids: 1 to 500 mail ids")
        return ids

    @classmethod
    def change(cls, args):
        with cls.lock:
            run(args, timeout=30, parse=False)
            cls.poll()
        return {"ok": True}

    @classmethod
    def mark(cls, b):
        args = ["mail", "mark"]
        for key, on, off in (("read", "--read", "--unread"), ("flagged", "--flag", "--unflag")):
            if b.get(key) is not None:
                args.append(on if b[key] else off)
        if b.get("folder"):
            if b["folder"] not in MAIL_FOLDERS:
                raise ValueError("folder must be one of " + ", ".join(MAIL_FOLDERS))
            args += ["--folder", b["folder"]]
        if len(args) == 2:
            raise ValueError("read, flagged or folder required")
        return cls.change(args + cls.ids(b))

    @classmethod
    def delete(cls, b):
        return cls.change(["mail", "delete", "--empty-trash"] if b.get("empty_trash") else ["mail", "delete", *cls.ids(b)])


STATE = State()


# --------------------------------------------------------------------------- Dreamfall boards (bridge/dreamfall.py)
DREAMFALL = dreamfall.Cache(norm_card, on_change=lambda: STATE.touch(), on_boards=lambda: STATE.refresh(), log=log)


def df_poll():
    """Boards made or removed here and run cards started here must be in the next /api/state: a full poll, which
    reads the stage boards too (their project is listed whole, see State.card_list)."""
    STATE.refresh(wait=True)


def df_apply(res):
    """A person's decision is shown from what act() reported, not after the boards are listed again: the new copy
    is read once (`card show`, fast), the moved cards change lane in the Dreamfall cache and in the state; the
    cards are re-read in the background (`card show`). df_poll waited for ~8 `card list` calls and a full state poll
    (5-10 s on a Pi) before the page saw a pick."""
    lanes = res.pop("lanes", None) or {}
    cards = []
    if res.get("id"):
        try:
            cards.append(norm_card(unwrap(run(["card", "show", res["id"]], timeout=20), "card")))
        except Exception as e:
            log(f"dreamfall: card show {res['id']}: {e}")
    DREAMFALL.apply_local({k: v for k, v in lanes.items() if k != res.get("id") or not cards}, cards)
    for cid, lane in lanes.items():
        if cid != res.get("id") or not cards:
            STATE.patch(cid, lane=lane)
    STATE.refresh(ids=[*lanes, res.get("id")])
    res.pop("boards", None)


def df_sync(*keys):
    """Show records written here at once instead of when the kv watch delivers them."""
    for k in keys:
        if k:
            try:
                val, _ = dreamfall.kv_get(k)
                DREAMFALL.put_local(k, val)
            except dreamfall.DfError as e:
                log(f"dreamfall: {k}: {e}")


def dreamfall_post(path, body, user):
    """POST /api/dreamfall[/ID/ACTION]: the page's Dreamfall actions (the person; agents use the `dreamfall` command)."""
    try:
        if not path:
            p = dreamfall.create_pipeline(body, user)
            DREAMFALL.put_local("p." + p["id"], p)
            df_poll()
            log(f"dreamfall: {p['name']} ({p['id']}) created in {p['project_id']}")
            return {"ok": True, "id": p["id"], "pipeline": p}
        pid, action = path[0], (path[1] if len(path) > 1 else "")
        if action == "update":
            p = dreamfall.update_pipeline(pid, body)
            DREAMFALL.put_local("p." + pid, p)
            return {"ok": True, "pipeline": p}
        if action == "delete":
            res = dreamfall.delete_pipeline(pid)
            DREAMFALL.put_local("p." + pid, None)
            df_poll()
            log(f"dreamfall: {pid} removed, its boards stay")
            return res
        if action == "run":
            res = dreamfall.run_now(pid, str(body.get("stage") or ""))
            df_poll()
            return res
        if action == "act":
            res = dreamfall.act(pid, body, user, known=DREAMFALL.cards_of)
            df_sync("c." + str(body.get("card") or ""), ("c." + res["id"]) if res.get("id") else "")
            df_apply(res)
            res["rev"] = STATE.rev    # the state that shows the change: the page keeps the card busy until it has it
            return res
        raise ValueError("not found")
    except dreamfall.DfError as e:
        raise ValueError(str(e))


def dreamfall_cards(data):
    """The open cards on Dreamfall stage boards (ideas and run cards) with the start of their brief: what the new
    page's add-on core draws Dreamfall boards from, without the rest of the cards."""
    boards = {s.get("board_id") for p in (data.get("dreamfall") or {}).get("pipelines") or [] for s in p.get("stages") or []}
    return [{**c, "initial_prompt": (c.get("initial_prompt") or "")[:1200]}
            for c in data.get("cards") or [] if c.get("board_id") in boards and not c.get("archived")]


# /api/events?fields=: keys computed from the state instead of taken from it
DERIVED_FIELDS = {"dreamfall_cards": dreamfall_cards}


def dreamfall_detail(cid):
    rev, data = STATE.snapshot()
    d = DREAMFALL.detail(cid, data.get("cards") or [])
    for n in d["chain"]:
        if not n["known"] and dreamfall.CARD_RE.match(n["card"] or ""):
            try:
                c = dreamfall.card_show(n["card"])
                n.update(title=c["title"], lane="archived" if c["archived"] else c["lane"], board_id=c["board_id"])
                if n.get("self") and d.get("brief") is None:
                    d["brief"] = c["prompt"]
            except dreamfall.DfError:
                pass
    return d


def dreamfall_created(cid, b, user):
    """A card the person adds to a Dreamfall stage board from the page gets a record (created by the person), so the
    board can tell it from a card that arrived some other way (e.g. an agent's `dieter card create`)."""
    board_id = b.get("board")
    p = next((x for x in DREAMFALL.pipelines() if dreamfall.stage_of_board(x, board_id)), None)
    if not p:
        return
    s = dreamfall.stage_of_board(p, board_id)
    if s["actor"] == "agent" and b.get("start", True):
        return     # an agent turn started by hand on an agent stage is a run, not an idea
    try:
        dreamfall.record_created(p, {"id": cid, "board_id": board_id, "title": str(b.get("title") or "")}, "human", user)
        df_sync("c." + cid)
    except dreamfall.DfError as e:
        log(f"dreamfall: record of {cid}: {e}")


class Watch:
    """`dieter watch changes`: the daemon's change frames (the stream the native apps hold). What a frame changed on
    the board decides the poll (classify): the cards and chats it names are re-read one by one, a change of projects,
    boards or checkouts lists everything. Not counted: activity frames that only stream a running turn's text (the open
    conversation has its own stream), a card summary whose only news is its lastActivityAt (a running agent bumps it
    every ~2 s), an owned card whose only news is its updatedAt and token usage (every 5-10 s per running agent: each
    started a full poll, ~10 full reads of the card files, and kept a Pi's daemon at 2-4 cores, 0.6.429.41-.45), the
    Dreamfall records (kv, its cache watches them itself) and schedules. Activity and token counts come with the
    timed full poll."""
    live = False   # caught up and running: the timed poll is only a safety net
    QUIET_OWNED = ("updatedAt", "tokenUsage")

    def __init__(self):
        self.summaries, self.owned, self.checkouts, self.status = {}, {}, {}, {}

    @staticmethod
    def summary_key(record):
        try:
            values = [json.loads(base64.b64decode(v.get("valueJson") or "") or b"null") for v in record.get("versions") or []]
        except ValueError:
            return None
        return json.dumps([{k: x for k, x in v.items() if k != "lastActivityAt"} if isinstance(v, dict) else v for v in values], sort_keys=True)

    def classify(self, frame):
        """(full, ids): whether the frame changed projects, boards or checkouts (list everything), and which cards
        and chats it changed (re-read those)."""
        full = any(frame.get(k) for k in ("resetRecords", "resetLocal"))
        ids = set()
        for r in frame.get("records") or []:
            if not isinstance(r, dict):
                full = True
                continue
            kind, rid = str(r.get("kind") or ""), str(r.get("id") or "")
            if kind.startswith("kv.") or kind == "schedule":
                continue
            cid, _, field = rid.partition(".")
            if kind not in ("item", "") or not cid.startswith("c_"):
                full = True
                continue
            if field == "summary":
                key = self.summary_key(r)
                if key is not None and self.summaries.get(rid) == key:
                    continue
                self.summaries[rid] = key
            ids.add(cid)
        for c in frame.get("ownedCards") or []:
            if not isinstance(c, dict) or not c.get("id"):
                continue
            key = json.dumps({k: v for k, v in c.items() if k not in self.QUIET_OWNED}, sort_keys=True, default=str)
            if self.owned.get(c["id"]) != key:
                self.owned[c["id"]] = key
                ids.add(c["id"])
        for c in frame.get("ownedCheckouts") or []:
            key = json.dumps(c, sort_keys=True, default=str)
            co = c.get("id") if isinstance(c, dict) else key
            if self.checkouts.get(co) != key:
                self.checkouts[co] = key
                full = True
        for a in frame.get("activities") or []:
            if isinstance(a, dict) and a.get("cardId") and self.status.get(a["cardId"]) != a.get("status"):
                self.status[a["cardId"]] = a.get("status")
                ids.add(a["cardId"])
        return full, ids

    @classmethod
    def loop(cls):
        backoff = 2
        while True:
            started = time.time()
            rc = None
            try:
                p = subprocess.Popen([DIETER, "watch", "changes"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
                w = cls()
                for line in p.stdout:
                    try:
                        frame = json.loads(line)
                    except ValueError:
                        continue
                    full, ids = w.classify(frame)
                    if frame.get("caughtUp"):   # before that, the replay of what the daemon holds
                        if not cls.live:
                            cls.live = True
                            log("watch: following the daemon's changes")
                            full = True
                        if full:
                            STATE.refresh()
                        elif ids:
                            STATE.refresh(ids=ids)
                rc = p.wait()
            except Exception as e:
                rc = repr(e)
            if cls.live:
                log(f"watch: ended ({rc}), polling every {POLL_S:.0f}s until it is back")
            cls.live = False
            backoff = 2 if time.time() - started > 60 else min(backoff * 2, 60)
            time.sleep(backoff)


# --------------------------------------------------------------------------- auth
WHOIS_CACHE = {}
OWNER_LOGIN = {"login": None}


def whois(ip):
    now = time.time()
    hit = WHOIS_CACHE.get(ip)
    if hit and hit[0] > now:
        return hit[1]
    login = None
    try:
        p = subprocess.run(["tailscale", "whois", "--json", ip], capture_output=True, text=True, timeout=5)
        if p.returncode == 0:
            login = ((json.loads(p.stdout).get("UserProfile") or {}).get("LoginName"))
    except Exception as e:
        log("whois", ip, e)
    WHOIS_CACHE[ip] = (now + 600, login)
    return login


def owner_login():
    if OWNER_LOGIN["login"]:
        return OWNER_LOGIN["login"]
    try:
        p = subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True, timeout=5)
        d = json.loads(p.stdout)
        uid = (d.get("Self") or {}).get("UserID")
        prof = (d.get("User") or {}).get(str(uid)) or {}
        OWNER_LOGIN["login"] = prof.get("LoginName")
    except Exception as e:
        log("owner login", e)
    return OWNER_LOGIN["login"]


def in_nets(ip, nets):
    try:
        a = ipaddress.ip_address(ip.split("%")[0])
    except ValueError:
        return False
    return any(a in n for n in nets)


# --------------------------------------------------------------------------- Compose web UI (/next/)
# The page, built from this repository's web/ with the image (tools/addon-dist.sh → dieter/web-dist → /opt/web).
WEB_DIR = os.environ.get("DIETER_WEB_DIR", "/opt/web")
# webpack's wasm assets (20 hex) and the packaged dieter.<16 hex>.js never change under their name
WEB_HASHED = re.compile(r"(^|[./])[0-9a-f]{16,}\.(wasm|js|mjs)(\.LICENSE\.txt)?(\.gz|\.br)?$")
WEB_TYPES = {
    ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8",
    ".wasm": "application/wasm", ".json": "application/json", ".txt": "text/plain; charset=utf-8",
    ".ttf": "font/ttf", ".otf": "font/otf", ".woff2": "font/woff2", ".svg": "image/svg+xml", ".png": "image/png",
    ".css": "text/css; charset=utf-8", ".xml": "application/xml",
}
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def web_file(rel):
    """Absolute path of a file of the web bundle, None if missing or outside WEB_DIR."""
    root = os.path.realpath(WEB_DIR)
    path = os.path.realpath(os.path.join(root, rel))
    if path != root and not path.startswith(root + os.sep):
        return None
    return path if os.path.isfile(path) else None


def accepted_encodings(header):
    """Content codings with q > 0 from an Accept-Encoding header."""
    out = set()
    for item in (header or "").lower().split(","):
        name, _, params = item.strip().partition(";")
        q = 1.0
        m = re.search(r"q=([0-9.]+)", params)
        if m:
            try:
                q = float(m.group(1))
            except ValueError:
                q = 0.0
        if name and q > 0:
            out.add(name.strip())
    return out


# The web host (host/ of this repository): the Compose page's core on the JVM, run by run.sh as its own user
# with a private state directory (/data/web-host: device key, gateway session). It listens on loopback and
# only answers requests that carry the per-boot secret, which run.sh hands to this bridge on an inherited
# file descriptor (never in a file or the environment the agents could read). The bridge relays
# next/core (the page's WebSocket) and next/signin (the gateway's redirect after GitHub sign-in).
WEB_HOST_ADDR = os.environ.get("DIETER_WEB_HOST_ADDR", "127.0.0.1:8097")
WEB_HOST_SECRET = None


def read_web_host_secret():
    """Takes the secret from the descriptor named by DIETER_WEB_SECRET_FD and closes it (before any child starts)."""
    global WEB_HOST_SECRET
    fd = os.environ.pop("DIETER_WEB_SECRET_FD", "")
    if not fd.isdigit():
        return
    # Agents run as this bridge's user (they descend from it through the daemon): as a non-dumpable process its
    # memory, environment and descriptors under /proc are closed to them, so the secret stays in this process.
    try:
        import ctypes
        if ctypes.CDLL(None, use_errno=True).prctl(4, 0, 0, 0, 0) != 0:   # PR_SET_DUMPABLE, 0
            log("web host: could not make the bridge non-dumpable, errno", ctypes.get_errno())
    except Exception as e:
        log("web host: prctl unavailable:", e)
    data = b""
    try:
        while True:
            chunk = os.read(int(fd), 4096)
            if not chunk:
                break
            data += chunk
    except OSError as e:
        log("web host: secret unreadable:", e)
    finally:
        try:
            os.close(int(fd))
        except OSError:
            pass
    WEB_HOST_SECRET = data.decode(errors="replace").strip() or None
    log("web host: relay to", WEB_HOST_ADDR, "enabled" if WEB_HOST_SECRET else "disabled (no secret)")


# --------------------------------------------------------------------------- http
INDEX = os.path.join(HERE, "index.html")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "dieter-bridge/1"

    def log_message(self, fmt, *args):  # quieter log: only non-200 and mutations
        if args and (str(args[1]) != "200" or self.command == "POST"):
            log(self.client_address[0], self.command, self.path, *args[1:2])

    # ---- helpers
    def client_ip(self):
        return self.client_address[0]

    def forward_operator(self, method, u, body):
        """A path the operator answers: passed on with its query, body and the Home Assistant user, answered as it answers."""
        before = settings()
        headers = [(h, self.headers.get(h)) for h in ("X-Remote-User-Id", "X-Remote-User-Name", "X-Remote-User-Display-Name") if self.headers.get(h)]
        try:
            status, raw = operator_request(method, u.path + (f"?{u.query}" if u.query else ""), body, headers)
        except (urllib.error.URLError, OSError) as e:
            log("operator:", method, u.path, e)
            return self.send_json({"error": "the add-on's operator is not running yet; try again in a moment"}, 503)
        if method == "POST" and status == 200:
            try:
                after_operator(tuple(p for p in u.path.split("/") if p)[1:], before, json.loads(raw))
            except Exception as e:
                log("operator: after", u.path, e)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def user_name(self):
        """The Home Assistant user behind Ingress (it forwards the logged-in user's name), for the Dreamfall trail."""
        return (self.headers.get("X-Remote-User-Display-Name") or self.headers.get("X-Remote-User-Name") or "you").strip()[:60] or "you"

    def caller_ip(self):
        """Real caller: X-Forwarded-For is trusted only from loopback (Caddy)."""
        ip = self.client_ip()
        xff = self.headers.get("X-Forwarded-For")
        if xff and in_nets(ip, [ipaddress.ip_network("127.0.0.0/8"), ipaddress.ip_network("::1/128")]):
            return xff.split(",")[0].strip()
        return ip

    def may_read(self):
        if ADDON:
            return in_nets(self.client_ip(), LOOPBACK + HASSIO_NETS)
        return in_nets(self.caller_ip(), READ_NETS)

    def may_write(self):
        key = self.headers.get("X-API-Key")
        if key and secrets.compare_digest(key, API_KEY):
            return True
        if ADDON:
            # Ingress only forwards logged-in HA users (X-Ingress-Path); HA core calls the rest_commands
            return in_nets(self.client_ip(), LOOPBACK + HASSIO_NETS)
        ip = self.caller_ip()
        if ip == HA_IP:
            return True
        if in_nets(ip, TS_NETS):
            login = whois(ip)
            return bool(login) and login == owner_login()
        return in_nets(ip, [ipaddress.ip_network("127.0.0.0/8")]) and not self.headers.get("X-Forwarded-For")

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False, default=str).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_image(self, mime, body, max_age):
        """Image bytes for an <img> in the chat. SVG may carry script: the sandbox CSP keeps it inert when opened directly."""
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", f"private, max-age={max_age}")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
        self.end_headers()
        self.wfile.write(body)

    def send_image_file(self, cid, path):
        """GET /api/{card|chat}/ID/image?path=REL: an image file of the checkout (Markdown ![](path) in a reply)."""
        res = file_read(cid, None, path)
        if res.get("too_large"):
            return self.send_json({"error": "file too large"}, 413)
        if not res.get("mime", "").startswith("image/") or res.get("data") is None:
            return self.send_json({"error": "not an image"}, 415)
        return self.send_image(res["mime"], base64.b64decode(res["data"]), 60)

    def send_tool_image(self, kind, cid, message, tool, i):
        """GET /api/{card|chat}/ID/tool-image?message=M&tool=T&i=N: the N-th image of a tool call's output
        (Read of an image file, screenshots of browser tools). Tool outputs don't change: cached for a day."""
        if not re.fullmatch(r"[\w.:-]{1,200}", message or "") or not re.fullmatch(r"[\w.:-]{1,200}", tool or ""):
            return self.send_json({"error": "bad id"}, 400)
        imgs = tool_images(kind, cid, message, tool)
        n = int(i) if str(i).isdigit() else 0
        if n >= len(imgs):
            return self.send_json({"error": "no image"}, 404)
        return self.send_image(imgs[n][0], imgs[n][1], 86400)

    def send_text(self, body, code=200, ctype="text/plain; charset=utf-8"):
        body = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def start_sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Connection", "close")
        self.end_headers()

    def sse(self, event, obj):
        self.wfile.write(f"event: {event}\ndata: {json.dumps(obj, ensure_ascii=False, default=str)}\n\n".encode())
        self.wfile.flush()

    def read_raw(self, limit):
        """Binary request body (uploads), chunked or with Content-Length, at most `limit` bytes."""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            raw = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    return raw
                raw += self.rfile.read(size)
                self.rfile.readline()
                if len(raw) > limit:
                    raise ValueError("file too large (20 MB at most)")
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("file too large (20 MB at most)")
        return self.rfile.read(n) if n else b""

    def read_body(self):
        # Supervisor Ingress forwards POST bodies chunked (no Content-Length)
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            raw = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    break
                raw += self.rfile.read(size)
                self.rfile.readline()
                if len(raw) > 1_000_000:
                    raise ValueError("body too large")
            return json.loads(raw or b"{}")
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1_000_000:
            raise ValueError("body too large")
        raw = self.rfile.read(n) if n else b""
        return json.loads(raw or b"{}")

    # ---- the Compose web UI (/next/)
    def serve_web(self, u, parts):
        if u.path == "/next":
            # relative Location keeps the Ingress prefix: .../<token>/next -> .../<token>/next/
            self.send_response(302)
            self.send_header("Location", "next/" + (f"?{u.query}" if u.query else ""))
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        rel = "/".join(parts[1:]) or "index.html"
        path = web_file(rel)
        if not path:
            if not os.path.isdir(WEB_DIR):
                return self.send_text("The Compose web UI is not part of this build.", 404)
            return self.send_text("not found", 404)
        name = os.path.basename(rel)
        if name.endswith(".wasm.gz"):
            ctype = "application/octet-stream"   # inflated by the loader (DecompressionStream)
        else:
            ctype = WEB_TYPES.get(os.path.splitext(name)[1].lower()) or mimetypes.guess_type(name)[0] or "application/octet-stream"
        encoding = None
        if not self.headers.get("X-Ingress-Path") and not name.endswith((".gz", ".br")):
            accepted = accepted_encodings(self.headers.get("Accept-Encoding"))
            for coding, suffix in (("br", ".br"), ("gzip", ".gz")):
                if coding in accepted and os.path.isfile(path + suffix):
                    path, encoding = path + suffix, coding
                    break
        st = os.stat(path)
        etag = f'"{st.st_size:x}-{int(st.st_mtime):x}{"-" + encoding if encoding else ""}"'
        cache = "public, max-age=31536000, immutable" if WEB_HASHED.search(name) else "no-cache"
        if etag in [t.strip() for t in (self.headers.get("If-None-Match") or "").split(",")]:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.send_header("Cache-Control", cache)
            self.send_header("Vary", "Accept-Encoding")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(st.st_size))
        self.send_header("Cache-Control", cache)
        self.send_header("ETag", etag)
        self.send_header("Vary", "Accept-Encoding")
        self.send_header("X-Content-Type-Options", "nosniff")
        if encoding:
            self.send_header("Content-Encoding", encoding)
        self.end_headers()
        with open(path, "rb") as f:
            shutil.copyfileobj(f, self.wfile, 256 * 1024)

    def web_host_relay(self, u, parts):
        """next/core (WebSocket) and next/signin to the web host on loopback, with the per-boot secret."""
        if not WEB_HOST_SECRET:
            return self.send_text("The web host is not running in this build.", 503)
        host, _, port = WEB_HOST_ADDR.rpartition(":")
        try:
            up = socket.create_connection((host, int(port)), timeout=5)
        except OSError:
            return self.send_text("The web host is starting or not running. Try again in a moment.", 503)
        upgrade = (self.headers.get("Upgrade") or "").lower() == "websocket"
        path = "/" + "/".join(parts) + (f"?{u.query}" if u.query else "")
        lines = [f"GET {path} HTTP/1.1", f"Host: {WEB_HOST_ADDR}", f"X-Dieter-Web-Secret: {WEB_HOST_SECRET}"]
        names = ["X-Remote-User-Id", "X-Remote-User-Name", "X-Remote-User-Display-Name"]
        names += ["Upgrade", "Connection", "Sec-WebSocket-Key", "Sec-WebSocket-Version"] if upgrade else []
        for name in names:
            value = self.headers.get(name)
            if value and "\r" not in value and "\n" not in value:
                lines.append(f"{name}: {value}")
        if not upgrade:
            lines.append("Connection: close")
        self.close_connection = True
        try:
            up.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1", "replace"))
            head = b""
            while b"\r\n\r\n" not in head:
                chunk = up.recv(4096)
                if not chunk:
                    raise ConnectionError("the web host closed the connection")
                head += chunk
                if len(head) > 65536:
                    raise ConnectionError("oversized response head")
            up.settimeout(None)
            if not upgrade:
                body = head
                while True:
                    chunk = up.recv(65536)
                    if not chunk:
                        break
                    body += chunk
                self.connection.sendall(body)
                return
            self.connection.sendall(head)
            if not head.startswith(b"HTTP/1.1 101"):
                return

            def downstream():   # host -> browser
                try:
                    while True:
                        chunk = up.recv(65536)
                        if not chunk:
                            break
                        self.connection.sendall(chunk)
                except OSError:
                    pass
                finally:
                    for sock in (self.connection, up):
                        try:
                            sock.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass

            t = threading.Thread(target=downstream, daemon=True)
            t.start()
            try:   # browser -> host (rfile may already hold the first frames)
                while True:
                    chunk = self.rfile.read1(65536)
                    if not chunk:
                        break
                    up.sendall(chunk)
            except (OSError, ValueError):
                pass
            finally:
                try:
                    up.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                t.join(5)
        except (ConnectionError, OSError) as e:
            log("web host relay:", e)
            try:
                if not upgrade:
                    self.send_text("The web host did not answer.", 502)
            except OSError:
                pass
        finally:
            up.close()

    def websocket_echo(self):
        """RFC 6455 echo for checking WebSockets through Ingress: text and binary frames come back."""
        key = self.headers.get("Sec-WebSocket-Key")
        if (self.headers.get("Upgrade") or "").lower() != "websocket" or not key:
            return self.send_json({"error": "WebSocket upgrade expected"}, 400)
        accept = base64.b64encode(hashlib.sha1((key.strip() + WS_GUID).encode()).digest()).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True

        def read(n):
            data = b""
            while len(data) < n:
                chunk = self.rfile.read(n - len(data))
                if not chunk:
                    raise EOFError
                data += chunk
            return data

        def send(opcode, payload):
            n = len(payload)
            head = bytes([0x80 | opcode]) + (bytes([n]) if n < 126 else bytes([126]) + n.to_bytes(2, "big") if n < 65536
                                             else bytes([127]) + n.to_bytes(8, "big"))
            self.wfile.write(head + payload)
            self.wfile.flush()

        try:
            while True:
                b0, b1 = read(2)
                opcode, n = b0 & 0x0F, b1 & 0x7F
                if n == 126:
                    n = int.from_bytes(read(2), "big")
                elif n == 127:
                    n = int.from_bytes(read(8), "big")
                if n > 1_000_000:
                    return send(0x8, (1009).to_bytes(2, "big"))
                mask = read(4) if b1 & 0x80 else b"\0\0\0\0"
                payload = bytes(c ^ mask[i % 4] for i, c in enumerate(read(n)))
                if opcode == 0x8:
                    return send(0x8, payload[:2])
                if opcode == 0x9:
                    send(0xA, payload)
                elif opcode in (0x0, 0x1, 0x2):
                    send(opcode, payload)
        except (EOFError, ConnectionError, OSError):
            return

    # ---- routing
    def do_GET(self):
        try:
            u = urllib.parse.urlsplit(self.path)
            q = urllib.parse.parse_qs(u.query)
            parts = [p for p in u.path.split("/") if p]
            if not self.may_read():
                return self.send_json({"error": "forbidden"}, 403)
            if parts[:2] in (["next", "core"], ["next", "signin"]) and len(parts) == 2:
                return self.web_host_relay(u, parts)
            if parts and parts[0] == "next":
                return self.serve_web(u, parts)
            if parts == ["api", "ws", "echo"]:
                return self.websocket_echo()
            if not parts or parts == ["index.html"]:
                return self.send_text(open(INDEX, "rb").read(), ctype="text/html; charset=utf-8")
            if parts[0] != "api":
                return self.send_json({"error": "not found"}, 404)
            if ("GET", tuple(parts[1:])) in OPERATOR_PATHS:
                return self.forward_operator("GET", u, None)
            if len(parts) == 4 and parts[1] == "dreamfall" and parts[2] == "card":
                return self.send_json(dreamfall_detail(parts[3]))
            if parts[1:] == ["machines"]:
                rev, data = STATE.snapshot()
                return self.send_json({"machines": data.get("machines", [])})
            if len(parts) == 4 and parts[1] == "machine" and parts[3] == "info":
                return self.send_json(machine_info(parts[2]))
            if len(parts) == 4 and parts[1] == "project" and parts[3] == "changes":
                return self.send_json({"raw": changeset(None, parts[2])})
            if parts[1:] == ["schedules"]:
                return self.send_json(schedule_list(q.get("project", [""])[0]))
            if parts[1:] == ["devices"]:
                return self.send_json(device_status())
            if len(parts) == 3 and parts[1] == "mail":
                if not MAIL_ID.match(parts[2]):
                    return self.send_json({"error": "bad mail id"}, 400)
                return self.send_json(Mail.show(parts[2]))
            if parts[1:] == ["gateway"] and ADDON:
                return self.send_json(gateway_status())
            if parts[1:] == ["schedules", "preview"]:
                return self.send_json(schedule_preview(q.get("cron", [""])[0], q.get("tz", ["UTC"])[0]))
            if len(parts) == 3 and parts[1] == "schedules":
                return self.send_json(schedule_show(parts[2]))
            if len(parts) == 4 and parts[1] == "project" and parts[3] == "files":
                return self.send_json(file_list(None, parts[2], q.get("path", [""])[0], q.get("hidden", ["0"])[0] == "1"))
            if len(parts) == 4 and parts[1] == "project" and parts[3] == "file":
                return self.send_json(file_read(None, parts[2], q.get("path", [""])[0]))
            if parts[1:] == ["state"]:
                rev, data = STATE.snapshot()
                return self.send_json({"rev": rev, **data})
            if parts[1:] == ["ha"]:
                return self.send_json(self.ha_summary())
            if parts[1:] == ["focus"]:
                fid = FOCUS["id"] if FOCUS["id"] and time.time() - FOCUS["at"] < FOCUS_TTL_S else None
                if q.get("consume", ["0"])[0] == "1":
                    FOCUS["id"] = None
                return self.send_json({"id": fid})
            if parts[1:] == ["events"]:
                fields = [f for f in ",".join(q.get("fields", [])).split(",") if re.match(r"^[a-z_]{1,32}$", f)]
                return self.stream_events(fields or None)
            if parts[1:] == ["claude", "login"]:
                return self.send_json(ClaudeLogin.status())
            if parts[1:] == ["ssh"] and ADDON:
                return self.send_json(ssh_public_key())
            if parts[1:] == ["questions"]:
                return self.send_json({"questions": pending_questions()})
            if len(parts) == 3 and parts[1] == "questions":
                st = question_poll(parts[2])
                return self.send_json(st if st else {"error": "no such question"}, 200 if st else 404)
            if len(parts) >= 3 and parts[1] in ("card", "chat"):
                kind, cid = parts[1], parts[2]
                if not cid.startswith("c_"):
                    return self.send_json({"error": "bad id"}, 400)
                if len(parts) == 3:
                    snap = run([kind, "poll", "--last", "200", cid], timeout=30) or {}
                    return self.send_json(norm_conversation(snap.get("snapshot", snap)))
                if parts[3] == "stream":
                    return self.stream_conversation(kind, cid, q.get("after", [None])[0], q.get("delta", ["0"])[0] == "1")
                if parts[3] == "changes" and kind == "card":
                    return self.send_json(self.changes(cid))
                if parts[3] == "files":
                    return self.send_json(file_list(cid, None, q.get("path", [""])[0], q.get("hidden", ["0"])[0] == "1"))
                if parts[3] == "file":
                    return self.send_json(file_read(cid, None, q.get("path", [""])[0]))
                if parts[3] == "image":
                    return self.send_image_file(cid, q.get("path", [""])[0])
                if parts[3] == "tool-image":
                    return self.send_tool_image(kind, cid, q.get("message", [""])[0], q.get("tool", [""])[0], q.get("i", ["0"])[0])
                if parts[3] == "diff" and kind == "card":
                    path = q.get("path", [""])[0]
                    if not path:
                        return self.send_json({"error": "path required"}, 400)
                    return self.send_json(self.diff(cid, path))
            return self.send_json({"error": "not found"}, 404)
        except CliError as e:
            return self.send_json({"error": str(e)}, 502)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log("GET error", self.path, repr(e))
            try:
                self.send_json({"error": repr(e)}, 500)
            except Exception:
                pass

    def do_POST(self):
        try:
            if not self.may_read():
                return self.send_json({"error": "forbidden"}, 403)
            if not self.may_write():
                return self.send_json({"error": "Aktionen nur von Home Assistant, vom eigenen Tailscale-Gerät oder mit API-Key"}, 403)
            parts = [p for p in urllib.parse.urlsplit(self.path).path.split("/") if p]
            if parts == ["api", "upload"]:
                name = urllib.parse.unquote(self.headers.get("X-Filename") or "file")
                return self.send_json(upload_store(name, self.read_raw(UPLOAD_MAX)))
            body = self.read_body()
            if parts[:1] != ["api"]:
                return self.send_json({"error": "not found"}, 404)
            if ("POST", tuple(parts[1:])) in OPERATOR_PATHS:
                return self.forward_operator("POST", urllib.parse.urlsplit(self.path), body)
            if parts[1:2] == ["dreamfall"]:
                return self.send_json(dreamfall_post(parts[2:], body, self.user_name()))
            if parts[1:] == ["card"] or parts[1:] == ["chat"]:
                return self.send_json(self.create(parts[1], body))
            if parts[1:] == ["schedules"]:
                return self.send_json(schedule_create(body))
            if parts[1:] == ["titles"]:
                return self.send_json(name_later(body))
            if parts[1:] == ["mail", "mark"]:
                return self.send_json(Mail.mark(body))
            if parts[1:] == ["mail", "delete"]:
                return self.send_json(Mail.delete(body))
            if parts[1:] == ["gateway", "enroll"] and ADDON:
                return self.send_json(Enroll.start(body.get("url") or OPTS.get("gateway_url"), body.get("name") or OPTS.get("machine_name")))
            if parts[1:] == ["gateway", "unenroll"] and ADDON:
                return self.send_json(Enroll.unenroll())
            if parts[1:] == ["gateway", "enroll", "cancel"] and ADDON:
                Enroll.cancel()
                return self.send_json({"ok": True})
            if parts[1:] == ["devices", "approve"]:
                return self.send_json(device_approve(body.get("code")))
            if len(parts) == 4 and parts[1] == "devices" and parts[3] == "revoke":
                return self.send_json(device_revoke(parts[2]))
            if len(parts) == 4 and parts[1] == "schedules":
                return self.send_json(schedule_action(parts[2], parts[3]))
            if parts[1:] == ["board"]:
                res = create_board(body)
                STATE.refresh(wait=True)
                return self.send_json(res)
            if len(parts) == 4 and parts[1] == "board" and parts[3] == "git":
                res = board_git(parts[2], body)
                STATE.refresh(wait=True)
                return self.send_json(res)
            if parts[1:] == ["focus"]:
                fid = str(body.get("id") or "").strip()
                if not fid.startswith("c_"):
                    return self.send_json({"error": "id required"}, 400)
                FOCUS.update(id=fid, at=time.time())
                return self.send_json({"ok": True})
            if parts[1:] == ["questions"]:
                return self.send_json(question_register(body))
            if len(parts) == 4 and parts[1] == "questions" and parts[3] == "answer":
                return self.send_json(question_answer(parts[2], body))
            if parts[1:] == ["ssh", "test"] and ADDON:
                return self.send_json(ssh_test(body.get("target") or ""))
            if parts[1:2] == ["claude"] and ADDON:
                action = "/".join(parts[2:])
                if action == "login":
                    return self.send_json(ClaudeLogin.start())
                if action == "login/code":
                    return self.send_json({**ClaudeLogin.code(body.get("code") or ""), "auth": claude_auth_status()})
                if action == "login/cancel":
                    ClaudeLogin.cancel()
                    return self.send_json({"ok": True})
                if action == "logout":
                    return self.send_json({"ok": True, "auth": claude_logout()})
                return self.send_json({"error": "not found"}, 404)
            if len(parts) == 4 and parts[1] in ("card", "chat") and parts[2].startswith("c_"):
                kind, cid, action = parts[1], parts[2], parts[3]
                if action == "send":
                    msg = (body.get("message") or "").strip()
                    files = upload_paths(body.get("attachments"))
                    if not msg and files:
                        msg = "Please look at the attached file." if len(files) == 1 else "Please look at the attached files."
                    if not msg:
                        return self.send_json({"error": "message required"}, 400)
                    args = [kind, "send", "--message", msg]
                    for k in ("model", "effort"):
                        if body.get(k):
                            args += [f"--{k}", str(body[k])]
                    for path in files:
                        args += ["--attach", path]
                    try:
                        out = run(args + [cid], timeout=120, parse=False)
                    finally:
                        upload_forget(files)
                    STATE.refresh(ids=[cid])
                    return self.send_json({"ok": True, "output": (out or "").strip()[:500]})
                if action == "move" and kind == "card":
                    lane = body.get("lane")
                    if lane not in LANES:
                        return self.send_json({"error": "lane must be one of " + ", ".join(LANES)}, 400)
                    out = run(["card", "move", "--lane", lane, cid], timeout=30, parse=False)
                    STATE.patch(cid, lane=lane)
                    return self.send_json({"ok": True, "output": (out or "").strip()[:500]})
                if action == "start" and kind == "card":
                    out = run(["card", "start", cid], timeout=60, parse=False)
                    STATE.patch(cid, lane="running")
                    return self.send_json({"ok": True, "output": (out or "").strip()[:500]})
                if action == "update":
                    title = (body.get("title") or "").strip()
                    prompt = body.get("prompt")
                    if title:
                        TITLE_PENDING.pop(cid, None)          # the user's title wins over a pending model title
                    if not title and prompt is None:
                        return self.send_json({"error": "title or prompt required"}, 400)
                    if kind == "chat":
                        if prompt is not None or not title:
                            return self.send_json({"error": "a chat can only be renamed"}, 400)
                        args = ["chat", "rename", "--title", title, cid]
                    else:
                        args = ["card", "update"]
                        if title:
                            args += ["--title", title]
                        if prompt is not None:
                            if not prompt.strip():
                                return self.send_json({"error": "task must not be empty"}, 400)
                            args += ["--prompt", prompt.strip()]
                        args.append(cid)
                    out = run(args, timeout=30, parse=False)
                    if title:
                        STATE.patch(cid, title=title)
                    else:
                        STATE.refresh(ids=[cid])
                    return self.send_json({"ok": True, "output": (out or "").strip()[:500]})
                if action in ("cancel", "archive"):
                    out = run([kind, action, cid], timeout=60, parse=False)
                    if action == "archive":
                        STATE.patch(cid, archived=True)
                    else:
                        STATE.refresh(ids=[cid])
                    return self.send_json({"ok": True, "output": (out or "").strip()[:500]})
                if action == "read" and kind == "card":
                    seq = str(body.get("seq", "")).strip()
                    if not seq.isdigit():
                        return self.send_json({"error": "seq required"}, 400)
                    out = run(["card", "read", "--response-seq", seq, cid], timeout=30, parse=False)
                    return self.send_json({"ok": True, "output": (out or "").strip()[:200]})
            return self.send_json({"error": "not found"}, 404)
        except CliError as e:
            return self.send_json({"error": str(e)}, 502)
        except (ValueError, json.JSONDecodeError) as e:
            return self.send_json({"error": str(e)}, 400)
        except RuntimeError as e:
            return self.send_json({"error": f"git: {e}"}, 502)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log("POST error", self.path, repr(e))
            try:
                self.send_json({"error": repr(e)}, 500)
            except Exception:
                pass

    # ---- actions
    def create(self, kind, b):
        for k in ("project", "prompt"):
            if not b.get(k):
                raise ValueError(f"{k} required")
        args = [kind, "create", "--project", b["project"], "--workspace", b.get("workspace") or "project", "--format", "json",
                "--prompt", b["prompt"]]
        if kind == "card":
            if not b.get("board"):
                raise ValueError("board required")
            args += ["--board", b["board"], "--lane", "running" if b.get("start", True) else "todo"]
        title = str(b.get("title") or "").strip()
        entity = None if title else title_entity()
        if title or entity:
            args += ["--title", title or first_line_title(b["prompt"])]
        else:
            args += ["--auto-title"]
        for k in ("provider", "model", "effort", "branch", "checkout"):
            if b.get(k):
                args += [f"--{k}", str(b[k])]
        if b.get("workspace") == "worktree" and b.get("base"):
            args += ["--base-branch", str(b["base"])]
        if b.get("publish"):
            args += ["--remote-publish", publish_mode(b["publish"])]
        for k in ("vault", "mail"):          # fixed at creation: the vault and mail gates of the agent turns
            if b.get(k):
                args.append(f"--{k}=true")
        files = upload_paths(b.get("attachments"))
        for path in files:
            args += ["--attach", path]
        try:
            out = run(args, timeout=120)
        finally:
            upload_forget(files)
        c = out.get("card") if isinstance(out, dict) and "card" in out else out
        cid = (c or {}).get("id")
        card = norm_card(c or {}, kind)
        if kind == "card" and cid:
            dreamfall_created(cid, b, self.user_name())
        if entity and cid:
            TITLE_PENDING[cid] = first_line_title(b["prompt"])
            threading.Thread(target=retitle, args=(kind, cid, b["prompt"], entity), daemon=True).start()
        if cid:
            if (kind == "chat" or b.get("start", True)) and card["runtime"] == "idle":
                card["runtime"] = "running"    # the turn is starting; the next poll has the daemon's runtime
            STATE.add(card)                    # the page opens the new card from /api/state
        else:
            STATE.refresh(wait=True)           # without an id: a full poll
        return {"ok": True, "id": cid, "card": card}

    def card_project(self, cid):
        rev, data = STATE.snapshot()
        card = next((c for c in data.get("cards", []) if c["id"] == cid), None)
        return (card or {}).get("project_id")

    def changes(self, cid):
        return {"raw": changeset(cid, self.card_project(cid))}

    def diff(self, cid, path):
        return {"raw": workspace_cmd(["workspace", "diff", "--path", path, "--limit", "400"], cid, self.card_project(cid))}

    def ha_summary(self):
        rev, d = STATE.snapshot()
        cards = [c for c in d.get("cards", []) if not c["archived"]]
        lanes = {l: [c for c in cards if c["lane"] == l] for l in LANES}
        running = [c for c in cards + d.get("chats", []) if c["runtime"] == "running"]
        q = {}
        for a in d.get("quota", []) or []:
            w = {x["id"]: x for x in a["windows"]}
            q[a["provider"]] = {"plan": a["plan"], "five_hour_used": (w.get("five_hour") or {}).get("used"),
                                "week_used": (w.get("seven_day") or {}).get("used"),
                                "five_hour_resets_at": (w.get("five_hour") or {}).get("resets_at"),
                                "week_resets_at": (w.get("seven_day") or {}).get("resets_at"), "available": a["available"]}
        m = d.get("machine") or {}
        return {
            "ok": d.get("ok", False), "error": d.get("error"), "daemon": d.get("daemon"), "rev": rev,
            "lanes": {l: len(v) for l, v in lanes.items()}, "running": len(running), "needs_you": len(lanes["review"]),
            "running_titles": [c["title"] for c in running][:5], "review_titles": [c["title"] for c in lanes["review"]][:5],
            "quota": q, "machine": {k: m.get(k) for k in ("cpu_percent", "mem_used", "mem_total", "gpu", "uptime_s")} if m else None,
            "last_finished": d.get("last_finished"), "last_review": d.get("last_review"),
            "projects": [p["name"] for p in d.get("projects", [])],
            "questions": len(d.get("questions") or []),
            "addon": ADDON,
        }

    # ---- streams
    def stream_events(self, fields=None):
        """The page's live state. `fields` (the new page: questions, dreamfall, ...) keeps only those keys, so a
        change costs a few kB instead of the full state; DERIVED_FIELDS are computed from it."""
        def only(data):
            return {k: DERIVED_FIELDS[k](data) if k in DERIVED_FIELDS else data.get(k) for k in fields}
        def pick(rev, data):
            return {"rev": rev, **(only(data) if fields else data)}
        self.start_sse()
        rev, data = STATE.snapshot()
        self.sse("state", pick(rev, data))
        sent = json.dumps(only(data), sort_keys=True, default=str) if fields else None
        last_beat = last_push = time.time()
        while True:
            new = STATE.wait_change(rev, 15)
            if new != rev:
                # a click comes as a burst of changes (the move, the re-read cards, the Dreamfall records): ~10 in
                # 1.5 s, each a full state of ~800 KB the page parses and draws again. One push per PUSH_GAP_S.
                time.sleep(max(0.0, last_push + PUSH_GAP_S - time.time()))
                rev, data = STATE.snapshot()
                if fields:
                    now = json.dumps(only(data), sort_keys=True, default=str)
                    if now == sent:                # nothing the subscriber asked for changed
                        if time.time() - last_beat > 14:
                            self.sse("ping", {"t": time.time()})
                            last_beat = time.time()
                        continue
                    sent = now
                self.sse("state", pick(rev, data))
                last_push = last_beat = time.time()
            elif time.time() - last_beat > 14:
                self.sse("ping", {"t": time.time()})
                last_beat = time.time()

    def stream_conversation(self, kind, cid, after, delta=False):
        args = [DIETER, kind, "watch", "--last", "200"]
        if after and str(after).isdigit():
            args += ["--after-seq", str(after)]
        args.append(cid)
        self.start_sse()
        p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        # `watch` is silent while the conversation is idle. Read it on a thread and send a ping
        # every 15 s, so proxies keep the stream open, the page can tell a live stream from a
        # half-open one (phone asleep, network change), and a gone client ends the watch.
        lines = queue.Queue()

        def pump():
            for raw in p.stdout:
                lines.put(raw)
            lines.put(None)
        threading.Thread(target=pump, daemon=True).start()
        current = held = None
        try:
            while True:
                try:
                    line = lines.get(timeout=15)
                except queue.Empty:
                    self.sse("ping", {"t": time.time()})
                    continue
                if line is None:
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    frame = json.loads(line)
                except json.JSONDecodeError:
                    continue
                # `card watch` sends one full snapshot, then delta frames while a turn runs:
                # {status, lastSeq, taskPlans, detail?, changedMessages?: [message with streaming parts]}.
                # Keep the snapshot and fold the deltas in, so the page gets a complete conversation
                # every time (forwarding deltas as snapshots blanked the view).
                snap = frame.get("snapshot")
                if isinstance(snap, dict) and isinstance(snap.get("conversation"), dict):
                    current = snap
                    page = norm_conversation(current)
                    self.sse("conversation", page)
                    held = conversation_held(page)
                    continue
                if current is None:
                    continue
                conv = current["conversation"]
                for key in ("status", "lastSeq", "updatedAt", "taskPlans", "queue", "pendingTools", "subagents"):
                    if key in frame:
                        conv[key] = frame[key]
                if isinstance(frame.get("detail"), dict):
                    current["detail"] = frame["detail"]
                changed = frame.get("changedMessages") or []
                if changed:
                    msgs = conv.setdefault("messages", [])
                    index = {m.get("id"): i for i, m in enumerate(msgs)}
                    for m in changed:
                        if m.get("id") in index:
                            msgs[index[m["id"]]] = m
                        else:
                            index[m.get("id")] = len(msgs)
                            msgs.append(m)
                if not (changed or "status" in frame):
                    continue
                if not delta:
                    self.sse("conversation", norm_conversation(current))
                    continue
                # delta=1: only what changed against what the page holds. The whole conversation (~200 KB of a
                # long one, three times a second) was most of what a phone received and parsed while it showed
                # a running agent, and the running turn alone is one message of a few hundred parts.
                d = conversation_delta(held, norm_conversation({**current, "conversation": {**conv, "messages": changed}}))
                if d:
                    self.sse("delta", d)
            err = (p.stderr.read() or "").strip()
            self.sse("end", {"error": err[:300] if err else None})
        finally:
            try:
                p.terminate()
                p.wait(timeout=3)
            except Exception:
                p.kill()


def main():
    read_web_host_secret()   # first: no child may inherit the descriptor
    if ADDON:
        log(f"dieter bridge (add-on) on {BIND}:{PORT}, store {DIETER_HOME}")
        os.makedirs(DIETER_HOME, mode=0o700, exist_ok=True)
        try:
            setup_ssh()
        except Exception as e:
            log("ssh: setup failed:", e)
        # the operator writes what the options configure, stages the harness runtime and starts the daemon
        threading.Thread(target=housekeeping_loop, daemon=True).start()

        def after_daemon():
            # the first start downloads the harness runtime (≈ 775 MB) before the daemon comes up
            if daemon_wait_healthy(timeout=6 * 3600):
                setup_projects()
                STATE.poll_slow()
                STATE.refresh()
        threading.Thread(target=after_daemon, daemon=True).start()
    else:
        log(f"dieter bridge on {BIND}:{PORT}, dieter={DIETER}, HA={HA_IP}, owner={owner_login()}")
    threading.Thread(target=STATE.loop, daemon=True).start()
    threading.Thread(target=Watch.loop, daemon=True).start()
    threading.Thread(target=Mail.loop, daemon=True).start()
    dreamfall.DIETER = DIETER
    DREAMFALL.start()
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.daemon_threads = True
    srv.serve_forever()


if __name__ == "__main__":
    main()
