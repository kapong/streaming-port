'use strict';

// Receive only (WHEP): this page shows the TV and sends nothing. Plain http on purpose: receiving WebRTC needs no
// secure context, so there is no certificate warning here. Sharing needs https, on the share page.

const $ = (id) => document.getElementById(id);
let pc = null;
let resource = null;
let retryTimer = null;
let backoff = 2000;
let enabled = true;
let state = 'connecting'; // connecting | watching | busy | off | error

function show(next, text, detail) {
  state = next;
  const s = $('status');
  s.textContent = text;
  s.className = 'status ' + ({ watching: 'ok', connecting: 'wait', busy: 'wait', off: '' }[next] ?? 'bad');
  $('detail').textContent = detail || '';
}

function waitForCandidates(peer) {
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => { if (peer.iceGatheringState === 'complete') { peer.removeEventListener('icegatheringstatechange', done); resolve(); } };
    peer.addEventListener('icegatheringstatechange', done);
    setTimeout(resolve, 2500); // the TV reaches us anyway once it has our host candidates
  });
}

/** Ends this viewer: the PeerConnection, and the TV's side when it still has one. */
function hangUp() {
  if (resource) fetch(resource, { method: 'DELETE', keepalive: true }).catch(() => {});
  resource = null;
  if (pc) { pc.onconnectionstatechange = null; pc.ontrack = null; pc.close(); }
  pc = null;
  $('video').srcObject = null;
}

/** Tries again later, a little later each time (2 s up to 30 s), unless watching is off. */
function retry(after) {
  hangUp();
  clearTimeout(retryTimer);
  if (!enabled) return;
  const wait = Math.min(after || backoff, 30000);
  backoff = Math.min(backoff * 2, 30000);
  retryTimer = setTimeout(connect, wait);
}

function lost() {
  show('error', 'Lost the TV', 'Trying again…');
  retry();
}

async function connect() {
  clearTimeout(retryTimer);
  hangUp();
  if (!window.RTCPeerConnection) { show('error', 'This browser can’t play the TV', 'Try a current Chrome, Edge, Firefox or Safari.'); return; }
  if (state !== 'busy') show('connecting', 'Connecting');
  const peer = new RTCPeerConnection();
  pc = peer;
  peer.addTransceiver('video', { direction: 'recvonly' });
  peer.ontrack = (e) => { $('video').srcObject = e.streams[0] || new MediaStream([e.track]); };
  peer.onconnectionstatechange = () => {
    if (peer !== pc) return;
    if (peer.connectionState === 'connected') {
      backoff = 2000;
      show('watching', 'Watching');
      $('video').play().catch(() => {});
    } else if (peer.connectionState === 'failed' || peer.connectionState === 'closed') {
      lost();
    } else if (peer.connectionState === 'disconnected') {
      // Often only a moment of Wi-Fi trouble; still gone after 5 s, start over.
      setTimeout(() => { if (peer === pc && peer.connectionState === 'disconnected') lost(); }, 5000);
    }
  };
  try {
    await peer.setLocalDescription(await peer.createOffer());
    await waitForCandidates(peer);
    if (peer !== pc) return;
    const r = await fetch('/whep', { method: 'POST', headers: { 'Content-Type': 'application/sdp' }, body: peer.localDescription.sdp });
    if (peer !== pc) return;
    const text = await r.text();
    if (r.status === 201) {
      resource = r.headers.get('Location');
      await peer.setRemoteDescription({ type: 'answer', sdp: text });
      return;
    }
    if (r.status === 403) {
      enabled = false;
      hangUp();
      showOff();
      return;
    }
    if (r.status === 503) {
      show('busy', 'TV busy', text + '. Trying again by itself.');
      retry(1000 * Number(r.headers.get('Retry-After') || 0) || undefined);
      return;
    }
    show('error', 'Couldn’t watch', text || 'Error ' + r.status);
  } catch (e) {
    if (peer !== pc) return;
    show('error', 'Couldn’t reach the TV', 'Trying again…');
  }
  retry();
}

function showOff() {
  show('off', 'Watching is turned off on this TV', 'The page starts by itself when it is turned back on.');
}

/** "N watching" and the on/off switch, every 4 s. */
async function poll() {
  try {
    const r = await fetch('/watch/status', { cache: 'no-store' });
    if (!r.ok) return;
    const s = await r.json();
    $('tv').textContent = s.name || 'the TV';
    document.title = 'Watch ' + (s.name || 'the TV');
    $('footer').textContent = 'StreamPort on ' + (s.name || 'the TV') + ' · ' + location.hostname;
    $('share').href = 'https://' + location.hostname + ':' + s.sharePort + '/';
    $('share').hidden = false;
    const v = $('viewers');
    v.hidden = !s.enabled;
    v.textContent = s.viewers + ' watching';
    v.classList.toggle('full', s.viewers >= s.max);
    if (!s.enabled && enabled) {
      enabled = false;
      clearTimeout(retryTimer);
      hangUp();
      showOff();
    } else if (s.enabled && !enabled) {
      enabled = true;
      backoff = 2000;
      connect();
    }
  } catch (e) { /* the TV may be restarting; the next poll tells */ }
}

$('full').onclick = () => {
  const stage = $('stage');
  const video = $('video');
  if (document.fullscreenElement) document.exitFullscreen();
  else if (stage.requestFullscreen) stage.requestFullscreen().catch(() => {});
  else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen(); // iPhone: only the video can go full screen
};

window.addEventListener('pagehide', hangUp);
window.addEventListener('pageshow', (e) => { if (e.persisted && enabled) connect(); }); // back from the bfcache
poll();
setInterval(() => { if (!document.hidden) poll(); }, 4000);
connect();
