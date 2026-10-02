'use strict';

const STORAGE_KEY = 'broadcastify-scanner-feeds-v2';
const LEGACY_STORAGE_KEY = 'broadcastify-scanner-feeds-v1';

/** Tiny silent WAV — used to unlock HTMLMediaElement under a user gesture. */
const SILENT_WAV =
  'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAESsAACJWAAACABAAZGF0YQAAAAA=';

const CALLS_SYSTEM = '11341';

function callsUrl(tg) {
  return `https://www.broadcastify.com/calls/tg/${CALLS_SYSTEM}/${tg}`;
}

function callsLink(tg, label) {
  return { talkgroup: String(tg), label, url: callsUrl(tg) };
}

/**
 * Calls entries are deep links, not listen feed IDs.
 * Talkgroups 30433–30438 must never be sent to the HLS scraper.
 */
const DEFAULT_FEEDS = [
  {
    kind: 'calls',
    feedId: 'calls-11341-30433',
    name: 'NSRS Washoe TMFPD Red Dispatch',
    region: 'nevada',
    callsLinks: [callsLink('30433', 'Red Dispatch')],
  },
  {
    kind: 'calls',
    feedId: 'calls-11341-command',
    name: 'TMFPD Command 1 + Command 2',
    region: 'nevada',
    callsLinks: [callsLink('30434', 'Command 1'), callsLink('30435', 'Command 2')],
  },
  {
    kind: 'calls',
    feedId: 'calls-11341-tac',
    name: 'TMFPD Tac 4–6',
    region: 'nevada',
    callsLinks: [
      callsLink('30436', 'Tac 4'),
      callsLink('30437', 'Tac 5'),
      callsLink('30438', 'Tac 6'),
    ],
  },
  {
    kind: 'listen',
    feedId: '7364',
    name: 'Reno and Sparks Police and Fire',
    region: 'nevada',
  },
  {
    kind: 'listen',
    feedId: '14826',
    name: 'East Placer and Nevada Counties CAL FIRE NEU - Kings Beach Area',
    region: 'california',
  },
  { kind: 'listen', feedId: '47365', name: 'CAL FIRE NEU West', region: 'california' },
  { kind: 'listen', feedId: '47367', name: 'Tahoe National Forest West', region: 'california' },
];

const SECTION_TITLES = {
  nevada: 'Nevada / Washoe',
  california: 'California / NEU–TNF',
};

/** @type {Map<string, FeedPlayer|CallsCard>} */
const players = new Map();

/** @type {Map<string, HTMLElement>} */
const sectionNodes = new Map();

/** @type {AudioContext|null} */
let sharedAudioCtx = null;

const SPECTRUM_BARS = 19;

function ensureSharedAudioCtx() {
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return null;
  if (!sharedAudioCtx) sharedAudioCtx = new AC();
  if (sharedAudioCtx.state === 'suspended') {
    sharedAudioCtx.resume().catch(() => {});
  }
  return sharedAudioCtx;
}

const els = {
  grid: document.getElementById('feed-grid'),
  tpl: document.getElementById('feed-card-tpl'),
  callsTpl: document.getElementById('calls-card-tpl'),
  playAll: document.getElementById('btn-play-all'),
  stopAll: document.getElementById('btn-stop-all'),
  masterVol: document.getElementById('master-volume'),
  settingsBtn: document.getElementById('btn-settings'),
  settingsPanel: document.getElementById('settings-panel'),
  addForm: document.getElementById('add-feed-form'),
  newId: document.getElementById('new-feed-id'),
  newName: document.getElementById('new-feed-name'),
};

function readStored(key) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed) && parsed.length) return parsed;
  } catch (_) {}
  return null;
}

function parseCallsUrl(url) {
  try {
    const u = new URL(url);
    if (u.protocol !== 'https:') return null;
    const host = u.hostname.toLowerCase().replace(/\.$/, '');
    if (host !== 'broadcastify.com' && !host.endsWith('.broadcastify.com')) return null;
    if (u.username || u.password) return null;
    if (u.port && u.port !== '443') return null;
    if (u.search || u.hash) return null;
    const match = u.pathname.match(/^\/calls\/tg\/11341\/(\d+)$/);
    if (!match) return null;
    return { talkgroup: match[1], url: `${u.origin}${u.pathname}` };
  } catch (_) {
    return null;
  }
}

