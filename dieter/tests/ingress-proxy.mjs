// Stand-in for the Supervisor's Ingress proxy for local end-to-end tests: serves the add-on under
// /api/hassio_ingress/<token>/, adds X-Ingress-Path and forwards request bodies chunked (as the
// Supervisor does), streams SSE through, passes WebSocket upgrades on and, like Home Assistant,
// drops Accept-Encoding (HA compresses at its border instead) and names the signed-in person on every
// request, sockets included (E2E_USER, default "E2E Tester"; a request's own X-Remote-User-* wins).
// Usage: node ingress-proxy.mjs  (PROXY_PORT, BRIDGE_PORT)
import http from "node:http";
import net from "node:net";

// from 127.0.0.2, the address run.sh lets reach the page's core (BRIDGE_INGRESS_NETS) as the Supervisor's network does
const TARGET = { host: "127.0.0.1", port: Number(process.env.BRIDGE_PORT || 18099), localAddress: "127.0.0.2" };
const PREFIX = "/api/hassio_ingress/t0ken";
const PORT = Number(process.env.PROXY_PORT || 18100);

const USER = process.env.E2E_USER || "E2E Tester";

const upstreamHeaders = (req) => {
  const person = { "x-remote-user-id": "e2e", "x-remote-user-name": "e2e", "x-remote-user-display-name": USER };
  const headers = { ...person, ...req.headers, "x-ingress-path": PREFIX, host: "local-dieter:8099" };
  delete headers["content-length"];
  delete headers["accept-encoding"];
  return headers;
};

const server = http
  .createServer((req, res) => {
    if (!req.url.startsWith(PREFIX)) {
      res.writeHead(302, { Location: `${PREFIX}/` });
      return res.end();
    }
    const path = req.url.slice(PREFIX.length) || "/";
    const upstream = http.request({ ...TARGET, method: req.method, path, headers: upstreamHeaders(req) }, (r) => {
      res.writeHead(r.statusCode, r.headers);
      r.pipe(res);
    });
    upstream.on("error", (error) => {
      res.writeHead(502, { "content-type": "text/plain" });
      res.end(`proxy: ${error.message}`);
    });
    req.pipe(upstream);
  })
  .listen(PORT, "127.0.0.1", () => console.log(`ingress proxy on http://127.0.0.1:${PORT}${PREFIX}/ -> ${TARGET.port}`));

server.on("upgrade", (req, socket, head) => {
  if (!req.url.startsWith(PREFIX)) return socket.destroy();
  const path = req.url.slice(PREFIX.length) || "/";
  const upstream = net.connect(TARGET, () => {
    const headers = upstreamHeaders(req);
    upstream.write(`GET ${path} HTTP/1.1\r\n` + Object.entries(headers).map(([k, v]) => `${k}: ${v}`).join("\r\n") + "\r\n\r\n");
    if (head && head.length) upstream.write(head);
    upstream.pipe(socket);
    socket.pipe(upstream);
  });
  upstream.on("error", () => socket.destroy());
  socket.on("error", () => upstream.destroy());
});
