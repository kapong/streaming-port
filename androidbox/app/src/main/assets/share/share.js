'use strict';

// One way only (WHIP, RFC 9725): this page sends a screen or a camera to the TV and receives nothing back.

const $ = (id) => document.getElementById(id);
let config = { name: 'the TV', maxWidth: 1920, maxHeight: 1080, fps: 30, free: 1, max: 4 };
let pc = null;
let stream = null;
let source = null; // 'screen' | 'camera'
let facing = 'user';
let resource = null;
let statsTimer = null;
let lastBytes = 0;
let lastAt = 0;
let live = false;
let fastStart = null;

function say(text) { $('message').textContent = text || ''; }

function status(text, kind) {
  const s = $('status');
  s.textContent = text;
  s.className = 'status ' + (kind || '');
}

/** The three-part bar under the status while connecting: 1 offer sent, 2 answer back, 3 first picture. 0 hides it. */
function progress(step) {
  $('steps').hidden = step === 0;
  [...$('steps').children].forEach((bar, i) => {
    bar.className = i + 1 < step ? 'done' : i + 1 === step ? 'now' : '';
  });
}

async function loadConfig() {
  try {
    const r = await fetch('/config', { cache: 'no-store' });
    if (r.ok) config = await r.json();
  } catch (e) { /* keep defaults */ }
  $('tv').textContent = config.name;
  $('rebroadcast-notice').hidden = !config.rebroadcast;
  const places = $('places');
  places.hidden = false;
  places.textContent = config.free > 0 ? config.free + ' of ' + config.max + ' places free' : 'TV full';
  places.classList.toggle('full', config.free <= 0);
  $('footer').textContent = 'StreamPort on ' + config.name + ' \u00b7 ' + location.hostname;
  document.title = 'Share to ' + config.name;
}

const canScreen = () => !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia);
const canCamera = () => !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
const isPhone = () => /Android|iPhone|iPad/.test(navigator.userAgent);

/** Shows only the buttons this browser can use, and says why when it can use none. */
function checkSupport() {
  if (!window.isSecureContext || !window.RTCPeerConnection) {
    say(window.isSecureContext ? 'This browser can’t send video to the TV.' : 'This page must be opened over https.');
    $('share').disabled = true;
    $('camera').disabled = true;
    return;
  }
  $('share').hidden = !canScreen();
  $('camera').hidden = !canCamera();
  $('choices').classList.toggle('one', !canScreen() || !canCamera());
  if (!canScreen() && /Android/.test(navigator.userAgent)) {
    $('setup-hint').hidden = false;
    $('setup-hint').textContent = 'Android browsers can’t share the screen; the camera works. To show your '
      + 'phone’s screen, use Cast from the quick settings.';
  }
  if (!canScreen() && !canCamera()) say('This browser can’t share a screen or a camera.');
}

/** H.264 decodes in hardware on the TV; put it first where the browser allows choosing. */
function preferCodecs(transceiver) {
  if (!transceiver.setCodecPreferences || !window.RTCRtpSender || !RTCRtpSender.getCapabilities) return;
  const caps = RTCRtpSender.getCapabilities('video');
  if (!caps) return;
  const rank = (c) => {
    const m = c.mimeType.toLowerCase();
    if (m === 'video/h264') return 0;
    if (m === 'video/vp9') return 1;
    if (m === 'video/av1') return 2;
    if (m === 'video/vp8') return 3;
    return 4; // rtx, red, ulpfec
  };
  try { transceiver.setCodecPreferences([...caps.codecs].sort((a, b) => rank(a) - rank(b))); } catch (e) { /* keep default */ }
}

function waitForCandidates(peer) {
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => { if (peer.iceGatheringState === 'complete') { peer.removeEventListener('icegatheringstatechange', done); resolve(); } };
    peer.addEventListener('icegatheringstatechange', done);
    setTimeout(resolve, 2500); // the TV reaches us anyway once it has our host candidates
  });
}