function cloneFeed(feed) {
  const links = (feed.callsLinks || [])
    .map((link) => {
      const parsed = parseCallsUrl(link.url);
      if (!parsed) return null;
      return {
        talkgroup: parsed.talkgroup,
        label: link.label || `TG ${parsed.talkgroup}`,
        url: parsed.url,
      };
    })
    .filter(Boolean);
  return {
    kind: feed.kind === 'calls' ? 'calls' : 'listen',
    feedId: String(feed.feedId),
    name: feed.name,
    region: feed.region || 'other',
    callsLinks: links,
  };
}

function knownListen(feedId) {
  return DEFAULT_FEEDS.find((feed) => feed.kind === 'listen' && feed.feedId === feedId) || null;
}

function normalizeListen(feed) {
  const id = String(feed.feedId || '').trim();
  if (!/^\d+$/.test(id)) return null;
  const known = knownListen(id);
  let region = feed.region;
  if (region !== 'nevada' && region !== 'california' && region !== 'other') {
    region = known ? known.region : 'other';
  } else if ((!feed.region || feed.region === 'other') && known) {
    region = known.region;
  }
  return {
    kind: 'listen',
    feedId: id,
    name: feed.name || (known && known.name) || `Feed ${id}`,
    region,
    callsLinks: [],
  };
}

function normalizeCalls(feed) {
  const copy = cloneFeed({ ...feed, kind: 'calls' });
  if (!copy.callsLinks.length) return null;
  if (/^\d+$/.test(copy.feedId)) return null;
  if (copy.region !== 'nevada' && copy.region !== 'california') copy.region = 'nevada';
  copy.name = copy.name || 'Calls';
  return copy;
}

function normalizeStored(feed) {
  if (!feed || typeof feed !== 'object') return null;
  if (feed.kind === 'calls') return normalizeCalls(feed);
  return normalizeListen(feed);
}

function upgradeLegacy(existing) {
  const have = new Set(existing.map((feed) => feed.feedId));
  const newcomers = DEFAULT_FEEDS.filter(
    (feed) => feed.region === 'nevada' && !have.has(feed.feedId),
  ).map(cloneFeed);
  return newcomers.concat(existing);
}

function loadFeeds() {
  const current = readStored(STORAGE_KEY);
  if (current) {
    const parsed = current.map(normalizeStored).filter(Boolean);
    if (parsed.length) return parsed;
  }
  const legacy = readStored(LEGACY_STORAGE_KEY);
  if (legacy) {
    const old = legacy.map((feed) => normalizeListen(feed || {})).filter(Boolean);
    if (old.length) return upgradeLegacy(old);
  }
  return DEFAULT_FEEDS.map(cloneFeed);
}

function saveFeeds() {
  const list = [...players.values()].map((card) => {
    if (card.kind === 'calls') {
      return {
        kind: 'calls',
        feedId: card.feedId,
        name: card.name,
        region: card.region,
        callsLinks: card.callsLinks,
      };
    }
    return {
      kind: 'listen',
      feedId: card.feedId,
      name: card.name,
      region: card.region || 'other',
    };
  });
  localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
}

function ensureSection(region) {
  let node = sectionNodes.get(region);
  if (!node) {
    node = document.createElement('h2');
    node.className = 'feed-section';
    node.dataset.region = region;
    node.textContent = SECTION_TITLES[region] || region;
    sectionNodes.set(region, node);
  }
  return node;
}

/** Move cards in saved order and insert a section label when the region changes. */
function layoutFeeds() {
  for (const node of sectionNodes.values()) node.remove();
  let previous = null;
  for (const card of players.values()) {
    const region = card.region;
    if ((region === 'nevada' || region === 'california') && region !== previous) {
      els.grid.appendChild(ensureSection(region));
    }
    previous = region;
    els.grid.appendChild(card.root);
  }
}

/**
 * Must run synchronously inside a click handler — before any await —
 * so Chromium still has a user gesture for audio.play().
 * @param {FeedPlayer[]} list
 */
