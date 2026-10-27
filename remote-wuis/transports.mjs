import { EventEmitter } from 'node:events';
import https from 'node:https';
import http from 'node:http';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import mqtt from 'mqtt';
import WebSocket from 'ws';

export function command(action, value) {
  return { id: 'wui-' + randomUUID(), action, ...(value === undefined ? {} : { value }),
    timestamp: new Date().toISOString(), expires_at: new Date(Date.now() + 20000).toISOString() };
}
export function stateFresh(state) {
  return Boolean(state && (!state.expires_at || Date.parse(state.expires_at) > Date.now()));
}
class Transport extends EventEmitter {
  state = null;
  availability = null;
  pending = new Map();
  closed = false;
  info = {};
  update(patch) { Object.assign(this.info, patch); this.emit('transport', this.info); }
  snapshot(state) { this.state = state; this.emit('state', state); }
  ack(ack) {
    this.emit('ack', ack);
    const p = this.pending.get(ack.command_id);
    if (p) { clearTimeout(p.timer); this.pending.delete(ack.command_id); p.resolve(ack); }
  }
  wait(id) {
    if (this.pending.size >= 24) throw new Error('Too many commands in progress');
    if (this.pending.has(id)) throw new Error('This command is already in progress');
    let entry;
    const promise = new Promise((resolve, reject) => {
      entry = { resolve, reject, timer: setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('No acknowledgement received. The command may have executed; retry the same ID.'));
      }, 25000) };
      this.pending.set(id, entry);
    });
    return { promise, fail: error => {
      if (this.pending.get(id) !== entry) return;
      clearTimeout(entry.timer); this.pending.delete(id); entry.reject(error);
    } };
  }
  failPending(message) {
    for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(new Error(message)); }
    this.pending.clear();
  }
}

export class ApiTransport extends Transport {
  constructor(tv, host) {
    super();
    const api = tv.api;
    const secure = Boolean(api.https?.enabled);
    if (!secure && !api.http?.enabled) throw new Error('The TV API has no enabled listener');
    const address = host || api.bindAddress;
    if (!address || ['0.0.0.0', '::'].includes(address)) throw new Error('Set tvApiHost to the TV LAN IP');
    const formatted = address.includes(':') ? '[' + address + ']' : address;
    this.base = new URL((secure ? 'https://' : 'http://') + formatted + ':' + (secure ? api.https.port : api.http.port));
    this.passkey = api.passkey;
    if (!this.passkey) throw new Error('api.passkey is required');
    this.agent = secure ? new https.Agent({ rejectUnauthorized: false, maxSockets: 4, keepAlive: false }) : new http.Agent({ maxSockets: 4 });
    this.info = { mode: 'api', endpoint: this.base.origin, rest: 'not tested', websocket: 'connecting' };
    this.connect();
  }
  request(path, method = 'GET', body) {
    return new Promise((resolve, reject) => {
      const raw = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
      const request = (this.base.protocol === 'https:' ? https : http).request(new URL(path, this.base), {
        method, agent: this.agent, headers: { 'X-API-Key': this.passkey,
          ...(raw ? { 'Content-Type': 'application/json', 'Content-Length': raw.length } : {}) }
      }, response => {
        const chunks = []; let size = 0;
        response.on('data', chunk => { size += chunk.length; if (size > 262144) response.destroy(new Error('TV response too large')); else chunks.push(chunk); });
        response.on('error', reject);
        response.on('end', () => {
          try {
            const value = JSON.parse(Buffer.concat(chunks).toString());
            if (response.statusCode !== 200) throw new Error(value.error?.message || 'TV returned HTTP ' + response.statusCode);
            this.update({ rest: 'connected' }); resolve(value);
          } catch (error) { reject(error); }
        });
      });
      const deadline = setTimeout(() => request.destroy(new Error('TV REST request timed out')), 25000);
      request.on('close', () => clearTimeout(deadline));
      request.on('error', error => { this.update({ rest: 'unavailable' }); reject(error); });
      request.end(raw);
    });
  }
  connect() {
    if (this.closed) return;
    this.update({ websocket: 'connecting' });
    const url = new URL('/api/ws', this.base); url.protocol = this.base.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = this.socket = new WebSocket(url, {
      headers: { 'X-API-Key': this.passkey }, rejectUnauthorized: false,
      handshakeTimeout: 10000, maxPayload: 262144, perMessageDeflate: false
    });
    let alive = true;
    socket.on('open', () => this.update({ websocket: 'connected' }));
    socket.on('pong', () => { alive = true; });
    const ping = setInterval(() => {
      if (socket.readyState !== WebSocket.OPEN) return;
      if (!alive) return socket.terminate();
      alive = false; socket.ping();
    }, 20000);
    socket.on('message', raw => {
      try {
        const value = JSON.parse(raw.toString());
        if (value.state) this.snapshot(value.state);
        if (value.ack) this.ack(value.ack);
        if (value.error?.command_id) {
          const p = this.pending.get(value.error.command_id);
          if (p) { clearTimeout(p.timer); this.pending.delete(value.error.command_id); p.reject(new Error(value.error.message)); }
        }
      } catch {}
    });
    socket.on('error', () => this.update({ websocket: 'unavailable' }));
    socket.on('close', () => {
      clearInterval(ping); this.update({ websocket: 'disconnected' });
      this.failPending('TV WebSocket disconnected. Execution is uncertain; retry the same ID.');
      if (!this.closed) this.retry = setTimeout(() => this.connect(), 2000);
    });
  }
  async getState() {
    const value = await this.request('/api/state'); this.snapshot(value.state); return value.state;
  }
  async send(cmd, via = 'rest') {
    if (via === 'rest') { const ack = await this.request('/api/command', 'POST', cmd); this.ack(ack); return ack; }
    if (via !== 'ws') throw new Error('Choose REST or WebSocket');
    if (this.socket?.readyState !== WebSocket.OPEN) throw new Error('TV WebSocket is not connected');
    const pending = this.wait(cmd.id);
    this.socket.send(JSON.stringify(cmd), error => { if (error) pending.fail(error); });
    return pending.promise;
  }
  async publicKey() { return this.request('/api/public-key'); }
  close() { this.closed = true; clearTimeout(this.retry); this.socket?.terminate(); this.agent.destroy(); this.failPending('Server stopped'); }
}