/** Camera at up to the TV's limit (1080p is plenty for a camera), front or back on phones. */
function cameraConstraints() {
  return {
    width: { ideal: Math.min(1920, config.maxWidth), max: config.maxWidth },
    height: { ideal: Math.min(1080, config.maxHeight), max: config.maxHeight },
    frameRate: { ideal: config.fps, max: config.fps },
    facingMode: isPhone() ? facing : undefined,
  };
}

async function capture(kind) {
  if (kind === 'screen') {
    return navigator.mediaDevices.getDisplayMedia({
      video: { width: { max: config.maxWidth }, height: { max: config.maxHeight }, frameRate: { ideal: config.fps, max: config.fps } },
      audio: true,
    });
  }
  const mic = $('mic').checked;
  return navigator.mediaDevices.getUserMedia({
    video: cameraConstraints(),
    audio: mic ? { echoCancellation: true, noiseSuppression: true } : false,
  });
}

async function start(kind) {
  say('');
  const name = $('name').value.trim();
  try { localStorage.setItem('streamport-name', name); } catch (e) { /* private mode */ }
  $('share').disabled = true;
  $('camera').disabled = true;
  try {
    stream = await capture(kind);
  } catch (e) {
    $('share').disabled = false;
    $('camera').disabled = false;
    if (e.name === 'NotAllowedError') {
      if (kind === 'camera') say('The camera wasn’t allowed. Allow it in the browser’s site settings and try again.');
    } else {
      say('Couldn’t start: ' + e.message);
    }
    return;
  }
  source = kind;
  const [video] = stream.getVideoTracks();
  // Screens: keep text sharp. Cameras: keep motion smooth.
  if ('contentHint' in video) video.contentHint = kind === 'screen' ? 'detail' : 'motion';
  video.addEventListener('ended', stop); // the browser's own "Stop sharing", or the camera going away

  $('setup').hidden = true;
  $('live').hidden = false;
  $('preview').srcObject = stream;
  $('preview').classList.toggle('mirror', kind === 'camera' && facing === 'user');
  $('stage').classList.toggle('portrait', kind === 'camera' && isPhone());
  $('onair').hidden = true;
  $('flip').hidden = !(kind === 'camera' && isPhone());
  live = false;
  status('Connecting to ' + config.name, 'wait');
  progress(1);

  pc = new RTCPeerConnection({ bundlePolicy: 'max-bundle' });
  for (const track of stream.getTracks()) {
    const t = pc.addTransceiver(track, { direction: 'sendonly', streams: [stream] });
    if (track.kind === 'video') preferCodecs(t);
  }
  pc.addEventListener('connectionstatechange', () => {
    const s = pc && pc.connectionState;
    if (s === 'connected') status(live ? 'On the TV now' : 'Sending the first picture', live ? 'ok' : 'wait');
    else if (s === 'disconnected') status('Connection unstable', 'wait');
    else if (s === 'failed') { status('Connection lost', 'bad'); stop(); }
  });

  try {
    await pc.setLocalDescription(await pc.createOffer());
    await waitForCandidates(pc);
    const url = '/whip?source=' + kind + '&name=' + encodeURIComponent(name);
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/sdp' }, body: pc.localDescription.sdp });
    progress(2);
    if (r.status === 503) {
      const why = await r.text();
      throw new Error(/already shares/.test(why)
        ? why + ' on the TV. Stop one of them first.'
        : 'All ' + config.max + ' places on the TV are taken. Try again when someone stops.');
    }
    if (r.status === 429) throw new Error('Too many attempts. Wait a minute and try again.');
    if (r.status !== 201) throw new Error('The TV refused the connection (' + r.status + '): ' + (await r.text()));
    resource = r.headers.get('Location');
    await pc.setRemoteDescription({ type: 'answer', sdp: await r.text() });
    status('Sending the first picture', 'wait');
    progress(3);
    if (kind === 'screen') {
      // A fast first picture: for a few seconds the browser may lower the resolution while the bitrate ramps up;
      // then keep the resolution (sharp text) and let the frame rate give instead.
      await tuneSender('balanced');
      fastStart = setTimeout(() => tuneSender('maintain-resolution'), 6000);
    } else {
      await tuneSender('maintain-framerate');
    }
    statsTimer = setInterval(showStats, 500);
  } catch (e) {
    say(e.message);
    stop();
  }
}