function unlockAudioGesture(list) {
  try {
    ensureSharedAudioCtx();
  } catch (_) {}

  for (const p of list) {
    try {
      p.ensureAnalyser();
    } catch (_) {}
    const a = p.audio;
    try {
      a.playsInline = true;
      // Prime each element under the gesture (muted silent clip).
      const resumeMuted = a.muted;
      a.muted = true;
      a.src = SILENT_WAV;
      p._unlockGen = (p._unlockGen || 0) + 1;
      const myGen = p._unlockGen;
      const pr = a.play();
      p._gestureUnlocked = true;
      if (pr && typeof pr.then === 'function') {
        pr.then(() => {
          // Do not wipe src if real HLS attach already started
          if (p._unlockGen !== myGen || p.hls || p.wantPlay) {
            p.applyVolume();
            return;
          }
          try {
            a.pause();
            a.removeAttribute('src');
            a.load();
          } catch (_) {}
          // resumeMuted was element.muted before unlock; map back to player mute only if no gain yet
          if (!p.gainNode) a.muted = resumeMuted;
          p.applyVolume();
        }).catch(() => {
          p.applyVolume();
        });
      } else {
        if (!p.gainNode) a.muted = resumeMuted;
        p.applyVolume();
      }
    } catch (_) {}
  }
}

function toProxyUrl(hlsUrl) {
  return '/proxy?url=' + encodeURIComponent(hlsUrl);
}

function isNotAllowed(err) {
  if (!err) return false;
  const name = err.name || '';
  const msg = String(err.message || err);
  return name === 'NotAllowedError' || /notallowed|user.?gesture|interact/i.test(msg);
}

class FeedPlayer {
  constructor(feedId, name) {
    this.kind = 'listen';
    this.region = 'other';
    this.feedId = String(feedId);
    this.name = name || `Feed ${feedId}`;
    this.hls = null;
    this.muted = false;
    this.volume = 1;
    this.wantPlay = false;
    this.status = 'idle';
    this._gestureUnlocked = false;
    this._statusDetail = '';
    /** @type {MediaElementAudioSourceNode|null} */
    this.mediaSource = null;
    /** @type {AnalyserNode|null} */
    this.analyser = null;
    /** @type {GainNode|null} */
    this.gainNode = null;
    this._freqData = null;
    this._vizRaf = 0;
    this._barLevels = new Float32Array(SPECTRUM_BARS);

    const node = els.tpl.content.firstElementChild.cloneNode(true);
    node.dataset.feedId = this.feedId;
    this.root = node;
    this.elName = node.querySelector('.feed-name');
    this.elId = node.querySelector('.feed-id');
    this.elStatus = node.querySelector('.status');
    this.audio = node.querySelector('.feed-audio');
    this.canvas = node.querySelector('.spectrum');
    this.btnPlay = node.querySelector('.btn-play');
    this.btnStop = node.querySelector('.btn-stop');
    this.btnMute = node.querySelector('.btn-mute');
    this.btnReconnect = node.querySelector('.btn-reconnect');
    this.btnRemove = node.querySelector('.btn-remove');
    this.volSlider = node.querySelector('.feed-volume');

    this.audio.setAttribute('playsinline', '');
    this.audio.playsInline = true;
    this.audio.preload = 'none';

    this.elName.textContent = this.name;
    this.elId.textContent = `ID ${this.feedId}`;

    this.btnPlay.addEventListener('click', () => {
      unlockAudioGesture([this]);
      this.play();
    });
    this.btnStop.addEventListener('click', () => this.stop());
    this.btnMute.addEventListener('click', () => this.toggleMute());
    this.btnReconnect.addEventListener('click', () => {
      unlockAudioGesture([this]);
      this.reconnect();
    });
    this.btnRemove.addEventListener('click', () => removeFeed(this.feedId));
    this.volSlider.addEventListener('input', () => {
      this.volume = Number(this.volSlider.value) / 100;
      this.applyVolume();
    });

    this.audio.addEventListener('playing', () => {
      if (this.wantPlay) {
        this.setStatus(this.muted ? 'muted' : 'playing');
        this.startVisualizer();
      }
    });
    this.audio.addEventListener('waiting', () => {
      if (this.wantPlay && this.status !== 'blocked') this.setStatus('loading');
    });
    this.audio.addEventListener('error', () => {
      if (this.wantPlay) {
        this.setStatus('error', 'audio error');
        this.scheduleReconnect();
      }
    });

    this.applyVolume();
    this.setStatus('idle');
    this.clearSpectrum();
  }

