import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { timingSafeEqual } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { ApiTransport, MqttTransport } from './transports.mjs';

const here = dirname(fileURLToPath(import.meta.url));
function same(a, b) {
  if (typeof a !== 'string' || !b) return false;
  const left = Buffer.from(a), right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}
function cookie(req) {
  const item = String(req.headers.cookie || '').split(';').map(v => v.trim()).find(v => v.startsWith('tv_wui='));
  try { return item ? decodeURIComponent(item.slice(7)) : ''; } catch { return ''; }
}
function json(res, code, value, headers = {}) {
  const body = JSON.stringify(value);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Content-Length': Buffer.byteLength(body), ...headers });
  res.end(body);
}
async function body(req) {
  const parts = []; let count = 0;
  for await (const part of req) {
    count += part.length; if (count > 16384) throw new Error('Request is limited to 16 KiB');
    parts.push(part);
  }
  const data = JSON.parse(Buffer.concat(parts).toString());
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('Expected a JSON object');
  return data;
}
function validCommand(cmd) {
  if (!cmd || typeof cmd !== 'object' || Array.isArray(cmd) ||
      typeof cmd.id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(cmd.id) ||
      typeof cmd.action !== 'string' || !/^[a-z][a-z_]{0,39}$/.test(cmd.action))
    throw new Error('Command requires a valid id and action');
  return cmd;
}
export async function startRemote(mode) {
  if (!['api', 'mqtt'].includes(mode)) throw new Error('Unknown remote mode');
  const configIndex = process.argv.indexOf('--config');
  if (configIndex >= 0 && !process.argv[configIndex + 1]) throw new Error('--config requires a file path');
  const configPath = resolve(configIndex >= 0 ? process.argv[configIndex + 1] : process.env.WUI_CONFIG || join(here, 'config.local.json'));
  if (!existsSync(configPath)) throw new Error('Create remote-wuis/config.local.json first');
  const config = JSON.parse(readFileSync(configPath, 'utf8'));
  const tvPath = resolve(dirname(configPath), config.tvConfigPath);
  const tv = JSON.parse(readFileSync(tvPath, 'utf8'));
  const requireLogin = config.requireLogin !== false;
  if (requireLogin && (typeof config.accessToken !== 'string' || config.accessToken.length < 24))
    throw new Error('Configure a WUI accessToken of at least 24 characters');
  const bind = config.bindAddress || '127.0.0.1';
  const port = mode === 'api' ? config.apiPort : config.mqttPort;
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid WUI port');
  const transport = mode === 'api' ? new ApiTransport(tv, config.tvApiHost) : new MqttTransport(tv, dirname(tvPath));
  const clients = new WebSocketServer({ noServer: true, maxPayload: 16384, perMessageDeflate: false });
  const authorized = req => !requireLogin || same(req.headers['x-remote-token'], config.accessToken) || same(cookie(req), config.accessToken);
  const originOkay = req => !req.headers.origin || req.headers.origin === 'http://' + req.headers.host;
  const send = (socket, value) => {
    if (socket.readyState !== WebSocket.OPEN) return;
    if (socket.bufferedAmount > 262144) return socket.terminate();
    socket.send(JSON.stringify(value));
  };
  const broadcast = value => { for (const socket of clients.clients) send(socket, value); };
  for (const type of ['state', 'ack', 'transport', 'availability']) transport.on(type, value => broadcast({ [type]: value }));
  const assets = new Map([
    ['/', { content: readFileSync(join(here, 'public/index.html')), type: 'text/html; charset=utf-8' }],
    ['/app.js', { content: readFileSync(join(here, 'public/app.js')), type: 'text/javascript; charset=utf-8' }]
  ]);
  const server = http.createServer({ maxHeaderSize: 16384, requestTimeout: 30000, headersTimeout: 10000 }, async (req, res) => {
    try {
      const url = new URL(req.url, 'http://local');
      if (req.method === 'GET' && url.pathname === '/health')
        return json(res, 200, { mode, running: true });
      if (!originOkay(req)) return json(res, 403, { error: 'Origin rejected' });
      if (req.method === 'GET' && assets.has(url.pathname)) {
        const asset = assets.get(url.pathname);
        res.writeHead(200, { 'Content-Type': asset.type, 'Cache-Control': 'no-store', 'Content-Length': asset.content.length,
          'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
          'Content-Security-Policy': "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'" });
        return res.end(asset.content);
      }
      if (req.method === 'POST' && url.pathname === '/api/login') {
        if (!requireLogin) return json(res, 200, { ok: true });
        if (!same(req.headers['x-remote-token'], config.accessToken)) return json(res, 401, { error: 'Invalid remote access token' });
        return json(res, 200, { ok: true }, { 'Set-Cookie': 'tv_wui=' + encodeURIComponent(config.accessToken) + '; HttpOnly; SameSite=Strict; Path=/; Max-Age=2592000' });
      }
      if (!authorized(req)) return json(res, 401, { error: 'Sign in to this remote first' });
      if (req.method === 'GET' && url.pathname === '/api/info')
        return json(res, 200, { mode, requireLogin, device: tv.mqtt.clientId, transport: transport.info, state: transport.state, availability: transport.availability });
      if (req.method === 'GET' && url.pathname === '/api/state')
        return json(res, 200, { state: await transport.getState() });
      if (req.method === 'GET' && url.pathname === '/api/public-key' && mode === 'api')
        return json(res, 200, await transport.publicKey());
      if (req.method === 'POST' && url.pathname === '/api/command') {
        const data = await body(req);
        const ack = await transport.send(validCommand(data.command), mode === 'api' ? (data.via || 'rest') : 'mqtt');
        return json(res, 200, { ack });
      }
      return json(res, 404, { error: 'Not found' });
    } catch (error) {
      if (!res.headersSent) json(res, 502, { error: error.message || 'Remote request failed' });
      else res.destroy();
    }
  });
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://local');
    if (url.pathname !== '/live' || !authorized(req) || !originOkay(req)) {
      socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return;
    }
    if (clients.clients.size >= 16) { socket.end('HTTP/1.1 503 Busy\r\nConnection: close\r\n\r\n'); return; }
    clients.handleUpgrade(req, socket, head, ws => clients.emit('connection', ws, req));
  });
  clients.on('connection', socket => {
    socket.alive = true;
    socket.on('pong', () => { socket.alive = true; });
    socket.on('error', () => {});
    send(socket, { transport: transport.info, ...(transport.state ? { state: transport.state } : {}),
      ...(transport.availability ? { availability: transport.availability } : {}) });
    let queued = 0;
    socket.on('message', async (raw, binary) => {
      let request;
      try {
        if (binary) throw new Error('Send JSON text');
        request = JSON.parse(raw.toString());
        if (typeof request.request_id !== 'string' || request.request_id.length > 128) throw new Error('Missing request id');
        if (queued >= 8) throw new Error('Too many commands in progress');
        const cmd = validCommand(request.command);
        queued++;
        try {
          const ack = await transport.send(cmd, mode === 'api' ? 'ws' : 'mqtt');
          send(socket, { reply: { request_id: request.request_id, ack } });
        } finally { queued--; }
      } catch (error) { send(socket, { reply: { request_id: request?.request_id, error: error.message || 'Request failed' } }); }
    });
  });
  const timer = setInterval(() => {
    for (const socket of clients.clients) {
      if (!socket.alive) { socket.terminate(); continue; }
      socket.alive = false; socket.ping();
    }
  }, 20000);
  const stop = () => {
    clearInterval(timer); transport.close();
    for (const socket of clients.clients) socket.terminate();
    clients.close(); server.close();
  };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, bind, resolve); });
  console.log('TV ' + mode.toUpperCase() + ' remote listening on http://' + bind + ':' + port);
  return { server, transport, stop };
}
