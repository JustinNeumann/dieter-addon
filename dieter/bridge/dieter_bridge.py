#!/usr/bin/env python3
"""Dieter bridge: the add-on's front door on port 8099 (Ingress and Home Assistant), until the operator takes the
port over too (docs/PLAN.md Step 2, area 10).

Everything under /api/ is the operator's (host/…/operator, Kotlin, run.sh starts it on loopback as this same user):
the bridge passes each request on with its query, body and the Home Assistant user, and answers as the operator
answers; a stream (text/event-stream: /api/events) is passed on as it comes. What the bridge still serves itself:

  GET  /                      the add-on's root (index.html next to this file): forwards to next/, with deep links
  GET  /next/...              the Compose page (Kotlin/Wasm bundle in /opt/web, DIETER_WEB_DIR): hashed files
                              immutable, index.html and resources no-cache + ETag; *.wasm.gz raw for the loader,
                              .br/.gz by Accept-Encoding only outside Ingress (Ingress strips that header)
  GET  /next/core             WebSocket to the web host (the page's core; relayed with the per-boot secret)
  GET  /next/signin?code=…    the gateway's redirect after GitHub sign-in, relayed to the web host
  GET  /api/ws/echo           WebSocket echo (checks that WebSockets pass Ingress; Phase 1 of the Compose page)

The operator's paths, the ones Home Assistant and the add-on core use among them (homeassistant/packages/dieter.yaml,
dieter/tests/e2e/bridge-api.spec.mjs): GET /api/state, /api/ha, /api/events, /api/focus, /api/{card|chat}/ID,
/api/schedules, /api/dreamfall/card/ID; POST /api/card, /api/chat, /api/{card|chat}/ID/{send|update|cancel|archive},
/api/card/ID/{move|start|read}, /api/focus, /api/upload, /api/project, /api/settings, /api/boards/order,
/api/titles, /api/prompts, /api/questions…, /api/dreamfall…, /api/claude/…, /api/gateway…, /api/ssh….

Two homes, detected by /data/options.json: the Home Assistant add-on (reads and writes from the Supervisor network
172.30.32.0/23 and loopback: Ingress forwards logged-in HA users, HA core calls /api/ha and the rest_commands), and a
PC (reads from loopback, LAN and Tailscale; writes from Home Assistant (HA_IP), Tailscale devices whose `tailscale
whois` login is the PC owner's, or with X-API-Key). Only stdlib; no pip dependencies.
"""
import base64
import hashlib
import ipaddress
import json
import mimetypes
import os
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