  setStatus(s, detail) {
    this.status = s;
    this._statusDetail = detail || '';
    const label = detail ? `${s}: ${detail}` : s;
    this.elStatus.textContent = label;
    this.elStatus.dataset.status = s;
    this.elStatus.title = label;
  }

  applyVolume() {
    const master = Number(els.masterVol.value) / 100;
    // Never leave volume stuck at 0 unless user chose it
    const vol = Math.max(0, Math.min(1, this.volume * master));
    if (this.gainNode) {
      // Keep element unmuted so AnalyserNode still sees traffic when card is muted
      this.audio.muted = false;
      this.audio.volume = 1;
      this.gainNode.gain.value = this.muted ? 0 : vol;
    } else {
      this.audio.muted = this.muted;
      this.audio.volume = vol;
    }
    this.btnMute.classList.toggle('is-muted', this.muted);
    this.btnMute.textContent = this.muted ? 'Unmute' : 'Mute';
    if (this.wantPlay && this.status === 'playing' && this.muted) {
      this.setStatus('muted');
    } else if (this.wantPlay && this.status === 'muted' && !this.muted) {
      this.setStatus('playing');
    }
  }

  /**
   * One MediaElementSource per <audio> — create once and reuse.
   * Graph: source → analyser → gain → destination (mute via gain so spectrum still moves).
   */
  ensureAnalyser() {
    if (this.analyser) return true;
    try {
      const ctx = ensureSharedAudioCtx();
      if (!ctx) return false;
      this.mediaSource = ctx.createMediaElementSource(this.audio);
      this.analyser = ctx.createAnalyser();
      this.analyser.fftSize = 64;
      this.analyser.smoothingTimeConstant = 0.72;
      this.analyser.minDecibels = -90;
      this.analyser.maxDecibels = -25;
      this.gainNode = ctx.createGain();
      this.mediaSource.connect(this.analyser);
      this.analyser.connect(this.gainNode);
      this.gainNode.connect(ctx.destination);
      this._freqData = new Uint8Array(this.analyser.frequencyBinCount);
      this.applyVolume();
      return true;
    } catch (e) {
      console.warn(`[feed ${this.feedId}] analyser setup`, e);
      return false;
    }
  }

  startVisualizer() {
    if (this._vizRaf) return;
    const tick = () => {
      this._vizRaf = requestAnimationFrame(tick);
      this.drawSpectrum();
    };
    this._vizRaf = requestAnimationFrame(tick);
  }

  stopVisualizer() {
    if (this._vizRaf) {
      cancelAnimationFrame(this._vizRaf);
      this._vizRaf = 0;
    }
    this.clearSpectrum();
  }

  clearSpectrum() {
    const canvas = this.canvas;
    if (!canvas) return;
    const ctx2d = canvas.getContext('2d');
    if (!ctx2d) return;
    const dpr = window.devicePixelRatio || 1;
    const cssW = canvas.clientWidth || 200;
    const cssH = canvas.clientHeight || 28;
    const w = Math.max(1, Math.floor(cssW * dpr));
    const h = Math.max(1, Math.floor(cssH * dpr));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    ctx2d.setTransform(1, 0, 0, 1, 0, 0);
    ctx2d.fillStyle = '#0a0e14';
    ctx2d.fillRect(0, 0, w, h);
    // Idle near-zero ticks so the meter is visible even when silent
    const bars = SPECTRUM_BARS;
    const gap = Math.max(1, Math.floor(w * 0.012));
    const barW = Math.max(1, Math.floor((w - gap * (bars - 1)) / bars));
    const total = bars * barW + (bars - 1) * gap;
    let x = Math.floor((w - total) / 2);
    const idleH = Math.max(1, Math.floor(h * 0.06));
    const grad = ctx2d.createLinearGradient(0, h, 0, 0);
    grad.addColorStop(0, '#1a7a2e');
    grad.addColorStop(0.55, '#3fb950');
    grad.addColorStop(1, '#d4a017');
    ctx2d.fillStyle = grad;
    for (let i = 0; i < bars; i++) {
      ctx2d.fillRect(x, h - idleH, barW, idleH);
      x += barW + gap;
    }
  }

