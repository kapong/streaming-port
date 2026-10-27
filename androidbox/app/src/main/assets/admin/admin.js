'use strict';

const $ = (id) => document.getElementById(id);
let state = null;
/** Most screens allowed at each resolution (the box's memory and decoder budget). */
let caps = { '720p': 8, '1080p': 8, '1440p': 4, '4k': 4 };

function fillStreamOptions(selected) {
  const ms = $('max-streams');
  const max = caps[$('resolution').value] || 8;
  const want = Math.min(Number(selected || ms.value || 4), max);
  if (ms.options.length !== max) {
    ms.replaceChildren();
    for (let i = 1; i <= max; i++) ms.append(new Option(String(i), String(i)));
  }
  ms.value = String(want);
}
let timer = null;

async function api(path, body) {
  const res = await fetch('/api/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'X-Admin': '1', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin',
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== 'login') { showLogin(); throw new Error('log in first'); }
  if (!res.ok) throw new Error(data.error || ('error ' + res.status));
  return data;
}

function say(el, text, isError) {
  el.textContent = text || '';
  el.classList.toggle('err', !!isError);
}

function showLogin() {
  clearInterval(timer);
  $('panel').hidden = true;
  $('logout').hidden = true;
  $('login').hidden = false;
}

function showPanel() {
  $('login').hidden = true;
  $('panel').hidden = false;
  $('logout').hidden = false;
  clearInterval(timer);
  timer = setInterval(refresh, 2000);
}

async function refresh() {
  try {
    render(await api('state'));
  } catch (e) { /* login screen already shown on 401 */ }
}

function render(s) {
  state = s;
  if (document.activeElement !== $('name')) $('name').value = s.name;
  $('box-name-hint').textContent = s.canRenameBox
    ? 'Chromecast and Miracast use the same name (now \u201C' + s.boxName + '\u201D).'
    : 'Chromecast and Miracast are still \u201C' + s.boxName + '\u201D: renaming them needs a one-time adb grant (see the README).';
  $('miracast-card').hidden = !s.miracast;
  if (s.rebroadcast) {
    if (document.activeElement !== $('rb-on')) $('rb-on').checked = s.rebroadcast.enabled;
    $('rb-url').textContent = s.rebroadcast.url;
    $('rb-status').textContent = !s.rebroadcast.enabled ? 'Off.'
      : !s.rebroadcast.live ? 'On: starting.'
      : s.rebroadcast.mirroring ? '\u25CF Live now: sending the TV.'
      : '\u25CF Live now: sending the welcome screen until someone shares.';
  }
  for (const b of document.querySelectorAll('.seg button')) b.classList.toggle('on', b.dataset.mode === s.layout);
  $('layout-hint').textContent = {
    dynamic: 'The primary screen is the biggest; the rest fit around it to use the most of the TV.',
    grid: 'Every screen gets an equal cell.',
    focus: 'Only the primary screen, as big as it fits. Pick another below, or with the arrows on the remote.',
  }[s.layout] || '';
  $('count').textContent = s.senders.length + ' of ' + s.max;
  caps = s.maxStreamsFor || caps;
  const editing = $('limits-form').contains(document.activeElement);
  if (!editing) {
    $('resolution').value = s.resolution;
    fillStreamOptions(s.maxStreams);
  }
  $('empty').hidden = s.senders.length > 0;
  const list = $('senders');
  // Rows are kept and updated in place (keyed by sender id), so a click is never lost to a refresh.
  const seen = new Set();
  for (const p of s.senders) {
    seen.add(String(p.id));
    let li = list.querySelector('li[data-id="' + p.id + '"]');
    if (!li) {
      li = senderRow(p);
      list.append(li);
    }
    updateRow(li, p, s);
  }
  for (const li of [...list.children]) {
    if (!seen.has(li.dataset.id)) li.remove();
  }
}

function senderRow(p) {
  const li = document.createElement('li');
  li.dataset.id = String(p.id);
  const who = document.createElement('div');
  who.className = 'who';
  const name = document.createElement('b');
  const meta = document.createElement('small');
  const tag = document.createElement('div');
  tag.className = 'primary';
  who.append(name, meta, tag);
  const q = document.createElement('span');
  const actions = document.createElement('div');
  actions.className = 'actions';
  const prim = document.createElement('button');
  prim.className = 'ghost';
  prim.textContent = 'Make primary';
  prim.onclick = () => act(() => api('primary', { id: p.id }),
    () => '\u201C' + li.dataset.name + '\u201D is now the primary screen on the TV.');
  const kick = document.createElement('button');
  kick.className = 'danger';
  kick.textContent = 'Kick';
  kick.onclick = () => {
    if (confirm('Disconnect ' + li.dataset.name + '?')) {
      act(() => api('kick', { id: p.id }), () => '\u201C' + li.dataset.name + '\u201D was disconnected.');
    }
  };
  actions.append(prim, kick);
  li.append(who, q, actions);
  return li;
}

