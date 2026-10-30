// Static server for the packaged bundle, shaped like Home Assistant's Ingress for the page:
// the bundle at /api/hassio_ingress/t0ken/next/, no Accept-Encoding handling (Ingress strips it),
// .wasm.gz as raw bytes for the loader.
// Usage: node serve.mjs <bundle dir> [port]
import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(process.argv[2] || "");
const port = Number(process.argv[3] || process.env.PORT || 18780);
const PREFIX = "/api/hassio_ingress/t0ken/next";
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".wasm": "application/wasm",
  ".json": "application/json", ".ttf": "font/ttf", ".txt": "text/plain", ".gz": "application/octet-stream" };

function send(res, file) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404, { "content-type": "text/plain" }); return res.end("not found"); }
    res.writeHead(200, { "content-type": TYPES[path.extname(file)] || "application/octet-stream", "content-length": st.size, "cache-control": "no-cache" });
    fs.createReadStream(file).pipe(res);
  });
}

http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  let p = decodeURIComponent(url.pathname);
  if (p === PREFIX) { res.writeHead(302, { location: "next/" }); return res.end(); }
  if (!p.startsWith(PREFIX + "/")) { res.writeHead(404); return res.end(); }
  p = p.slice(PREFIX.length + 1) || "index.html";
  const file = path.join(root, path.normalize(p));
  if (!file.startsWith(root)) { res.writeHead(403); return res.end(); }
  send(res, file);
}).listen(port, "127.0.0.1", () => console.log(`bundle ${root} on http://127.0.0.1:${port}${PREFIX}/`));
