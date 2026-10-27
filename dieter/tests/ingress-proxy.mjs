// Stand-in for the Supervisor's Ingress proxy for local end-to-end tests: serves the bridge under
// /api/hassio_ingress/<token>/, adds X-Ingress-Path and forwards request bodies chunked (as the
// Supervisor does), streams SSE through. Usage: node ingress-proxy.mjs  (PROXY_PORT, BRIDGE_PORT)
import http from "node:http";

const TARGET = { host: "127.0.0.1", port: Number(process.env.BRIDGE_PORT || 18099) };
const PREFIX = "/api/hassio_ingress/t0ken";
const PORT = Number(process.env.PROXY_PORT || 18100);

http
  .createServer((req, res) => {
    if (!req.url.startsWith(PREFIX)) {
      res.writeHead(302, { Location: `${PREFIX}/` });
      return res.end();
    }
    const path = req.url.slice(PREFIX.length) || "/";
    const headers = { ...req.headers, "x-ingress-path": PREFIX, host: "local-dieter:8099" };
    delete headers["content-length"];
    const upstream = http.request({ ...TARGET, method: req.method, path, headers }, (r) => {
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
