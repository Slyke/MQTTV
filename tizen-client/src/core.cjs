'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
class Failure extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
function ensureDir(directory) {
  if (fs.existsSync(directory)) { if (!fs.statSync(directory).isDirectory()) throw new Error('Data path is not a directory'); return; }
  ensureDir(path.dirname(directory)); fs.mkdirSync(directory, 0o700);
}
function atomic(file, value) {
  ensureDir(path.dirname(file));
  const temporary = file + '.tmp';
  const fd = fs.openSync(temporary, 'w', 0o600);
  try { fs.writeFileSync(fd, JSON.stringify(value)); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  fs.renameSync(temporary, file);
}
function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}';
  return JSON.stringify(value);
}
function integer(value, min, max, name) {
  if (!Number.isInteger(value) || value < min || value > max) throw new Failure('invalid_value', name + ' must be an integer from ' + min + ' to ' + max);
  return value;
}
function normalize(raw) {
  const c = JSON.parse(JSON.stringify(raw || {}));
  c.tv = Object.assign({ driver: 'samsung-tizen', host: '127.0.0.1', name: 'Samsung TV', timeoutMs: 5000, remoteName: 'MQTTV', token: '', applications: [] }, c.tv);
  if (c.tv.driver !== 'samsung-tizen') throw new Failure('invalid_config', 'Select the samsung-tizen driver for this package');
  // This application runs on the TV and controls only its own local services.
  if (!['127.0.0.1', 'localhost'].includes(c.tv.host)) throw new Failure('invalid_config', 'The Tizen driver uses the TV loopback address');
  if (typeof c.tv.name !== 'string' || !c.tv.name.trim() || c.tv.name.length > 128) throw new Failure('invalid_config', 'A TV name is required');
  if (typeof c.tv.remoteName !== 'string' || !c.tv.remoteName || typeof c.tv.token !== 'string') throw new Failure('invalid_config', 'Invalid Samsung pairing configuration');
  if (!Array.isArray(c.tv.applications) || c.tv.applications.some(a => !a || typeof a.name !== 'string' || typeof a.id !== 'string' || !/^[A-Za-z0-9._-]{1,128}$/.test(a.id))) throw new Failure('invalid_config', 'Invalid application list');
  c.tv.timeoutMs = integer(c.tv.timeoutMs, 500, 15000, 'tv.timeoutMs');
  c.mqtt = Object.assign({ uri: '', clientId: 'mqttv-samsung', baseTopic: '', username: '', password: '', keepAliveSeconds: 30, tls: {} }, c.mqtt);
  for (const key of ['uri','clientId','username','password','baseTopic']) if (typeof c.mqtt[key] !== 'string') throw new Failure('invalid_config', 'MQTT fields must be strings');
  if (!c.mqtt.clientId || c.mqtt.clientId.length > 128) throw new Failure('invalid_config', 'A unique MQTT client ID is required');
  integer(c.mqtt.keepAliveSeconds, 5, 3600, 'keepAliveSeconds');
  c.mqtt.tls = Object.assign({ insecureSkipVerify: false, trustSystemCAs: true }, c.mqtt.tls);
  for (const key of ['insecureSkipVerify','trustSystemCAs']) if (typeof c.mqtt.tls[key] !== 'boolean') throw new Failure('invalid_config', 'TLS options must be booleans');
  if (/^(mqtts|ssl|tls):/.test(c.mqtt.uri) && !c.mqtt.tls.insecureSkipVerify && !c.mqtt.tls.trustSystemCAs && !c.mqtt.tls.caFile && !c.mqtt.tls.caCertificatePem) throw new Failure('invalid_config', 'Provide a CA certificate or enable system trust');
  if (c.mqtt.uri && !/^(mqtts?|ssl|tls|tcp):\/\//.test(c.mqtt.uri)) throw new Failure('invalid_config', 'Unsupported MQTT URI');
  const defaultTopic = 'tvs/' + (c.tv.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'samsung-tv');
  c.mqtt.baseTopic = (c.mqtt.baseTopic || '').replace(/\/+$/, '');
  c.mqtt.effectiveBaseTopic = c.mqtt.baseTopic || defaultTopic;
  if (!c.mqtt.effectiveBaseTopic || /[+#\u0000]/.test(c.mqtt.effectiveBaseTopic)) throw new Failure('invalid_config', 'Invalid MQTT base topic');
  c.api = Object.assign({ passkey: '', bindAddress: '127.0.0.1', http: {}, https: {}, maxConnections: 16, maxWebSockets: 8, requestTimeoutMs: 20000 }, c.api);
  c.api.http = Object.assign({ enabled: false, port: 8788 }, c.api.http);
  c.api.https = Object.assign({ enabled: false, port: 8789 }, c.api.https);
  if (!c.api.passkey) c.api.passkey = crypto.randomBytes(32).toString('hex');
  if (typeof c.api.passkey !== 'string' || /[\r\n]/.test(c.api.passkey) || c.api.passkey.length > 4096) throw new Failure('invalid_config', 'Invalid API passkey');
  if (typeof c.api.bindAddress !== 'string' || !c.api.bindAddress || c.api.bindAddress.length > 253) throw new Failure('invalid_config', 'A listener address is required');
  for (const kind of ['http', 'https']) {
    if (typeof c.api[kind].enabled !== 'boolean') throw new Failure('invalid_config', 'API enable flags must be booleans');
    integer(c.api[kind].port, 1024, 65535, kind + ' port');
    if (c.api[kind].enabled && c.api[kind].port === 8790) throw new Failure('invalid_config', 'Port 8790 is reserved for local settings');
  }
  if (c.api.http.enabled && c.api.https.enabled && c.api.http.port === c.api.https.port) throw new Failure('invalid_config', 'API ports must differ');
  integer(c.api.maxConnections, 1, 64, 'maxConnections');
  integer(c.api.maxWebSockets, 1, 32, 'maxWebSockets');
  integer(c.api.requestTimeoutMs, 1000, 60000, 'requestTimeoutMs');
  c.service = Object.assign({ statePollSeconds: 5, stateHeartbeatSeconds: 60, stateRetain: true, deduplicationEntries: 256, maximumCommandAgeSeconds: 120 }, c.service);
  if (typeof c.service.stateRetain !== 'boolean') throw new Failure('invalid_config', 'stateRetain must be a boolean');
  integer(c.service.statePollSeconds, 1, 3600, 'statePollSeconds');
  integer(c.service.stateHeartbeatSeconds, 0, 86400, 'stateHeartbeatSeconds');
  integer(c.service.deduplicationEntries, 16, 4096, 'deduplicationEntries');
  integer(c.service.maximumCommandAgeSeconds, 0, 86400, 'maximumCommandAgeSeconds');
  return c;
}
function failure(id, action, received, error) {
  const now = new Date().toISOString();
  return { command_id: id, action, ok: false, received_at: received, completed_at: now, timestamp: now,
    error: { code: error.code || 'command_failed', message: error instanceof Failure ? error.message : 'Command could not be completed' } };
}
class Processor {
  constructor(file, options, runner) {
    this.file = file; this.options = options; this.runner = runner; this.queue = Promise.resolve(); this.queued = 0;
    if (fs.existsSync(file) && fs.statSync(file).size > 8 * 1024 * 1024) throw new Error('Command journal exceeds size limit');
    this.data = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : { entries: [], outbox: [] };
    if (!Array.isArray(this.data.entries) || !Array.isArray(this.data.outbox)) throw new Error('Invalid command journal');
    let changed = false;
    for (const e of this.data.entries) if (!e.ack) {
      e.ack = failure(e.id, e.action, e.received, new Failure('execution_interrupted', 'The service stopped during execution; the command was not repeated'));
      if (e.publish) this.append(e.ack);
      changed = true;
    }
    if (changed) this.save();
  }
  save() {
    if (Buffer.byteLength(JSON.stringify(this.data)) > 8 * 1024 * 1024) throw new Failure('journal_full', 'Command journal is full');
    try { atomic(this.file, this.data); } catch (error) { this.failed = true; throw new Failure('journal_unavailable', 'Durable command storage is unavailable; restart after fixing storage'); }
  }
  append(ack) {
    if (this.data.outbox.length >= 512) throw new Failure('outbox_full', 'Acknowledgement outbox is full');
    this.data.outbox.push({ token: crypto.randomBytes(16).toString('hex'), ack });
  }
  delivered(token) { this.data.outbox = this.data.outbox.filter(x => x.token !== token); this.save(); }
  submit(payload, retained, publish) {
    if (this.failed) return Promise.reject(new Failure('journal_unavailable', 'Durable command storage is unavailable'));
    if (this.queued >= 32) return Promise.reject(new Failure('busy', 'Command queue is full'));
    this.queued++;
    const run = this.queue.then(() => this.handle(payload, retained, publish));
    this.queue = run.catch(() => {});
    return run.then(r => { this.queued--; return r; }, e => { this.queued--; throw e; });
  }
  async handle(payload, retained, publish) {
    if (this.failed) throw new Failure('journal_unavailable', 'Durable command storage is unavailable');
    const received = new Date().toISOString(); let id = null; let action = null; let cmd;
    const enqueue = ack => { if (publish) { this.append(ack); this.save(); } return ack; };
    try {
      if (Buffer.byteLength(payload) > 16384) throw new Failure('payload_too_large', 'Commands are limited to 16 KiB');
      try { cmd = JSON.parse(payload.toString()); } catch (_) { throw new Failure('invalid_json', 'Command must contain one JSON object'); }
      if (!cmd || typeof cmd !== 'object' || Array.isArray(cmd)) throw new Failure('invalid_json', 'Command must contain one JSON object');
      if (typeof cmd.id === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(cmd.id)) id = cmd.id;
      if (typeof cmd.action === 'string' && /^[a-z][a-z_]{0,39}$/.test(cmd.action)) action = cmd.action;
      if (!id) throw new Failure('invalid_id', 'A valid command id is required');
      if (!action) throw new Failure('invalid_action', 'A lowercase action is required');
      if (retained) throw new Failure('retained_command', 'Retained commands are rejected');
    } catch (e) { return enqueue(failure(id, action, received, e)); }
    const hash = crypto.createHash('sha256').update(canonical({ action, value: cmd.value === undefined ? null : cmd.value })).digest('hex');
    const previous = this.data.entries.find(x => x.id === id);
    if (previous) return enqueue(previous.hash === hash ? Object.assign({}, previous.ack, { duplicate: true, timestamp: new Date().toISOString() }) :
      failure(id, action, received, new Failure('id_conflict', 'This id was used with different command contents')));
    try {
      const now = Date.now();
      for (const name of ['timestamp', 'expires_at']) if (cmd[name] !== undefined && (typeof cmd[name] !== 'string' || !/^\d{4}-\d\d-\d\dT.*Z$/.test(cmd[name]) || !Number.isFinite(Date.parse(cmd[name]))))
        throw new Failure('invalid_timestamp', 'Timestamps must be ISO-8601 UTC');
      if (cmd.expires_at && Date.parse(cmd.expires_at) <= now) throw new Failure('expired_command', 'Command has expired');
      if (cmd.timestamp && Date.parse(cmd.timestamp) > now + 60000) throw new Failure('invalid_timestamp', 'Timestamp is too far in the future');
      if (cmd.timestamp && this.options.maximumCommandAgeSeconds > 0 && Date.parse(cmd.timestamp) < now - this.options.maximumCommandAgeSeconds * 1000)
        throw new Failure('expired_command', 'Command timestamp is too old');
    } catch (e) { return enqueue(failure(id, action, received, e)); }
    if (publish && this.data.outbox.length >= 512) throw new Failure('outbox_full', 'Acknowledgement outbox is full');
    const entry = { id, hash, action, received, publish, ack: null };
    this.data.entries.push(entry);
    while (this.data.entries.length > this.options.deduplicationEntries) this.data.entries.shift();
    this.save(); // Persist intent before any side effect; never replay uncertain execution.
    const started = Date.now(); let ack;
    try {
      const result = await this.runner(cmd);
      if (Buffer.byteLength(JSON.stringify(result)) > 32768) throw new Failure('result_too_large', 'Command result exceeds 32 KiB');
      const now = new Date().toISOString();
      ack = { command_id: id, action, ok: true, received_at: received, completed_at: now, timestamp: now, result };
    } catch (e) { ack = failure(id, action, received, e); }
    ack.duration_ms = Date.now() - started; entry.ack = ack;
    if (publish) this.append(ack);
    this.save(); return ack;
  }
}
module.exports = { ensureDir, atomic, canonical, integer, normalize, Failure, Processor };