  drawSpectrum() {
    const canvas = this.canvas;
    if (!canvas) return;
    const ctx2d = canvas.getContext('2d');
    if (!ctx2d) return;

    const dpr = window.devicePixelRatio || 1;
    const cssW = canvas.clientWidth || 200;
    const cssH = canvas.clientHeight || 28;
    const w = Math.max(1, Math.floor(cssW * dpr));
    const h = Math.max(1, Math.floor(cssH * dpr));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }

    const bars = SPECTRUM_BARS;
    const levels = this._barLevels;

    if (this.analyser && this._freqData) {
      this.analyser.getByteFrequencyData(this._freqData);
      const data = this._freqData;
      // Focus on lower bins (radio/speech energy); skip DC
      const usable = Math.max(1, Math.min(data.length - 1, Math.floor(data.length * 0.85)));
      for (let i = 0; i < bars; i++) {
        const start = 1 + Math.floor((i / bars) * usable);
        const end = 1 + Math.floor(((i + 1) / bars) * usable);
        let sum = 0;
        let n = 0;
        for (let j = start; j < end; j++) {
          sum += data[j];
          n++;
        }
        const raw = (n ? sum / n : 0) / 255;
        // Light boost so quiet scanner squelch still shows a hint
        const boosted = Math.min(1, Math.pow(raw, 0.85) * 1.15);
        levels[i] = levels[i] * 0.45 + boosted * 0.55;
      }
    } else {
      for (let i = 0; i < bars; i++) levels[i] *= 0.85;
    }

    ctx2d.setTransform(1, 0, 0, 1, 0, 0);
    ctx2d.fillStyle = '#0a0e14';
    ctx2d.fillRect(0, 0, w, h);

    const gap = Math.max(1, Math.floor(w * 0.012));
    const barW = Math.max(1, Math.floor((w - gap * (bars - 1)) / bars));
    const total = bars * barW + (bars - 1) * gap;
    let x = Math.floor((w - total) / 2);

    const grad = ctx2d.createLinearGradient(0, h, 0, 0);
    grad.addColorStop(0, '#1a9b3c');
    grad.addColorStop(0.45, '#3ecf5a');
    grad.addColorStop(0.75, '#c9c22a');
    grad.addColorStop(1, '#f0d030');
    ctx2d.fillStyle = grad;

