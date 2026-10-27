import http from "node:http";
import { readFile } from "node:fs/promises";

const port = Number(process.argv[2] ?? 8800);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("Use a port from 1024 to 65535.");
const assets = new URL("../app/src/main/assets/remote/", import.meta.url);
const files = new Map([
  ["/app.js", [new URL("app.js", assets), "text/javascript; charset=utf-8"]],
  ["/style.css", [new URL("style.css", assets), "text/css; charset=utf-8"]],
  ["/preview.js", [new URL("preview.js", import.meta.url), "text/javascript; charset=utf-8"]],
  ["/viewport.css", [new URL("viewport.css", import.meta.url), "text/css; charset=utf-8"]],
  ["/artwork.css", [new URL("artwork.css", import.meta.url), "text/css; charset=utf-8"]],
  ["/icon.svg", [new URL("../artwork/icon.svg", import.meta.url), "image/svg+xml"]],
  ["/tv-banner.svg", [new URL("../artwork/tv-banner.svg", import.meta.url), "image/svg+xml"]]
]);
const server = http.createServer(async (request, response) => {
  const headers = {
    "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'none'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'self'"
  };
  try {
    const path = new URL(request.url, "http://preview.local").pathname;
    if (!["GET", "HEAD"].includes(request.method)) {
      response.writeHead(405, headers);
      return response.end("This preview serves static files only.");
    }
    if (path === "/favicon.ico") { response.writeHead(204, headers); return response.end(); }
    let content, type;
    if (path === "/artwork") {
      content = '<!doctype html><html lang="en"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>MQTTV launcher artwork</title><link rel="stylesheet" href="/artwork.css"></head><body><main><header><h1>TV MQTT &amp; API Client</h1><p>Launcher icon &amp; TV banner</p></header><div class="icons"><figure><img src="/icon.svg" width="160" height="160" alt="MQTTV square launcher icon"><figcaption>Launcher icon</figcaption></figure><figure class="round"><img src="/icon.svg" width="160" height="160" alt="MQTTV round launcher icon"><figcaption>Round launcher mask</figcaption></figure></div><figure class="banner"><img src="/tv-banner.svg" width="640" height="360" alt="MQTTV Android TV banner"><figcaption>Android TV launcher banner</figcaption></figure><a href="/">Back to remote preview</a></main></body></html>';
      type = "text/html; charset=utf-8";
    } else if (path === "/mobile") {
      const requested = Number(new URL(request.url, "http://preview.local").searchParams.get("width") ?? 390);
      const width = [320, 360, 390, 430, 768].includes(requested) ? requested : 390;
      content = '<!doctype html><html lang="en"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Phone layout preview</title><link rel="stylesheet" href="/viewport.css"></head><body><header><strong>Phone preview</strong><span>'+width+' px</span><a href="/">Open remote</a></header><iframe id="phone-preview" title="Phone remote preview" src="/" width="'+width+'" height="1200"></iframe></body></html>';
      type = "text/html; charset=utf-8";
    } else if (path === "/") {
      content = (await readFile(new URL("index.html", assets), "utf8"))
        .replace('<script src="./app.js">', '<script src="./preview.js"></script>\n  <script src="./app.js">');
      type = "text/html; charset=utf-8";
    } else {
      const entry = files.get(path);
      if (!entry) { response.writeHead(404, headers); return response.end("Not found"); }
      content = await readFile(entry[0]);
      type = entry[1];
    }
    response.writeHead(200, { ...headers, "Content-Type": type });
    return response.end(request.method === "HEAD" ? undefined : content);
  } catch (error) {
    console.error(JSON.stringify({ event: "preview_request_failed", message: error.message }));
    response.writeHead(500, headers);
    return response.end("Preview unavailable");
  }
});
server.on("error", error => { console.error(error.message); process.exitCode = 1; });
server.listen(port, "0.0.0.0", () => console.log("Simple remote style preview on http://0.0.0.0:" + port + " (mock data; no device connections)"));
