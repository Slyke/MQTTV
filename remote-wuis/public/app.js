const $ = selector => document.querySelector(selector);
const notice = $('#notice');
let mode, state, availability, transport = {}, live, retry, signedIn = false;
const pending = new Map();
const seenAcks = new Set();
const uid = () => 'wui-' + Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
function makeCommand(action, value) {
  return { id: uid(), action, ...(value === undefined ? {} : { value }),
    timestamp: new Date().toISOString(), expires_at: new Date(Date.now() + 20000).toISOString() };
}
async function request(path, options = {}) {
  const response = await fetch(path, options);
  const value = await response.json();
  if (!response.ok) throw new Error(value.error || 'Request failed');
  return value;
}
const post = (path, value) => request(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
function fail(error) { notice.textContent = error.message || String(error); }
function render() {
  document.querySelectorAll('[data-input], [data-key="input"]').forEach(button => { button.disabled = state?.capabilities?.input === false; });
  $('#power').textContent = 'Power ' + (state?.power || 'unknown');
  $('#volume').textContent = 'Volume ' + (state?.volume ?? '\u2014') + (state?.muted ? ' \u00b7 muted' : '');
  const stale = state?.expires_at && Date.parse(state.expires_at) <= Date.now();
  const online = mode === 'api' ? transport.websocket === 'connected' : transport.mqtt === 'connected' && availability?.online !== false;
  $('#connection').textContent = !online ? 'Connection unavailable' : stale ? 'Last known state \u00b7 stale' : state ? 'TV service online' : 'Waiting for state';
  $('#link-status').textContent = mode === 'api' ? 'REST ' + transport.rest + ' \u00b7 WSS ' + transport.websocket : 'MQTT ' + transport.mqtt;
  $('#watching').textContent = state?.media?.display_title || state?.media?.title || state?.content?.title || state?.foreground_app?.label || '\u2014';
  $('#memory').textContent = state?.memory ? state.memory.available_mb + ' / ' + state.memory.total_mb + ' MiB available' : '\u2014';
  $('#network').textContent = state?.network?.primary_ip || '\u2014';
  $('#updated').textContent = state?.timestamp ? new Date(state.timestamp).toLocaleString() + (stale ? ' (stale)' : '') : '\u2014';
  $('#state-json').textContent = state ? JSON.stringify({ state }, null, 2) : 'Waiting for state';
  $('#availability-json').textContent = availability ? JSON.stringify(availability, null, 2) : (mode === 'api' ? 'HTTPS / WSS connection status is shown above.' : 'Waiting for retained availability');
}
function acknowledgement(ack) {
  if (!ack || seenAcks.has(ack.command_id + ':' + ack.timestamp)) return;
  seenAcks.add(ack.command_id + ':' + ack.timestamp);
  if (seenAcks.size > 100) seenAcks.delete(seenAcks.values().next().value);
  const row = document.createElement('li');
  row.textContent = (ack.ok ? 'OK ' : 'Failed ') + ack.action + ' \u00b7 ' + ack.command_id + ' \u00b7 ' + ack.timestamp;
  $('#acks').prepend(row);
  while ($('#acks').children.length > 8) $('#acks').lastChild.remove();
  $('#ack-json').textContent = JSON.stringify(ack, null, 2);
}
function connectLive() {
  clearTimeout(retry);
  live = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/live');
  live.onmessage = event => {
    const data = JSON.parse(event.data);
    if (data.state) state = data.state;
    if (data.availability) availability = data.availability;
    if (data.transport) transport = data.transport;
    if (data.ack) acknowledgement(data.ack);
    if (data.reply) {
      const p = pending.get(data.reply.request_id);
      if (p) {
        clearTimeout(p.timer); pending.delete(data.reply.request_id);
        if (data.reply.error) p.reject(new Error(data.reply.error)); else p.resolve(data.reply.ack);
      }
    }
    render();
  };
  live.onclose = () => {
    $('#connection').textContent = 'Remote connection lost';
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(new Error('Connection lost. Execution is uncertain.')); }
    pending.clear();
    if (signedIn) retry = setTimeout(connectLive, 2000);
  };
}
async function execute(cmd, via) {
  let ack;
  if (mode === 'api' && via === 'ws') {
    if (live?.readyState !== WebSocket.OPEN) throw new Error('WebSocket relay is not connected');
    ack = await new Promise((resolve, reject) => {
      const requestId = uid();
      const timer = setTimeout(() => { pending.delete(requestId); reject(new Error('Timed out. Execution is uncertain; retry the same command ID.')); }, 28000);
      pending.set(requestId, { resolve, reject, timer });
      live.send(JSON.stringify({ request_id: requestId, command: cmd }));
    });
  } else ack = (await post('/api/command', { command: cmd, via })).ack;
  acknowledgement(ack);
  if (!ack.ok) throw new Error(ack.error?.message || 'TV rejected the command');
  return ack;
}
async function send(action, value, via = $('#via').value) {
  notice.textContent = 'Sending ' + action + '\u2026';
  const ack = await execute(makeCommand(action, value), via);
  notice.textContent = 'Acknowledged ' + action + ' \u00b7 ' + ack.command_id;
  return ack;
}
document.querySelectorAll('[data-key]').forEach(button => button.onclick = async () => {
  try {
    let key = button.dataset.key;
    if (key === 'power') {
      if (!state || !['active', 'standby'].includes(state.power) || (state.expires_at && Date.parse(state.expires_at) <= Date.now())) throw new Error('Refresh state before toggling power');
      await send(state.power === 'active' ? 'power_off' : 'power_on'); return;
    }
    const actions = { volumeUp: 'volume_up', volumeDown: 'volume_down', forward: 'fast_forward' };
    if (key === 'input') await send('key', 'input'); else await send(actions[key] || key);
  } catch (error) { fail(error); }
});
const apps = { youtube: 'YouTube', smarttube: 'SmartTube', jellyfin: 'Jellyfin', tv: 'TV', mirror: 'Screen mirroring' };
document.querySelectorAll('[data-app]').forEach(button => button.onclick = () => send('launch', apps[button.dataset.app]).catch(fail));
document.querySelectorAll('[data-input]').forEach(button => button.onclick = () => send('input', button.dataset.input).catch(fail));
async function sendText() {
  const field = $('#text'); if (!field.value) return;
  const value = field.value; await send('text', value); if (field.value === value) field.value = '';
}
$('#send').onclick = () => sendText().catch(fail);
$('#text').onkeydown = event => { if (event.key === 'Enter') sendText().catch(fail); };
let queuedDeletes = 0, deleting = false, deleteTimer, holdDelay, holdRepeat;
function stopDeleting() { clearTimeout(holdDelay); clearInterval(holdRepeat); }
async function flushDeletes() {
  clearTimeout(deleteTimer); deleteTimer = null;
  if (deleting || !queuedDeletes) return;
  deleting = true;
  const count = queuedDeletes; queuedDeletes = 0;
  try { await send('backspace', count); }
  catch (error) { queuedDeletes = 0; stopDeleting(); fail(error); }
  finally { deleting = false; if (queuedDeletes) flushDeletes(); }
}
function queueDelete() {
  queuedDeletes = Math.min(240, queuedDeletes + 1);
  notice.textContent = 'Deleting on TV' + (queuedDeletes > 1 ? ' (' + queuedDeletes + ' queued)' : '') + '...';
  if (!deleting && !deleteTimer) deleteTimer = setTimeout(flushDeletes, 50);
}
const deleteButton = $('#backspace');
deleteButton.style.touchAction = 'none';
deleteButton.onpointerdown = event => {
  if (event.button !== 0) return;
  event.preventDefault(); stopDeleting(); deleteButton.setPointerCapture(event.pointerId);
  queueDelete();
  holdDelay = setTimeout(() => { holdRepeat = setInterval(queueDelete, 90); }, 350);
};
deleteButton.onpointerup = deleteButton.onpointercancel = deleteButton.onlostpointercapture = stopDeleting;
deleteButton.onclick = event => { if (event.detail === 0) queueDelete(); };
deleteButton.oncontextmenu = event => event.preventDefault();
window.addEventListener('blur', stopDeleting);
document.addEventListener('visibilitychange', () => { if (document.hidden) stopDeleting(); });
$('#select-all').onclick = () => send('select_all').catch(fail);
$('#refresh').onclick = async () => {
  try { state = (await request('/api/state')).state; render(); notice.textContent = 'Current state received'; } catch (error) { fail(error); }
};
$('#test-rest').onclick = async () => {
  try {
    $('#rest-result').textContent = 'Testing\u2026';
    const fresh = (await request('/api/state')).state;
    if (!fresh?.power) throw new Error('REST state envelope missing');
    const ack = await send('status', undefined, 'rest');
    $('#rest-result').textContent = 'PASS \u00b7 GET state + POST status \u00b7 ' + ack.command_id;
  } catch (error) { $('#rest-result').textContent = 'Failed: ' + error.message; fail(error); }
};
$('#test-ws').onclick = async () => {
  try {
    $('#ws-result').textContent = 'Testing\u2026';
    const ack = await send('status', undefined, 'ws');
    $('#ws-result').textContent = 'PASS \u00b7 WSS status + ack \u00b7 ' + ack.command_id;
  } catch (error) { $('#ws-result').textContent = 'Failed: ' + error.message; fail(error); }
};
$('#test-mqtt').onclick = async () => {
  try {
    const ack = await send('status');
    $('#mqtt-result').textContent = 'PASS \u00b7 MQTT status + ack \u00b7 ' + ack.command_id;
  } catch (error) { $('#mqtt-result').textContent = 'Failed: ' + error.message; fail(error); }
};
$('#public-key').onclick = async () => {
  try { $('#key-json').textContent = JSON.stringify(await request('/api/public-key'), null, 2); $('#key-details').open = true; } catch (error) { fail(error); }
};
async function start() {
  const info = await request('/api/info');
  signedIn = true; mode = info.mode; state = info.state; availability = info.availability; transport = info.transport;
  $('#login-panel').hidden = true; $('#remote').hidden = false;
  document.title = 'TV remote \u00b7 ' + mode.toUpperCase();
  $('#subtitle').textContent = info.device + ' \u00b7 ' + (mode === 'api' ? 'HTTPS control with live WebSocket state' : 'MQTT control with live state');
  $('#api-tools').hidden = mode !== 'api'; $('#mqtt-tools').hidden = mode !== 'mqtt';
  $('#via-label').hidden = mode !== 'api';
  $('#via').value = mode === 'api' ? 'rest' : 'mqtt';
  render(); connectLive();
  if (!state && mode === 'api') { try { state = (await request('/api/state')).state; render(); } catch (error) { fail(error); } }
}
async function login(token) {
  await request('/api/login', { method: 'POST', headers: { 'X-Remote-Token': token } }); await start();
}
$('#login-form').onsubmit = event => { event.preventDefault(); login($('#token').value).then(() => { $('#token').value = ''; }).catch(error => { $('#login-error').textContent = error.message; }); };
const bootstrap = new URLSearchParams(location.hash.slice(1)).get('token');
if (bootstrap) { history.replaceState(null, '', location.pathname); login(bootstrap).catch(error => { $('#login-error').textContent = error.message; }); }
else start().catch(() => { $('#login-panel').hidden = false; });
setInterval(render, 1000);