    const minH = Math.max(1, Math.floor(h * 0.05));
    for (let i = 0; i < bars; i++) {
      const bh = Math.max(minH, Math.floor(levels[i] * h * 0.95));
      ctx2d.fillRect(x, h - bh, barW, bh);
      x += barW + gap;
    }
  }

  async fetchStream() {
    const res = await fetch(`/api/stream/${this.feedId}`);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    if (data.name && data.name !== this.name) {
      this.name = data.name;
      this.elName.textContent = this.name;
      saveFeeds();
    }
    if (!data.hlsUrl) throw new Error('No hlsUrl in response');
    return data.hlsUrl;
  }

  destroyHls() {
    if (this.hls) {
      try {
        this.hls.destroy();
      } catch (_) {}
      this.hls = null;
    }
    try {
      this.audio.pause();
      this.audio.removeAttribute('src');
      this.audio.load();
    } catch (_) {}
  }

  async attach(hlsUrl) {
    this.destroyHls();
    const playUrl = toProxyUrl(hlsUrl);

    if (window.Hls && Hls.isSupported()) {
      const hls = new Hls({
        enableWorker: true,
        lowLatencyMode: true,
        liveDurationInfinity: true,
        manifestLoadingMaxRetry: 4,
        levelLoadingMaxRetry: 4,
        fragLoadingMaxRetry: 4,
      });
      this.hls = hls;
      hls.loadSource(playUrl);
      hls.attachMedia(this.audio);
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!this.wantPlay) return;
        if (data.fatal) {
          this.setStatus('error', data.details || 'HLS');
          this.scheduleReconnect();
        }
      });
      await new Promise((resolve, reject) => {
        const t = setTimeout(() => {
          cleanup();
          reject(new Error('manifest timeout'));
        }, 20000);
        const onManifest = () => {
          cleanup();
          resolve();
        };
        const onErr = (_ev, d) => {
          if (d.fatal) {
            cleanup();
            reject(new Error(d.details || 'HLS fatal error'));
          }
        };
        const cleanup = () => {
          clearTimeout(t);
          hls.off(Hls.Events.MANIFEST_PARSED, onManifest);
          hls.off(Hls.Events.ERROR, onErr);
        };
        hls.on(Hls.Events.MANIFEST_PARSED, onManifest);
        hls.on(Hls.Events.ERROR, onErr);
      });
    } else if (this.audio.canPlayType('application/vnd.apple.mpegurl')) {
      this.audio.src = playUrl;
    } else {
      throw new Error('HLS not supported in this browser');
    }
  }

  async tryPlayAudio() {
    this.ensureAnalyser();
    this.applyVolume();
    try {
      await this.audio.play();
      this.startVisualizer();
      return true;
    } catch (e) {
      if (isNotAllowed(e)) {
        this.setStatus('blocked', 'click Play');
        this.btnPlay.disabled = false;
        return false;
      }
      throw e;
    }
  }

  async play() {
    this.wantPlay = true;
    this.clearReconnectTimer();
    this.setStatus('loading');
    this.btnPlay.disabled = true;
    this.btnStop.disabled = false;
    try {
      const hlsUrl = await this.fetchStream();
      if (!this.wantPlay) return;
      await this.attach(hlsUrl);
      if (!this.wantPlay) return;
      const ok = await this.tryPlayAudio();
      if (ok) this.setStatus(this.muted ? 'muted' : 'playing');
    } catch (e) {
      console.error(`[feed ${this.feedId}]`, e);
      const msg = (e && e.message) || 'failed';
      if (isNotAllowed(e)) {
        this.setStatus('blocked', 'click Play');
        this.btnPlay.disabled = false;
      } else {
        this.setStatus('error', String(msg).slice(0, 40));
        this.scheduleReconnect();
      }
    }
  }

  stop() {
    this.wantPlay = false;
    this.clearReconnectTimer();
    this.destroyHls();
    this.stopVisualizer();
    this.btnPlay.disabled = false;
    this.btnStop.disabled = true;
    this.setStatus('idle');
  }

  async reconnect() {
    if (!this.wantPlay) {
      await this.play();
      return;
    }
    this.setStatus('reconnecting');
    this.btnStop.disabled = false;
    try {
      const hlsUrl = await this.fetchStream();
      if (!this.wantPlay) return;
      await this.attach(hlsUrl);
      if (!this.wantPlay) return;
      const ok = await this.tryPlayAudio();
      if (ok) this.setStatus(this.muted ? 'muted' : 'playing');
    } catch (e) {
      console.error(`[feed ${this.feedId}] reconnect`, e);
      if (isNotAllowed(e)) {
        this.setStatus('blocked', 'click Play');
        this.btnPlay.disabled = false;
      } else {
        this.setStatus('error', String((e && e.message) || 'reconnect').slice(0, 40));
        this.scheduleReconnect();
      }
    }
  }

  toggleMute() {
    this.muted = !this.muted;
    this.applyVolume();
  }

  scheduleReconnect() {
    this.clearReconnectTimer();
    if (!this.wantPlay) return;
    // Don't auto-loop on blocked (needs a click)
    if (this.status === 'blocked') return;
    this._reconnectTimer = setTimeout(() => {
      if (this.wantPlay && this.status !== 'blocked') this.reconnect();
    }, 4000);
  }

  clearReconnectTimer() {
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
  }

  dispose() {
    this.stop();
    this.root.remove();
  }
}