function updateRow(li, p, s) {
  li.dataset.name = p.name;
  const [who, q, actions] = li.children;
  const [name, meta, tag] = who.children;
  name.textContent = p.name;
  meta.textContent = [p.kind, p.model, p.address, p.width ? p.width + '×' + p.height : ''].filter(Boolean).join(' · ');
  const primary = p.id === s.primary;
  tag.hidden = !primary;
  tag.textContent = s.layout === 'dynamic' ? 'Primary · you hear this one'
    : s.layout === 'focus' ? 'On the TV · you hear this one' : 'Focused · you hear this one';
  q.className = 'q ' + p.quality;
  q.textContent = p.quality === 'idle'
    ? 'idle'
    : p.quality + ' · ' + p.mbps.toFixed(1) + ' Mb/s · ' + Math.round(p.fps) + ' fps · ' + p.jitterMs + ' ms jitter';
  actions.children[0].disabled = primary;
}

/** Runs an API call; on success shows what was saved and how it applies, on failure why not. */
async function act(fn, done) {
  try {
    const s = await fn();
    render(s);
    say($('msg'), '');
    if (done) toast(done(s));
  } catch (e) {
    say($('msg'), e.message, true);
    toast('Not saved: ' + e.message, true);
  }
}

let toastTimer = null;

function toast(text, isError) {
  const t = $('toast');
  t.textContent = (isError ? '' : '\u2713 ') + text;
  t.classList.toggle('err', !!isError);
  t.hidden = false;
  t.classList.remove('show');
  void t.offsetWidth; // restart the fade-in
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), isError ? 6000 : 4000);
}

$('ask-pin').onclick = async () => {
  try {
    const r = await api('pin', {});
    if (r.error) say($('login-msg'), r.error, true);
    else say($('login-msg'), 'Look at the TV. The PIN is valid for ' + r.expiresIn + ' s.');
    $('pin').focus();
  } catch (e) { say($('login-msg'), e.message, true); }
};

$('pin-form').onsubmit = async (ev) => {
  ev.preventDefault();
  try {
    await api('login', { pin: $('pin').value });
    $('pin').value = '';
    say($('login-msg'), '');
    showPanel();
    refresh();
  } catch (e) { say($('login-msg'), e.message, true); }
};

$('resolution').onchange = () => fillStreamOptions();

$('rb-on').onchange = () => {
  const on = $('rb-on').checked;
  act(() => api('rebroadcast', { enabled: on }), () => on
    ? 'Rebroadcast on: the welcome screen now, the TV whenever someone shares. Watch in VLC at ' + $('rb-url').textContent + '.'
    : 'Rebroadcast off.');
};

$('open-miracast').onclick = () => {
  if (!confirm('Switch the TV to Miracast?\n\nEveryone on AirPlay is disconnected, and the TV leaves the Wi-Fi '
      + 'while Miracast is open: AirPlay, Chromecast and this page stop working until someone presses BACK on the '
      + 'remote. You can\u2019t switch it back from here.\n\nIf the TV doesn\u2019t come back on the network '
      + 'afterwards, reconnect its Wi-Fi in Settings > Network.')) return;
  act(() => api('miracast', {}), () => 'The TV is switching to Miracast and leaving the Wi-Fi. On the phone or laptop, '
      + 'choose \u201C' + (state ? state.boxName : 'the TV') + '\u201D under Cast / Screen mirroring / Connect. '
      + 'This page stops responding until someone presses BACK on the remote.');
};

$('limits-form').onsubmit = (ev) => {
  ev.preventDefault();
  act(() => api('settings', { maxStreams: Number($('max-streams').value), resolution: $('resolution').value }),
    (s) => 'Saved: up to ' + s.maxStreams + ' screens at ' + s.resolution.toUpperCase() + '. Applies to senders who connect next; nobody was disconnected.');
  document.activeElement.blur();
};

$('name-form').onsubmit = (ev) => {
  ev.preventDefault();
  act(() => api('name', { name: $('name').value }),
    (s) => 'Saved. iPhones, iPads and Macs now see \u201C' + s.name + '\u201D'
      + (s.canRenameBox ? ', and so does Chromecast (Miracast after its next start).' : '.')
      + ' Lists update within a few seconds.');
  $('name').blur();
};

for (const b of document.querySelectorAll('.seg button')) {
  b.onclick = () => act(() => api('layout', { mode: b.dataset.mode }),
    (s) => ({ grid: 'Grid', focus: 'Focus', dynamic: 'Dynamic' }[s.layout] || 'The') + ' layout is on the TV now.');
}

$('logout').onclick = async () => { await api('logout', {}).catch(() => {}); showLogin(); };
$('logout-all').onclick = async () => {
  if (confirm('Log out every browser, including this one?')) { await api('logout-all', {}).catch(() => {}); showLogin(); }
};

api('state').then((s) => { showPanel(); render(s); }).catch(() => showLogin());