HERE = os.path.dirname(os.path.abspath(__file__))
OPTIONS_FILE = "/data/options.json"              # written by the Supervisor from the add-on's options
ADDON = os.path.exists(OPTIONS_FILE)
BIND = os.environ.get("BRIDGE_BIND", "0.0.0.0")
PORT = int(os.environ.get("BRIDGE_PORT", "8099" if ADDON else "4250"))
HA_IP = os.environ.get("BRIDGE_HA_IP", "192.168.0.10")
KEY_FILE = os.environ.get("BRIDGE_KEY_FILE", "/data/bridge_api_key" if ADDON else os.path.expanduser("~/.config/dieter-bridge/api_key"))
HASSIO_NETS = [ipaddress.ip_network("172.30.32.0/23")]   # Supervisor (ingress proxy) and HA core
# Where the page's core may be reached from: the Supervisor's network only, never loopback, where the agents are
# (BRIDGE_INGRESS_NETS adds the e2e suite's stand-in proxy, which connects from 127.0.0.2)
INGRESS_NETS = HASSIO_NETS + [ipaddress.ip_network(n.strip()) for n in os.environ.get("BRIDGE_INGRESS_NETS", "").split(",") if n.strip()]
LOOPBACK = [ipaddress.ip_network(n) for n in ("127.0.0.0/8", "::1/128")]
READ_NETS = LOOPBACK + HASSIO_NETS + [ipaddress.ip_network(n) for n in ("192.168.0.0/24", "100.64.0.0/10", "fd7a:115c:a1e0::/48")]
TS_NETS = [ipaddress.ip_network(n) for n in ("100.64.0.0/10", "fd7a:115c:a1e0::/48")]
OPERATOR_URL = os.environ.get("DIETER_OPERATOR_URL", "http://127.0.0.1:8096/")
OPERATOR_TIMEOUT_S = 660        # a clone may take its 10 minutes; a stream's pings come every 15 s
BODY_MAX = 20 * 1024 * 1024     # an upload's 20 MB


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

    def forward(self, method, u, raw=None):
        """A path the operator answers: passed on with its query, body and the Home Assistant user, answered as it
        answers; an event stream as it comes, until either side leaves."""
        req = urllib.request.Request(OPERATOR_URL.rstrip("/") + u.path + (f"?{u.query}" if u.query else ""), data=raw, method=method)
        if raw is not None:
            req.add_header("Content-Type", self.headers.get("Content-Type") or "application/json")
        for k, v in self.user_headers() + ([("X-Filename", self.headers["X-Filename"])] if self.headers.get("X-Filename") else []):
            req.add_header(k, v)
        try:
            r = urllib.request.urlopen(req, timeout=OPERATOR_TIMEOUT_S)
        except urllib.error.HTTPError as e:
            r = e
        except (urllib.error.URLError, OSError) as e:
            log("operator:", method, u.path, e)
            return self.send_json({"error": "the add-on's operator is not running yet; try again in a moment"}, 503)
        with r:
            status, ctype = getattr(r, "status", None) or r.code, r.headers.get("Content-Type") or "application/json; charset=utf-8"
            if ctype.startswith("text/event-stream"):
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Accel-Buffering", "no")
                self.send_header("Connection", "close")
                self.end_headers()
                while chunk := r.read1(65536):
                    self.wfile.write(chunk)
                    self.wfile.flush()
                return
            body = r.read()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def user_headers(self):
        """Ingress's headers naming the Home Assistant user, passed on to the operator (its Dreamfall trail)."""
        return [(h, self.headers.get(h)) for h in ("X-Remote-User-Id", "X-Remote-User-Name", "X-Remote-User-Display-Name") if self.headers.get(h)]

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
            parts = [p for p in u.path.split("/") if p]
            if not self.may_read():
                return self.send_json({"error": "forbidden"}, 403)
            if parts[:2] in (["next", "core"], ["next", "signin"]) and len(parts) == 2:
                # the core drives the account's gateway session: Home Assistant's Ingress only, so the agents
                # (this user, on loopback) cannot have the bridge relay them with the secret
                if not (ADDON and in_nets(self.client_ip(), INGRESS_NETS)):
                    return self.send_text("The page's core is reached through Home Assistant only.", 403)
                return self.web_host_relay(u, parts)
            if parts and parts[0] == "next":
                return self.serve_web(u, parts)
            if parts == ["api", "ws", "echo"]:
                return self.websocket_echo()
            if not parts or parts == ["index.html"]:
                return self.send_text(open(INDEX, "rb").read(), ctype="text/html; charset=utf-8")
            if parts[0] != "api":
                return self.send_json({"error": "not found"}, 404)
            return self.forward("GET", u)
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
            u = urllib.parse.urlsplit(self.path)
            if [p for p in u.path.split("/") if p][:1] != ["api"]:
                return self.send_json({"error": "not found"}, 404)
            return self.forward("POST", u, self.read_raw(BODY_MAX))
        except ValueError as e:
            return self.send_json({"error": str(e)}, 400)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log("POST error", self.path, repr(e))
            try:
                self.send_json({"error": repr(e)}, 500)
            except Exception:
                pass


def main():
    read_web_host_secret()   # first: no child may inherit the descriptor
    log(f"dieter bridge{' (add-on)' if ADDON else ''} on {BIND}:{PORT}, operator {OPERATOR_URL}")
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.daemon_threads = True
    srv.serve_forever()


if __name__ == "__main__":
    main()
