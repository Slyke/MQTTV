import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import { command } from '../transports.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const config = JSON.parse(readFileSync(process.argv[2] || join(root, 'config.local.json'), 'utf8'));
const requireLogin = config.requireLogin !== false;
const headers = requireLogin ? { 'X-Remote-Token': config.accessToken } : {};
const results = [];
function pass(label) { results.push(label); console.log('PASS ' + label); }
async function request(base, path, options = {}, status = 200) {
  const response = await fetch(base + path, { signal: AbortSignal.timeout(30000), ...options });
  assert.equal(response.status, status, path + ' status');
  return response;
}
async function data(base, path, value, extra = {}) {
  const response = await request(base, path, { headers: { ...headers, 'Content-Type': 'application/json', ...extra },
    ...(value ? { method: 'POST', body: JSON.stringify(value) } : {}) });
  return response.json();
}
async function open(base, auth = true) {
  const socket = new WebSocket(base.replace('http:', 'ws:') + '/live', {
    headers: { Origin: base, ...(auth ? headers : {}) }, perMessageDeflate: false
  });
  const events = []; const waiting = [];
  socket.on('message', raw => {
    const value = JSON.parse(raw.toString()); events.push(value);
    for (const waiter of [...waiting]) {
      if (waiter.predicate(value)) { waiting.splice(waiting.indexOf(waiter), 1); clearTimeout(waiter.timer); waiter.resolve(value); }
    }
  });
  socket.on('error', () => {});
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
  const next = predicate => {
    const found = events.find(predicate); if (found) return Promise.resolve(found);
    return new Promise((resolve, reject) => {
      const waiter = { predicate, resolve, timer: setTimeout(() => { waiting.splice(waiting.indexOf(waiter), 1); reject(new Error('WebSocket event timed out')); }, 28000) };
      waiting.push(waiter);
    });
  };
  return { socket, next, events };
}
async function badWs(base, extra = {}) {
  const socket = new WebSocket(base.replace('http:', 'ws:') + '/live', { headers: extra });
  await new Promise((resolve, reject) => {
    socket.once('open', () => { socket.terminate(); reject(new Error('Unauthorized upgrade accepted')); });
    socket.once('unexpected-response', (_, response) => { assert.equal(response.statusCode, 401); response.resume(); socket.terminate(); resolve(); });
    socket.on('error', () => {});
  });
}
const api = 'http://' + config.bindAddress + ':' + config.apiPort;
const mqtt = 'http://' + config.bindAddress + ':' + config.mqttPort;
const sockets = [];
try {
  for (const [mode, base] of [['api', api], ['mqtt', mqtt]]) {
    await request(base, '/api/info', { headers: { ...headers, Origin: 'https://example.invalid' } }, 403);
    await badWs(base, { ...headers, Origin: 'https://example.invalid' });
    let info;
    if (requireLogin) {
      await request(base, '/api/info', {}, 401);
      await request(base, '/api/info?token=' + encodeURIComponent(config.accessToken), {}, 401);
      await badWs(base);
      pass(mode + ': REST and WebSocket reject missing auth, query auth, and foreign origins');
      const login = await request(base, '/api/login', { method: 'POST', headers });
      const cookie = login.headers.get('set-cookie').split(';')[0];
      info = await (await request(base, '/api/info', { headers: { Cookie: cookie } })).json();
      pass(mode + ': token login creates working HttpOnly cookie');
    } else {
      info = await (await request(base, '/api/info')).json();
      assert.equal(info.requireLogin, false);
      pass(mode + ': plain URL works without login; foreign origins still rejected');
      const anonymous = await open(base, false);
      sockets.push(anonymous.socket);
      await anonymous.next(x => x.state);
      pass(mode + ': browser state stream works without cookies or a token');
    }
    assert.equal(info.mode, mode);
    assert.equal(JSON.stringify(info).includes(config.accessToken), false);
    const ws = await open(base); sockets.push(ws.socket);
    const streamed = await ws.next(x => x.state);
    assert.ok(streamed.state.network.ips);
    pass(mode + ': browser WebSocket receives state envelope and interface IPs');
    const state = (await data(base, '/api/state')).state;
    assert.ok(['active', 'standby'].includes(state.power));
    assert.ok(state.timestamp); assert.ok(state.memory.total_mb);
    pass(mode + ': current state includes power, timestamp, memory and network');
  }
  const cmd = command('status');
  const rest = (await data(api, '/api/command', { command: cmd, via: 'rest' })).ack;
  assert.equal(rest.ok, true); assert.equal(rest.command_id, cmd.id);
  assert.ok(rest.received_at); assert.ok(rest.completed_at);
  pass('API: REST POST returns matching command ID and acknowledgement timestamps');
  const apiWs = await open(api); sockets.push(apiWs.socket);
  const requestId = 'ws-' + cmd.id;
  apiWs.socket.send(JSON.stringify({ request_id: requestId, command: cmd }));
  const reply = (await apiWs.next(x => x.reply?.request_id === requestId)).reply;
  assert.equal(reply.ack.ok, true); assert.equal(reply.ack.duplicate, true);
  pass('API: browser WS -> TV WSS shares command IDs and deduplicates REST retry');
  const mqttAck = (await data(mqtt, '/api/command', { command: cmd })).ack;
  assert.equal(mqttAck.ok, true); assert.equal(mqttAck.duplicate, true);
  pass('MQTT: same command is deduplicated across REST, WSS and MQTT');
  const key = await data(api, '/api/public-key');
  assert.ok(JSON.stringify(key).includes('BEGIN PUBLIC KEY'));
  assert.equal(JSON.stringify(key).includes('PRIVATE KEY'), false);
  pass('API: public-key route exposes only public identity');
  const rawInfo = await data(api, '/api/info');
  assert.equal(rawInfo.state.api.http_enabled, false);
  assert.equal(rawInfo.state.api.https_enabled, true);
  pass('TV remains HTTPS-only');
  const before = sockets[0];
  const waiting = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Fresh streamed state not observed')), 12000);
    before.on('message', raw => { const value = JSON.parse(raw.toString()); if (value.state && Date.parse(value.state.timestamp) >= Date.parse(rest.completed_at)) { clearTimeout(timer); resolve(); } });
  });
  const fresh = command('status');
  await data(mqtt, '/api/command', { command: fresh });
  await waiting;
  pass('State changes triggered by MQTT stream into API browser WSS session');
  mkdirSync(join(root, '.runtime'), { recursive: true });
  writeFileSync(join(root, '.runtime/smoke-results.json'), JSON.stringify({ at: new Date().toISOString(), results }, null, 2));
  console.log(results.length + ' read-only WUI integration checks passed');
} finally { for (const socket of sockets) socket.terminate(); }