class CallsCard {
  constructor(feed) {
    this.kind = 'calls';
    this.feedId = String(feed.feedId);
    this.name = feed.name || 'Calls';
    this.region = feed.region || 'nevada';
    this.callsLinks = (feed.callsLinks || []).map((link) => ({ ...link }));

    const node = els.callsTpl.content.firstElementChild.cloneNode(true);
    node.dataset.feedId = this.feedId;
    this.root = node;
    node.querySelector('.feed-name').textContent = this.name;
    const tgLine = this.callsLinks
      .map((link) => `${link.label} TG ${link.talkgroup}`)
      .join('  ·  ');
    node.querySelector('.calls-tgs').textContent = tgLine;
    this.btnOpenAll = node.querySelector('.btn-open-all');
    this.btnRemove = node.querySelector('.btn-remove');
    if (this.callsLinks.length > 1) this.btnOpenAll.textContent = 'Open all';

    this.btnOpenAll.addEventListener('click', () => this.openAll());
    this.btnRemove.addEventListener('click', () => removeFeed(this.feedId));

    const linksEl = node.querySelector('.calls-links');
    if (this.callsLinks.length > 1) {
      for (const link of this.callsLinks) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn-sm btn-calls-link';
        button.textContent = `${link.label} · TG ${link.talkgroup}`;
        button.addEventListener('click', () => this.openOne(link.url));
        linksEl.appendChild(button);
      }
    }
  }

  openAll() {
    for (const link of this.callsLinks) this.openOne(link.url);
  }

  openOne(url) {
    if (!parseCallsUrl(url)) return;
    window.open(url, '_blank', 'noopener,noreferrer');
  }

  dispose() {
    this.root.remove();
  }
}

function addListen(feedId, name, region) {
  const id = String(feedId).trim();
  if (!/^\d+$/.test(id) || players.has(id)) return;
  const player = new FeedPlayer(id, name);
  const known = knownListen(id);
  player.region = region || (known && known.region) || 'other';
  players.set(id, player);
  layoutFeeds();
  saveFeeds();
}

function addCalls(feed) {
  const normalized = normalizeCalls(feed);
  if (!normalized || players.has(normalized.feedId)) return;
  const card = new CallsCard(normalized);
  players.set(normalized.feedId, card);
  layoutFeeds();
  saveFeeds();
}

function addFeed(feedId, name) {
  const id = String(feedId).trim();
  if (!/^\d+$/.test(id) || players.has(id)) return;
  const known = knownListen(id);
  addListen(id, name || (known && known.name), known ? known.region : 'other');
}

function removeFeed(feedId) {
  const p = players.get(String(feedId));
  if (!p) return;
  p.dispose();
  players.delete(String(feedId));
  layoutFeeds();
  saveFeeds();
}

function init() {
  // Ensure master volume is not zero
  if (Number(els.masterVol.value) === 0) els.masterVol.value = '80';

  const feeds = loadFeeds();
  for (const f of feeds) {
    if (f.kind === 'calls') addCalls(f);
    else addListen(f.feedId, f.name, f.region);
  }

  els.playAll.addEventListener('click', () => {
    const list = [...players.values()].filter((p) => p.kind !== 'calls');
    // CRITICAL: unlock synchronously before any network await
    unlockAudioGesture(list);
    for (const p of list) {
      if (!p.wantPlay) p.play();
      else if (p.status === 'blocked' || p.status === 'error') p.reconnect();
    }
  });

  els.stopAll.addEventListener('click', () => {
    for (const p of players.values()) {
      if (p.kind !== 'calls') p.stop();
    }
  });

  els.masterVol.addEventListener('input', () => {
    for (const p of players.values()) {
      if (p.kind !== 'calls') p.applyVolume();
    }
  });

  els.settingsBtn.addEventListener('click', () => {
    const open = els.settingsPanel.classList.toggle('hidden') === false;
    els.settingsPanel.setAttribute('aria-hidden', open ? 'false' : 'true');
  });

  els.addForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const id = els.newId.value.trim();
    const name = els.newName.value.trim() || undefined;
    if (!id) return;
    addFeed(id, name);
    els.newId.value = '';
    els.newName.value = '';
  });
}

init();
