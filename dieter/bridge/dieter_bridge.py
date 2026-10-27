#!/usr/bin/env python3
"""Dieter bridge: a small HTTP service that wraps the `dieter` CLI for the Home Assistant
page (index.html next to this file) and for HA sensors.

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
  GET  /                      the page
  GET  /api/state             everything the board needs (polled every POLL_S from the CLI)
  GET  /api/events            SSE: "state" whenever the board changed, heartbeats
  GET  /api/ha                compact summary for HA sensors
  GET  /api/{card|chat}/ID    conversation (last 200 messages) + detail
  GET  /api/{card|chat}/ID/stream?after=SEQ   SSE of `dieter ... watch` frames
  GET  /api/card/ID/changes   changed files of the card's checkout
  GET  /api/project/ID/changes
  GET  /api/card/ID/diff?path=FILE
  GET  /api/machines          enrolled machines (also part of /api/state)
  GET  /app.css, /app.js      the page's assets
  POST /api/card              create {project, board, title, prompt, provider, model, effort, workspace, lane}
  POST /api/chat              create {project, title, prompt, provider, model, effort}
  POST /api/{card|chat}/ID/send   {message, model?, effort?}
  POST /api/card/ID/move      {lane}
  POST /api/card/ID/start
  POST /api/{card|chat}/ID/cancel     stop the running turn
  POST /api/{card|chat}/ID/archive
  POST /api/card/ID/read      {seq}  mark the displayed reply as seen (response_seq)
  POST /api/project           {git_url, name?, branch?}  add-on only: clone into /data/projects/<name> and register
  POST /api/focus             {id}   remember which card the next page load should open (push deep link)
  GET  /api/focus?consume=1   {id|null} and forget it (valid 15 min)
  GET  /api/claude/login      add-on only: state of a running `claude auth login` {running, url, done, auth}
  POST /api/claude/login      start the sign-in; returns the link the user opens on claude.com
  POST /api/claude/login/code {code}  hand the code from claude.com to the CLI; writes /data/.claude/.credentials.json
  POST /api/claude/login/cancel, POST /api/claude/logout
Only stdlib; no pip dependencies.
"""
import base64
import ipaddress
import pty
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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
DAEMON_ADDR = os.environ.get("DIETER_DAEMON_ADDR", "127.0.0.1:4242")
BIND = os.environ.get("BRIDGE_BIND", "0.0.0.0")
PORT = int(os.environ.get("BRIDGE_PORT", "8099" if ADDON else "4250"))
HA_IP = os.environ.get("BRIDGE_HA_IP", "192.168.0.10")
POLL_S = float(os.environ.get("BRIDGE_POLL_S", "8"))
SLOW_POLL_S = float(os.environ.get("BRIDGE_SLOW_POLL_S", "60"))
KEY_FILE = os.environ.get("BRIDGE_KEY_FILE", "/data/bridge_api_key" if ADDON else os.path.expanduser("~/.config/dieter-bridge/api_key"))
SUPERVISOR = "http://supervisor"
SUPERVISOR_TOKEN = os.environ.get("SUPERVISOR_TOKEN")
PROJECTS_DIR = "/data/projects"
ENV_FILE = "/data/dieter.env"                    # provider credentials for the daemon and its harnesses
# Harness runtime (Claude Code / Codex / … workers, ~800 MB): the daemon's own download has a 15 min
# limit and failed on the Pi, so the bridge stages it (pinned sha256 from the fork release's signed
# SHA256SUMS) and hands it to the daemon with DIETER_HARNESS_RUNTIME_DIR. The archive carries only
# node_modules (+ Bun); the runtime sources of the same build are in the image (/opt/dieter-runtime,
# from the fork tag, see Dockerfile) and are laid over it.
HARNESS_REPO = "JustinNeumann/dieter"
HARNESS_VERSION = os.environ.get("DIETER_VERSION", "0.6.429-antigravity.1")
HARNESS_SHA = {"aarch64": "aef2667403aa872f3e40e0448fda52554a8c7ec74b9c628a40583dbe93fc7c68",
               "x86_64": "132c656f9a934479f2e3fce99be149021c7e940e39497302da9768559213a5e2"}