/** Front and back camera on phones, without reconnecting: the new camera replaces the old in the same stream. */
async function flipCamera() {
  if (!pc || source !== 'camera') return;
  facing = facing === 'user' ? 'environment' : 'user';
  try {
    const next = await navigator.mediaDevices.getUserMedia({ video: cameraConstraints(), audio: false });
    const track = next.getVideoTracks()[0];
    track.contentHint = 'motion';
    const sender = pc.getSenders().find((s) => s.track && s.track.kind === 'video');
    const old = sender.track;
    await sender.replaceTrack(track);
    old.removeEventListener('ended', stop);
    old.stop();
    stream.removeTrack(old);
    stream.addTrack(track);
    track.addEventListener('ended', stop);
    $('preview').srcObject = stream;
    $('preview').classList.toggle('mirror', facing === 'user');
  } catch (e) {
    facing = facing === 'user' ? 'environment' : 'user';
    say('Couldn’t switch camera: ' + e.message);
  }
}

/** Bitrate for the TV's resolution; how to give way when the network is tight depends on what is shared. */
async function tuneSender(preference) {
  if (!pc) return;
  const sender = pc.getSenders().find((s) => s.track && s.track.kind === 'video');
  if (!sender) return;
  const p = sender.getParameters();
  if (!p.encodings || !p.encodings.length) p.encodings = [{}];
  const pixels = config.maxWidth * config.maxHeight;
  p.encodings[0].maxBitrate = Math.round(Math.min(25e6, 8e6 * pixels / (1920 * 1080)));
  p.encodings[0].maxFramerate = config.fps;
  p.degradationPreference = preference;
  try { await sender.setParameters(p); } catch (e) { /* not every browser allows every field */ }
}

async function showStats() {
  if (!pc) return;
  const report = await pc.getStats();
  report.forEach((s) => {
    if (s.type !== 'outbound-rtp' || s.kind !== 'video') return;
    const now = performance.now();
    const mbps = lastAt ? ((s.bytesSent - lastBytes) * 8 / ((now - lastAt) / 1000)) / 1e6 : 0;
    lastBytes = s.bytesSent;
    lastAt = now;
    if (!live && s.framesSent > 0 && pc.connectionState === 'connected') {
      live = true;
      status('On the TV now', 'ok');
      progress(0);
      $('onair').hidden = false;
      loadConfig(); // one place fewer now
      clearInterval(statsTimer);
      statsTimer = setInterval(showStats, 2000);
    }
    const size = s.frameWidth ? s.frameWidth + '×' + s.frameHeight : '';
    $('detail').textContent = [size, s.framesPerSecond ? Math.round(s.framesPerSecond) + ' fps' : '', mbps ? mbps.toFixed(1) + ' Mb/s' : '']
      .filter(Boolean).join(' · ');
  });
}

function stop() {
  clearInterval(statsTimer);
  statsTimer = null;
  clearTimeout(fastStart);
  live = false;
  if (resource) {
    fetch(resource, { method: 'DELETE', keepalive: true }).catch(() => {});
    resource = null;
  }
  if (pc) { pc.close(); pc = null; }
  if (stream) { stream.getTracks().forEach((t) => t.stop()); stream = null; }
  source = null;
  $('preview').srcObject = null;
  progress(0);
  loadConfig();
  $('live').hidden = true;
  $('setup').hidden = false;
  $('share').disabled = false;
  $('camera').disabled = false;
  $('detail').textContent = '';
  lastBytes = 0;
  lastAt = 0;
}

window.addEventListener('pagehide', () => { if (resource) fetch(resource, { method: 'DELETE', keepalive: true }); });
$('share').addEventListener('click', () => start('screen'));
$('camera').addEventListener('click', () => start('camera'));
$('flip').addEventListener('click', flipCamera);
$('stop').addEventListener('click', stop);

(async () => {
  try { $('name').value = localStorage.getItem('streamport-name') || ''; } catch (e) { /* private mode */ }
  await loadConfig();
  checkSupport();
  // Places come and go: keep the count fresh while choosing.
  setInterval(() => { if (!pc && !document.hidden) loadConfig(); }, 10000);
})();
