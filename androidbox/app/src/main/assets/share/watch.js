'use strict';

// Watching the TV on the share page (WHEP): receive only, nothing is sent. Nothing connects, and the TV's encoder
// stays off, until "Watch TV" is tapped. share.js calls watch.stop() before sharing: the TV would show itself.

const watch = (() => {
  const $ = (id) => document.getElementById(id);
  let pc = null;
  let resource = null;
  let retryTimer = null;
  let pollTimer = null;
  let backoff = 2000;
  let active = false; // the user asked to watch
  let enabled = true; // the TV allows watching
  let state = 'connecting'; // connecting | watching | busy | off | error

  function show(next, text, detail) {
    state = next;
    const s = $('w-status');
    s.textContent = text;
    s.className = 'status ' + ({ watching: 'ok', connecting: 'wait', busy: 'wait', off: '' }[next] ?? 'bad');
    $('w-detail').textContent = detail || '';
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
    $('w-video').srcObject = null;
  }

  /** Tries again later, a little later each time (2 s up to 30 s), unless watching is off or stopped. */
  function retry(after) {
    hangUp();
    clearTimeout(retryTimer);
    if (!enabled || !active) return;
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
    if (!active) return;
    if (!window.RTCPeerConnection) { show('error', 'This browser can’t play the TV', 'Try a current Chrome, Edge, Firefox or Safari.'); return; }
    if (state !== 'busy') show('connecting', 'Connecting');
    const peer = new RTCPeerConnection();
    pc = peer;
    peer.addTransceiver('video', { direction: 'recvonly' });
    peer.ontrack = (e) => { $('w-video').srcObject = e.streams[0] || new MediaStream([e.track]); };
    peer.onconnectionstatechange = () => {
      if (peer !== pc) return;
      if (peer.connectionState === 'connected') {
        backoff = 2000;
        show('watching', 'Watching');
        $('w-video').play().catch(() => {});
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
    show('off', 'Watching is turned off on this TV', 'It starts by itself when it is turned back on.');
  }

  /** "N watching" and the on/off switch, every 4 s while watching. */
  async function poll() {
    try {
      const r = await fetch('/watch/status', { cache: 'no-store' });
      if (!r.ok || !active) return;
      const s = await r.json();
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

  function start() {
    if (active) return;
    active = true;
    enabled = true;
    backoff = 2000;
    state = 'connecting';
    $('watching').hidden = false;
    $('watch-card').hidden = true;
    document.querySelector('main').classList.add('wide');
    connect();
    poll();
    pollTimer = setInterval(() => { if (!document.hidden) poll(); }, 4000);
  }

  function stop() {
    if (!active) return;
    active = false;
    clearTimeout(retryTimer);
    clearInterval(pollTimer);
    hangUp();
    $('watching').hidden = true;
    $('viewers').hidden = true;
    document.querySelector('main').classList.remove('wide');
  }

  $('watch').addEventListener('click', start);
  $('w-stop').addEventListener('click', () => { stop(); $('watch-card').hidden = false; });
  $('w-full').addEventListener('click', () => {
    const stage = $('w-stage');
    const video = $('w-video');
    if (document.fullscreenElement) document.exitFullscreen();
    else if (stage.requestFullscreen) stage.requestFullscreen().catch(() => {});
    else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen(); // iPhone: only the video can go full screen
  });
  window.addEventListener('pagehide', hangUp);
  window.addEventListener('pageshow', (e) => { if (e.persisted && active && enabled) connect(); }); // back from the bfcache

  return { stop, get active() { return active; } };
})();