HARNESS_ASSET = {"aarch64": "dieter-harness-runtime-linux-arm64.tar.gz", "x86_64": "dieter-harness-runtime-linux-amd64.tar.gz"}
HARNESS_DIR = os.path.join(DIETER_HOME, "harness-staged", HARNESS_VERSION)
RUNTIME_SRC = os.environ.get("DIETER_RUNTIME_SRC", "/opt/dieter-runtime")
RUNTIME_DIR = os.path.join(HARNESS_DIR, "runtime")       # DIETER_HARNESS_RUNTIME_DIR: sources + node_modules
ANTIGRAVITY_TOKEN_FILE = "/data/.gemini/antigravity-cli/antigravity-oauth-token"   # HOME=/data
PROVIDER_ENV = {"claude_code_oauth_token": "CLAUDE_CODE_OAUTH_TOKEN", "openai_api_key": "OPENAI_API_KEY"}
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


def norm_card(c, kind="card"):
    """snake_case (list) and camelCase (show/create) variants -> one shape."""
    g = lambda *ks: next((c[k] for k in ks if k in c and c[k] is not None), None)
    usage = g("token_usage", "tokenUsage") or {}
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
        "response_seq": g("response_seq", "responseSeq"),
        "seen_response_seq": g("seen_response_seq", "seenResponseSeq"),
        "label_ids": g("label_ids", "labelIds") or [],
        "tokens": {k: int(usage.get(k) or 0) for k in ("inputTokens", "outputTokens", "totalTokens")} if usage else None,
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
                    if isinstance(v, str):
                        slim[k] = v[:2000]
                    elif isinstance(v, (int, float, bool)) or v is None:
                        slim[k] = v
                    else:
                        slim[k] = json.dumps(v, ensure_ascii=False, default=str)[:2000]
                slim["type"] = t or "other"
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
    }


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


# --------------------------------------------------------------------------- daemon (add-on only)
class Daemon:
    """Runs `dieter daemon start` in the foreground and restarts it when it exits."""
    instance = None

    def __init__(self):
        self.proc = None
        Daemon.instance = self

    def restart(self):
        """Stop the daemon; loop() starts it again with the current env file (after a provider change)."""
        if self.proc and self.proc.poll() is None:
            log("daemon: restarting (credentials changed)")
            self.proc.terminate()

    def loop(self):
        backoff = 2
        while True:
            log(f"daemon: starting (store {DIETER_HOME}, api {DAEMON_ADDR})")
            started = time.time()
            args = [DIETER, "--store", DIETER_HOME, "daemon", "start", "--addr", DAEMON_ADDR]
            env = dict(os.environ)
            if os.path.exists(os.path.join(RUNTIME_DIR, "runner.mjs")):
                env["DIETER_HARNESS_RUNTIME_DIR"] = RUNTIME_DIR
            if os.path.exists(ENV_FILE):
                args += ["--env-file", ENV_FILE]
                # also in the daemon's own environment, so harness processes inherit the tokens either way
                for line in open(ENV_FILE):
                    if "=" in line and not line.startswith("#"):
                        k, v = line.rstrip("\n").split("=", 1)
                        env[k] = v
            try:
                self.proc = subprocess.Popen(args, stdout=sys.stderr, stderr=sys.stderr, env=env)
                rc = self.proc.wait()
            except Exception as e:
                rc = repr(e)
            ran = time.time() - started
            backoff = 2 if ran > 60 else min(backoff * 2, 60)
            log(f"daemon: exited ({rc}) after {ran:.0f}s, restart in {backoff}s")
            time.sleep(backoff)

    @staticmethod
    def wait_healthy(timeout=180):
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