export class MqttTransport extends Transport {
  constructor(tv, directory) {
    super();
    const config = tv.mqtt;
    const uri = config.uri.replace(/^(tls|ssl):/, 'mqtts:').replace(/^tcp:/, 'mqtt:');
    const url = new URL(uri);
    if (!['mqtt:', 'mqtts:'].includes(url.protocol)) throw new Error('Unsupported MQTT URI');
    this.base = config.baseTopic.replace(/\/+$/, '');
    const topics = this.topics = { command: this.base + '/cmnd/COMMAND', ack: this.base + '/stat/RESULT',
      state: this.base + '/tele/STATE', availability: this.base + '/tele/LWT' };
    const tls = config.tls || {};
    let ca = tls.caCertificatePem || (tls.caFile ? readFileSync(resolve(directory, tls.caFile)) : undefined);
    this.info = { mode: 'mqtt', endpoint: url.protocol + '//' + url.host, baseTopic: this.base, mqtt: 'connecting' };
    const client = this.client = mqtt.connect(uri, {
      username: config.username, password: config.password, clientId: 'tv-wui-' + randomUUID().slice(0, 12),
      protocolVersion: 4, clean: true, keepalive: 30, connectTimeout: 10000,
      reconnectPeriod: 2000, reconnectOnConnackError: true, queueQoSZero: false,
      rejectUnauthorized: !tls.insecureSkipVerify, ...(ca ? { ca } : {}),
      resubscribe: false
    });
    client.on('connect', () => {
      this.update({ mqtt: 'subscribing' });
      client.subscribe([topics.ack, topics.state, topics.availability], { qos: 1 }, (error, grants) => {
        if (error || grants?.some(g => g.qos === 128)) this.update({ mqtt: 'subscription failed' });
        else this.update({ mqtt: 'connected' });
      });
    });
    client.on('reconnect', () => this.update({ mqtt: 'connecting' }));
    client.on('error', () => this.update({ mqtt: 'unavailable' }));
    client.on('close', () => {
      this.update({ mqtt: 'disconnected' });
      this.failPending('Broker disconnected. Execution is uncertain; retry the same ID.');
      // A clean reconnect must not resend an old navigation command.
      for (const id of Object.keys(client.outgoing)) client.removeOutgoingMessage(Number(id));
    });
    client.on('message', (topic, bytes, packet) => {
      if (bytes.length > 262144) return;
      try {
        const value = JSON.parse(bytes.toString());
        if (topic === topics.state) this.snapshot(value);
        else if (topic === topics.ack && !packet.retain) this.ack(value);
        else if (topic === topics.availability) { this.availability = value; this.emit('availability', value); }
      } catch {}
    });
  }
  async send(cmd) {
    if (!this.client.connected || this.info.mqtt !== 'connected') throw new Error('MQTT is not connected');
    if (this.availability?.online === false || !stateFresh(this.state)) throw new Error('TV state is offline or stale; wait for a fresh update');
    const pending = this.wait(cmd.id);
    this.client.publish(this.topics.command, JSON.stringify(cmd), { qos: 1, retain: false },
      error => { if (error) pending.fail(error); });
    return pending.promise;
  }
  async getState() {
    const ack = await this.send(command('status'));
    if (!ack.ok) throw new Error(ack.error?.message || 'Status command failed');
    const seconds = this.state?.stale_after_seconds ?? 180;
    const state = { ...this.state, ...ack.result, timestamp: ack.completed_at,
      expires_at: seconds > 0 ? new Date(Date.parse(ack.completed_at) + seconds * 1000).toISOString() : null };
    this.snapshot(state); return state;
  }
  close() { this.closed = true; this.client.end(true); this.failPending('Server stopped'); }
}