def stage_harness_runtime():
    """Download (once) and unpack the harness runtime archive for this platform into HARNESS_DIR."""
    import hashlib, platform
    arch = platform.machine()
    if arch not in HARNESS_SHA or os.path.exists(os.path.join(HARNESS_DIR, ".staged")):
        return
    url = f"https://github.com/{HARNESS_REPO}/releases/download/v{HARNESS_VERSION}/{HARNESS_ASSET[arch]}"
    tmp = HARNESS_DIR + ".download.tar.gz"
    log(f"harness: downloading {HARNESS_ASSET[arch]} ({HARNESS_VERSION})")
    os.makedirs(os.path.dirname(HARNESS_DIR), exist_ok=True)
    subprocess.run(["curl", "-fsSL", "--retry", "3", "-o", tmp, url], check=True, timeout=3600)
    h = hashlib.sha256()
    with open(tmp, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest() != HARNESS_SHA[arch]:
        os.remove(tmp)
        raise RuntimeError(f"harness archive checksum mismatch ({h.hexdigest()[:12]}…)")
    subprocess.run(["rm", "-rf", HARNESS_DIR], check=True)
    os.makedirs(HARNESS_DIR)
    subprocess.run(["tar", "-xzf", tmp, "-C", HARNESS_DIR], check=True, timeout=1800)
    os.remove(tmp)
    open(os.path.join(HARNESS_DIR, ".staged"), "w").write(HARNESS_SHA[arch])
    log(f"harness: staged in {HARNESS_DIR}")


def build_runtime_dir():
    """DIETER_HARNESS_RUNTIME_DIR = the build's runtime sources on top of the archive's node_modules.
    Re-done whenever the shipped sources change (their digest is kept next to the directory)."""
    import hashlib
    stale = os.path.join(DIETER_HOME, "harnesses.yaml")   # catalog override of the pre-fork builds
    if os.path.exists(stale):
        os.remove(stale)
        log("harness: removed the old catalog override (the daemon's own catalog has Antigravity)")
    if not os.path.isdir(RUNTIME_SRC) or not os.path.exists(os.path.join(HARNESS_DIR, ".staged")):
        return
    names = sorted(n for n in os.listdir(RUNTIME_SRC) if n.endswith((".mjs", ".json")))
    h = hashlib.sha256()
    for n in names:
        h.update(n.encode() + b"\0" + open(os.path.join(RUNTIME_SRC, n), "rb").read() + b"\0")
    digest = h.hexdigest()
    marker = os.path.join(HARNESS_DIR, ".runtime-sources")
    if os.path.exists(os.path.join(RUNTIME_DIR, "runner.mjs")) and os.path.exists(marker) and open(marker).read() == digest:
        return
    os.makedirs(RUNTIME_DIR, exist_ok=True)
    archive_modules = os.path.join(HARNESS_DIR, "harness", "node_modules")
    if not os.path.isdir(os.path.join(RUNTIME_DIR, "node_modules")) and os.path.isdir(archive_modules):
        os.rename(archive_modules, os.path.join(RUNTIME_DIR, "node_modules"))
    for n in names:
        with open(os.path.join(RUNTIME_SRC, n), "rb") as src, open(os.path.join(RUNTIME_DIR, n), "wb") as dst:
            dst.write(src.read())
    open(marker, "w").write(digest)
    log(f"harness: runtime sources staged in {RUNTIME_DIR} ({len(names)} files)")


def setup_antigravity():
    """Antigravity CLI credentials from the option (the JSON of ~/.gemini/antigravity-cli/antigravity-oauth-token)."""
    tok = (OPTS.get("antigravity_token") or "").strip()
    if not tok:
        return
    os.makedirs(os.path.dirname(ANTIGRAVITY_TOKEN_FILE), mode=0o700, exist_ok=True)
    with open(os.open(ANTIGRAVITY_TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        f.write(tok if tok.endswith("\n") else tok + "\n")
    log("env: Antigravity credentials installed")


QUOTA_CACHE = {"at": 0, "items": []}
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


def on_claude_login_changed():
    """After a sign-in or sign-out: rewrite the env file (the login takes precedence over the option
    token), restart the daemon so the harness picks it up, refresh the quota."""
    try:
        setup_env_file()
    except Exception as e:
        log("env:", e)
    QUOTA_CACHE["at"] = 0
    if Daemon.instance:
        Daemon.instance.restart()
    threading.Thread(target=lambda: (Daemon.wait_healthy(), STATE.poll_slow()), daemon=True).start()


def claude_logout():
    ClaudeLogin.cancel()
    try:
        os.remove(CLAUDE_CRED_FILE)
    except OSError:
        pass
    log("claude: signed out")
    on_claude_login_changed()
    return claude_auth_status()


def local_quota():
    """Provider quotas without a gateway (the daemon's `quota list` is gateway-scoped): Claude via Dieter's
    probe quota-claude.mjs (reads CLAUDE_CODE_OAUTH_TOKEN), Antigravity via `agy --print=/quota`."""
    now = time.time()
    if now - QUOTA_CACHE["at"] < QUOTA_TTL_S:
        return QUOTA_CACHE["items"]
    items = []
    env = {**os.environ, **env_file_vars(), "HOME": "/data"}
    probe = os.path.join(RUNTIME_DIR, "quota-claude.mjs")
    if claude_credentials():
        env.pop("CLAUDE_CODE_OAUTH_TOKEN", None)   # the probe reads /data/.claude/.credentials.json instead
        claude_refresh_if_due()
    if (env.get("CLAUDE_CODE_OAUTH_TOKEN") or claude_credentials()) and os.path.exists(probe):
        try:
            p = subprocess.run(["node", probe], capture_output=True, text=True, timeout=40, env=env, cwd=os.path.dirname(probe))
            a = json.loads(p.stdout.strip() or "{}")
            if not a.get("windows"):
                # setup-token logins are "unsupported" for Dieter's probe: fetch the usage API directly
                p = subprocess.run(["node", os.path.join(HERE, "quota_claude_token.mjs"), probe], capture_output=True, text=True, timeout=40, env=env, cwd=HERE)
                err = next((l for l in p.stderr.splitlines() if "Error" in l), (p.stderr.strip().splitlines() or ["?"])[-1])
                a = json.loads(p.stdout.strip() or "{}") if p.returncode == 0 else {"availability": err.strip()[:120]}
            if a.get("windows"):
                items.append({"provider": "anthropic_claude", "plan": a.get("plan"), "email": a.get("displayEmail"),
                              "available": a.get("availability") == "available",
                              "windows": [{"id": w.get("id"), "label": w.get("label"), "used": w.get("usedPercent"),
                                           "remaining": w.get("remainingPercent"), "resets_at": w.get("resetsAt")} for w in a["windows"]],
                              "refreshed_at": datetime_now()})
            elif a.get("availability"):
                log("quota: claude:", a.get("availability"))
        except Exception as e:
            log("quota: claude probe:", e)
    if os.path.exists(ANTIGRAVITY_TOKEN_FILE):
        try:
            p = subprocess.run(["agy", "--print=/quota"], capture_output=True, text=True, timeout=60, env=env, cwd="/data")
            windows = []
            for line in p.stdout.splitlines():
                cols = [c.strip() for c in line.split("\t")]
                if len(cols) >= 3 and cols[2].endswith("%"):
                    remaining = int(cols[2].rstrip("%"))
                    wid = re.sub(r"[^a-z0-9]+", "_", f"{cols[0]} {cols[1]}".lower()).strip("_")
                    label = f"{cols[0]} · {cols[1].replace(' Limit Remaining', '').replace(' Limit', '')}"
                    windows.append({"id": wid, "label": label, "used": 100 - remaining, "remaining": remaining,
                                    "resets_at": cols[3] if len(cols) > 3 else None})
            if windows:
                items.append({"provider": "antigravity", "plan": None, "email": None, "available": all(w["remaining"] > 0 for w in windows),
                              "windows": windows, "refreshed_at": datetime_now()})
            elif p.returncode != 0:
                log("quota: agy:", (p.stderr or p.stdout).strip()[-160:])
        except Exception as e:
            log("quota: agy:", e)
    QUOTA_CACHE.update(at=now, items=items)
    return items


def datetime_now():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def git(args, cwd=None, timeout=600):
    p = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError((p.stderr or p.stdout).strip()[:500])
    return p.stdout


def setup_env_file():
    """Provider tokens from the options -> /data/dieter.env, handed to the daemon with --env-file
    (the Claude Code harness reads CLAUDE_CODE_OAUTH_TOKEN, Codex OPENAI_API_KEY)."""
    def token(opt, env):
        raw = (OPTS.get(opt) or "").strip()
        # tolerate a pasted line like "claude: sk-ant-oat01-…" or surrounding quotes
        m = re.search(r"sk-ant-oat01-[A-Za-z0-9_-]{40,}", raw) if env == "CLAUDE_CODE_OAUTH_TOKEN" else re.search(r"sk-[A-Za-z0-9_-]{20,}", raw)
        return m.group(0) if m else raw.strip("\"' ")
    lines = [f"{env}={token(opt, env)}" for opt, env in PROVIDER_ENV.items() if (OPTS.get(opt) or "").strip()]
    if claude_credentials():
        # signed in on the page (`claude auth login`): the CLI and the quota probe use that login, not the option token
        lines = [l for l in lines if not l.startswith("CLAUDE_CODE_OAUTH_TOKEN=")]
        log("env: Claude Code uses the page sign-in (" + CLAUDE_CRED_FILE + ")")
    if lines:
        with open(os.open(ENV_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
            f.write("\n".join(lines) + "\n")
        log("env: providers configured:", ", ".join(l.split("=")[0] for l in lines))
    elif os.path.exists(ENV_FILE):
        os.remove(ENV_FILE)


def setup_git():
    """Commit identity and GitHub token for the agents' pushes (HOME=/data, so it persists)."""
    for key, opt in (("user.name", "git_user"), ("user.email", "git_email")):
        if OPTS.get(opt):
            git(["config", "--global", key, OPTS[opt]])
    cred = "/data/.git-credentials"
    tok = (OPTS.get("github_token") or "").strip()
    if tok:
        with open(os.open(cred, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
            f.write(f"https://x-access-token:{tok}@github.com\n")
        git(["config", "--global", "credential.helper", f"store --file {cred}"])
    elif os.path.exists(cred):
        os.remove(cred)


GIT_URL_RE = re.compile(r"^(https://[\w.\-]+(:\d+)?/[\w.\-/~]+|git@[\w.\-]+:[\w.\-/~]+|ssh://[\w.\-@]+(:\d+)?/[\w.\-/~]+)$")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


def repo_name(url):
    return re.sub(r"\.git$", "", url.rstrip("/").rsplit("/", 1)[-1].rsplit(":", 1)[-1])


def add_project(url, name="", branch=""):
    """Clone a Git URL into /data/projects/<name> (if missing) and register it with the daemon.
    Any number of repositories works; private GitHub repos use the add-on's github_token."""
    url, name, branch = url.strip(), (name or "").strip() or repo_name(url.strip()), (branch or "").strip()
    if not GIT_URL_RE.match(url):
        raise ValueError("git_url must be an https://, ssh:// or git@host:path URL")
    if not NAME_RE.match(name):
        raise ValueError("name may contain letters, digits, . _ - (max 64)")
    if branch and not re.match(r"^[\w./-]{1,100}$", branch):
        raise ValueError("invalid branch")
    os.makedirs(PROJECTS_DIR, exist_ok=True)
    path = os.path.join(PROJECTS_DIR, name)
    if not os.path.isdir(os.path.join(path, ".git")):
        log(f"projects: cloning {url} -> {path}")
        git(["clone", "--quiet", *(["--branch", branch] if branch else []), url, path])
    known = {p.get("path"): p for p in unwrap(run(["project", "list", "--format", "json"], timeout=20), "projects")}
    if path in known:
        return {"ok": True, "id": known[path].get("id"), "name": known[path].get("name") or name, "existed": True}
    args = ["project", "create", "--name", name, "--format", "json"]
    if branch:
        args += ["--base-branch", branch]
    out = run(args + [path], timeout=60)
    pid = (out.get("project") or out).get("id") if isinstance(out, dict) else out
    log(f"projects: registered {name} as {pid}")
    return {"ok": True, "id": pid, "name": name, "existed": False}


def setup_projects():
    """Projects from the add-on options (a convenience; more can be added from the page any time)."""
    for pr in OPTS.get("projects") or []:
        try:
            add_project(pr.get("git_url") or "", pr.get("name") or "", pr.get("branch") or "")
        except Exception as e:
            log(f"projects: {pr.get('name') or pr.get('git_url')}: {e}")


# --------------------------------------------------------------------------- state poller
class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.cond = threading.Condition(self.lock)
        self.rev = 0
        self.data = {"ok": False, "error": "noch nicht geladen"}
        self.slow = {"machine": None, "quota": [], "harnesses": [], "machines": [], "claude_auth": None, "at": 0}
        self.runtime_seen = {}       # card id -> runtime, to detect finished turns
        self.last_finished = None    # {"id","title","at","kind"}
        self.last_review = None

    def snapshot(self):
        with self.lock:
            return self.rev, dict(self.data)

    def note(self, what, err):
        """Log a recurring poll error once per distinct message (unenrolled daemons have no quota/machines)."""
        noted = self.__dict__.setdefault("_noted", {})
        if noted.get(what) != str(err):
            noted[what] = str(err)
            log(f"{what}: {err}")

    def poll_fast(self):
        try:
            status = run(["status", "--format", "json"], timeout=15) or {}
            projects = [p for p in unwrap(run(["project", "list", "--format", "json"], timeout=15), "projects")]
            boards = unwrap(run(["board", "list", "--format", "json"], timeout=15), "boards")
            cards = [norm_card(c) for c in unwrap(run(["card", "list", "--format", "json", "--limit", "200"], timeout=20), "cards")]
            chats = [norm_card(c, "chat") for c in unwrap(run(["chat", "list", "--format", "json"], timeout=15), "chats")]
        except CliError as e:
            with self.lock:
                if self.data.get("ok") or self.data.get("error") != str(e):
                    self.data = {**self.data, "ok": False, "error": str(e), "generated_at": time.time()}
                    self.rev += 1
                    self.cond.notify_all()
            return
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
            prev_lanes = {c["id"]: c["lane"] for c in self.data.get("cards", [])} if self.data.get("ok") else {}
        for c in cards:
            if c["lane"] == "review" and prev_lanes and prev_lanes.get(c["id"]) not in (None, "review"):
                review = {"id": c["id"], "title": c["title"], "at": now, "project": (pmap.get(c["project_id"]) or {}).get("name")}
        data = {
            "ok": True, "error": None, "generated_at": now,
            "daemon": {"health": (status.get("health") or {}).get("status"), "version": (status.get("health") or {}).get("release_version"),
                       "route": status.get("route"), "counts": {k: status.get(k) for k in ("projects", "boards", "cards", "chats")}},
            "projects": [{"id": p["id"], "name": p.get("name"), "path": p.get("path"), "boards": p.get("boardCount"),
                          "checkouts": [{"id": c.get("id"), "name": c.get("name"), "path": c.get("path"), "daemon_id": c.get("daemonId") or c.get("daemon_id")} for c in p.get("checkouts", []) or []]}
                         for p in projects],
            "boards": [{"id": b.get("id"), "name": b.get("name"), "project_id": b.get("project_id") or b.get("projectId"),
                        "workflow": b.get("workflow"), "lanes": [l.get("id") for l in b.get("lanes", []) or []]} for b in boards],
            "cards": cards, "chats": chats,
            "machine": self.slow["machine"], "quota": self.slow["quota"], "harnesses": self.slow["harnesses"],
            "machines": self.slow["machines"], "claude_auth": self.slow.get("claude_auth"),
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
            changed = json.dumps({k: v for k, v in data.items() if k != "generated_at"}, sort_keys=True, default=str) != \
                      json.dumps({k: v for k, v in self.data.items() if k != "generated_at"}, sort_keys=True, default=str)
            self.data = data
            if changed:
                self.rev += 1
                self.cond.notify_all()

    def poll_slow(self):
        try:
            machine = norm_machine(run(["machine", "info"], timeout=20))
        except CliError as e:
            machine = {"error": str(e)}
        try:
            quota = norm_quota(run(["quota", "list", "--format", "json"], timeout=20))
        except CliError as e:
            quota = []
            self.note("quota", e)
        if not quota and ADDON:
            try:
                quota = local_quota()
            except Exception as e:
                self.note("quota-local", e)
        try:
            ml = run(["machine", "list", "--format", "json"], timeout=20) or {}
            machines = [{"id": d.get("id"), "name": d.get("name"), "online": bool(d.get("online")), "last_seen_at": d.get("lastSeenAt"),
                         "version": d.get("releaseVersion"),
                         "route": ", ".join(sorted({c.get("network", "") for c in d.get("directCandidates", []) or []} - {""})) or None}
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
        if not machines and isinstance(machine, dict) and not machine.get("error"):
            # unenrolled daemon (no gateway): `machine list` is empty, so list this machine from `machine info`
            machines = [{"id": "local", "name": "Home Assistant" if ADDON else (machine.get("hostname") or "local"), "online": True,
                         "last_seen_at": machine.get("collected_at"), "version": machine.get("daemon_version"), "route": "local"}]
        claude_auth = claude_auth_status() if ADDON else None
        with self.lock:
            self.slow = {"machine": machine, "quota": quota, "harnesses": harnesses, "machines": machines, "claude_auth": claude_auth, "at": time.time()}
            if self.data.get("ok"):
                self.data = {**self.data, "machine": machine, "quota": quota, "harnesses": harnesses, "machines": machines, "claude_auth": claude_auth}
                self.rev += 1
                self.cond.notify_all()

    def loop(self):
        last_slow = 0
        while True:
            # the first `harness list` after a daemon start often times out: retry quickly while the catalog is empty
            if time.time() - last_slow > (SLOW_POLL_S if self.slow["harnesses"] else 10):
                self.poll_slow()
                last_slow = time.time()
            self.poll_fast()
            time.sleep(POLL_S)

    def wait_change(self, rev, timeout):
        with self.lock:
            self.cond.wait_for(lambda: self.rev != rev, timeout=timeout)
            return self.rev


STATE = State()


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

    # ---- routing
    def do_GET(self):
        try:
            u = urllib.parse.urlsplit(self.path)
            q = urllib.parse.parse_qs(u.query)
            parts = [p for p in u.path.split("/") if p]
            if not self.may_read():
                return self.send_json({"error": "forbidden"}, 403)
            if not parts or parts == ["index.html"]:
                return self.send_text(open(INDEX, "rb").read(), ctype="text/html; charset=utf-8")
            if parts in (["app.css"], ["app.js"]):
                ctype = "text/css; charset=utf-8" if parts[0].endswith(".css") else "text/javascript; charset=utf-8"
                return self.send_text(open(os.path.join(HERE, parts[0]), "rb").read(), ctype=ctype)
            if parts[0] != "api":
                return self.send_json({"error": "not found"}, 404)
            if parts[1:] == ["machines"]:
                rev, data = STATE.snapshot()
                return self.send_json({"machines": data.get("machines", [])})
            if len(parts) == 4 and parts[1] == "project" and parts[3] == "changes":
                return self.send_json({"raw": run(["workspace", "changes", "--project", parts[2]], timeout=30)})
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
                return self.stream_events()
            if parts[1:] == ["claude", "login"]:
                return self.send_json(ClaudeLogin.status())
            if len(parts) >= 3 and parts[1] in ("card", "chat"):
                kind, cid = parts[1], parts[2]
                if not cid.startswith("c_"):
                    return self.send_json({"error": "bad id"}, 400)
                if len(parts) == 3:
                    snap = run([kind, "poll", "--last", "200", cid], timeout=30) or {}
                    return self.send_json(norm_conversation(snap.get("snapshot", snap)))
                if parts[3] == "stream":
                    return self.stream_conversation(kind, cid, q.get("after", [None])[0])
                if parts[3] == "changes" and kind == "card":
                    return self.send_json(self.changes(cid))
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
            body = self.read_body()
            if parts[:1] != ["api"]:
                return self.send_json({"error": "not found"}, 404)
            if parts[1:] == ["card"] or parts[1:] == ["chat"]:
                return self.send_json(self.create(parts[1], body))
            if parts[1:] == ["project"]:
                if not ADDON:
                    return self.send_json({"error": "adding repositories works in the add-on only"}, 400)
                res = add_project(body.get("git_url") or "", body.get("name") or "", body.get("branch") or "")
                STATE.poll_fast()
                return self.send_json(res)
            if parts[1:] == ["focus"]:
                fid = str(body.get("id") or "").strip()
                if not fid.startswith("c_"):
                    return self.send_json({"error": "id required"}, 400)
                FOCUS.update(id=fid, at=time.time())
                return self.send_json({"ok": True})
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
                    if not msg:
                        return self.send_json({"error": "message required"}, 400)
                    args = [kind, "send", "--message", msg]
                    for k in ("model", "effort"):
                        if body.get(k):
                            args += [f"--{k}", str(body[k])]
                    out = run(args + [cid], timeout=60, parse=False)
                    STATE.poll_fast()
                    return self.send_json({"ok": True, "output": (out or "").strip()[:500]})
                if action == "move" and kind == "card":
                    lane = body.get("lane")
                    if lane not in LANES:
                        return self.send_json({"error": "lane must be one of " + ", ".join(LANES)}, 400)
                    out = run(["card", "move", "--lane", lane, cid], timeout=30, parse=False)
                    STATE.poll_fast()
                    return self.send_json({"ok": True, "output": (out or "").strip()[:500]})
                if action == "start" and kind == "card":
                    out = run(["card", "start", cid], timeout=60, parse=False)
                    STATE.poll_fast()
                    return self.send_json({"ok": True, "output": (out or "").strip()[:500]})
                if action in ("cancel", "archive"):
                    out = run([kind, action, cid], timeout=60, parse=False)
                    STATE.poll_fast()
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
        if b.get("title"):
            args += ["--title", b["title"]]
        else:
            args += ["--auto-title"]
        for k in ("provider", "model", "effort", "branch", "checkout"):
            if b.get(k):
                args += [f"--{k}", str(b[k])]
        out = run(args, timeout=60)
        STATE.poll_fast()
        c = out.get("card") if isinstance(out, dict) and "card" in out else out
        return {"ok": True, "id": (c or {}).get("id"), "card": norm_card(c or {}, kind)}

    def changes(self, cid):
        rev, data = STATE.snapshot()
        card = next((c for c in data.get("cards", []) if c["id"] == cid), None)
        try:
            out = run(["workspace", "changes", cid], timeout=30)
        except CliError as e:
            if card and card.get("project_id") and "project-scoped" in str(e):
                out = run(["workspace", "changes", "--project", card["project_id"]], timeout=30)
            else:
                raise
        return {"raw": out}

    def diff(self, cid, path):
        rev, data = STATE.snapshot()
        card = next((c for c in data.get("cards", []) if c["id"] == cid), None)
        args = ["workspace", "diff", "--path", path, "--limit", "400"]
        try:
            out = run(args + [cid], timeout=30)
        except CliError as e:
            if card and card.get("project_id") and "project-scoped" in str(e):
                out = run(args + ["--project", card["project_id"]], timeout=30)
            else:
                raise
        return {"raw": out}

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
            "addon": ADDON,
        }

    # ---- streams
    def stream_events(self):
        self.start_sse()
        rev, data = STATE.snapshot()
        self.sse("state", {"rev": rev, **data})
        last_beat = time.time()
        while True:
            new = STATE.wait_change(rev, 15)
            if new != rev:
                rev, data = STATE.snapshot()
                self.sse("state", {"rev": rev, **data})
            elif time.time() - last_beat > 14:
                self.sse("ping", {"t": time.time()})
                last_beat = time.time()

    def stream_conversation(self, kind, cid, after):
        args = [DIETER, kind, "watch", "--last", "200"]
        if after and str(after).isdigit():
            args += ["--after-seq", str(after)]
        args.append(cid)
        self.start_sse()
        p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        current = None
        try:
            for line in p.stdout:
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
                    self.sse("conversation", norm_conversation(current))
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
                if changed or "status" in frame:
                    self.sse("conversation", norm_conversation(current))
            err = (p.stderr.read() or "").strip()
            self.sse("end", {"error": err[:300] if err else None})
        finally:
            try:
                p.terminate()
                p.wait(timeout=3)
            except Exception:
                p.kill()


def main():
    if ADDON:
        log(f"dieter bridge (add-on) on {BIND}:{PORT}, store {DIETER_HOME}")
        os.makedirs(DIETER_HOME, mode=0o700, exist_ok=True)
        try:
            setup_env_file()
            setup_git()
        except Exception as e:
            log("setup:", e)
        try:
            stage_harness_runtime()
            build_runtime_dir()
            setup_antigravity()
        except Exception as e:
            log("harness: staging failed, the daemon will download it itself:", e)
        threading.Thread(target=Daemon().loop, daemon=True).start()

        def after_daemon():
            if Daemon.wait_healthy():
                setup_projects()
                STATE.poll_slow()
                STATE.poll_fast()
        threading.Thread(target=after_daemon, daemon=True).start()
    else:
        log(f"dieter bridge on {BIND}:{PORT}, dieter={DIETER}, HA={HA_IP}, owner={owner_login()}")
    threading.Thread(target=STATE.loop, daemon=True).start()
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.daemon_threads = True
    srv.serve_forever()


if __name__ == "__main__":
    main()
