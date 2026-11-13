'use strict';
/*
 * Web version of the Android app: the same drawing code (Hardware.java + PodView.java) ported to
 * the canvas. Sizes of the body are real millimetres times u (pixels per mm); the screen is drawn
 * in the iPod's own 320x240 grid. Songs are Apple Music's most-played chart, 30-second previews.
 */
(() => {
  // ------------------------------------------------------------------ colours and gradients

  const col = v => {
    v = v >>> 0;
    return `rgba(${(v >>> 16) & 255},${(v >>> 8) & 255},${v & 255},${(((v >>> 24) & 255) / 255).toFixed(3)})`;
  };
  const stops = (g, colors, at) => {
    colors.forEach((c, i) => g.addColorStop(at ? at[i] : i / (colors.length - 1), col(c)));
    return g;
  };
  const lin = (c, x0, y0, x1, y1, colors, at) => stops(c.createLinearGradient(x0, y0, x1, y1), colors, at);
  const vert = (c, y0, y1, colors, at) => lin(c, 0, y0, 0, y1, colors, at);
  const radial = (c, x, y, r, colors, at) => stops(c.createRadialGradient(x, y, 0, x, y, r), colors, at);
  const canvas = (w, h) => { const e = document.createElement('canvas'); e.width = w; e.height = h; return e; };

  function rr(c, x, y, w, h, r) {
    r = Math.max(0, Math.min(r, w / 2, h / 2));
    c.beginPath();
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r);
    c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r);
    c.arcTo(x, y, x + w, y, r);
    c.closePath();
  }
  function disc(c, x, y, r, style) {
    c.beginPath();
    c.arc(x, y, r, 0, Math.PI * 2);
    c.fillStyle = style;
    c.fill();
  }

  // ------------------------------------------------------------------ the physical player

  const W = 61.8, H = 103.5, CORNER = 6.8;
  const WIN_X = 4.85, WIN_Y = 4.85, WIN_W = 52.1, WIN_H = 39.6, LCD_W = 49.9;
  const WHEEL_Y = 72.86, WHEEL_R = 18.93, BUTTON_R = 6.82;

  const FINISHES = {
    silver: { dark: false, face: [0xffe6e8eb, 0xffd9dbdf, 0xffc6c8cd], edge: [0xd0ffffff, 0x70ffffff, 0x20ffffff, 0x40ffffff],
      roll: 0x42000000, wheel: [0xffffffff, 0xfff4f4f5, 0xffe5e6e8], label: 0xffa7a9ae,
      button: [0xffc3c5c9, 0xffd7d9dc, 0xffeaebed], recess: [0xff64676c, 0xffb4b7bb, 0xffffffff], grain: 12 },
    black: { dark: true, face: [0xff303134, 0xff222326, 0xff141517], edge: [0x80ffffff, 0x38ffffff, 0x0cffffff, 0x1cffffff],
      roll: 0x70000000, wheel: [0xff2b2c2f, 0xff1f2023, 0xff151618], label: 0xffe9eaec,
      button: [0xff121315, 0xff1c1d20, 0xff2a2b2e], recess: [0xff000000, 0xff2a2b2e, 0xff6a6c70], grain: 9 },
    red: { dark: false, face: [0xffdd2a35, 0xffc81d28, 0xffa3121b], edge: [0xb0ffffff, 0x58ffffff, 0x14ffffff, 0x30ffffff],
      roll: 0x50000000, wheel: [0xffffffff, 0xfff4f4f5, 0xffe5e6e8], label: 0xffa7a9ae,
      button: [0xffa0111a, 0xffbb1923, 0xffd12631], recess: [0xff5e0a10, 0xffb8323a, 0xffff9aa0], grain: 12 },
  };
  /** Mostly silver; now and then the black one, or the rare red one. ?finish=black forces one. */
  function pickFinish() {
    const forced = new URLSearchParams(location.search).get('finish');
    if (FINISHES[forced]) return FINISHES[forced];
    const roll = Math.random() * 100;
    return roll < 10 ? FINISHES.black : roll < 20 ? FINISHES.red : FINISHES.silver;
  }

  const FILTER = typeof canvas(1, 1).getContext('2d').filter === 'string';
  let scratch = null;

  /** Draws into a scratch layer, then composites it softened (blur radius r, like Android's BlurMaskFilter). */
  function soft(c, w, h, r, draw) {
    if (!scratch || scratch.width !== w || scratch.height !== h) scratch = canvas(w, h);
    const t = scratch.getContext('2d');
    t.setTransform(1, 0, 0, 1, 0, 0);
    t.clearRect(0, 0, w, h);
    draw(t);
    if (FILTER) {
      c.filter = `blur(${(r * 0.57735 + 0.5).toFixed(2)}px)`;
      c.drawImage(scratch, 0, 0);
      c.filter = 'none';
    } else {
      c.globalAlpha = 0.7;
      c.drawImage(scratch, 0, 0);
      c.globalAlpha = 1;
    }
  }

  /** A blurred silhouette of whatever path draw() builds: the shape stays off-canvas, only its shadow lands. */
  function shadow(c, color, r, draw) {
    const far = 100000;
    c.save();
    c.shadowColor = col(color);
    c.shadowBlur = r * 1.15;
    c.shadowOffsetY = far;
    c.translate(0, -far);
    draw();
    c.fillStyle = '#000';
    c.fill();
    c.restore();
  }

  function grain(c, strength) {
    const n = 160, e = canvas(n, n), g = e.getContext('2d'), img = g.createImageData(n, n);
    let s = 11;
    const rnd = () => { s = (s * 1103515245 + 12345) & 0x7fffffff; return s / 0x7fffffff; };
    for (let i = 0; i < n * n; i++) {
      const v = rnd() < 0.5 ? 255 : 0;
      img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = v;
      img.data[i * 4 + 3] = Math.floor(rnd() * strength);
    }
    g.putImageData(img, 0, 0);
    return c.createPattern(e, 'repeat');
  }

  function renderHardware(w, h, reserve, f) {
    const u = Math.min(w * 0.94 / W, (h - reserve) * 0.95 / H);
    const bw = W * u, bh = H * u, bx = (w - bw) / 2, by = (h - reserve - bh) / 2;
    const win = { x: bx + WIN_X * u, y: by + WIN_Y * u, w: WIN_W * u, h: WIN_H * u };
    const lw = Math.round(LCD_W * u), lh = Math.round(LCD_W * u * 0.75);
    const lcd = { x: Math.round(win.x + win.w / 2 - lw / 2), y: Math.round(win.y + win.h / 2 - lh / 2), w: lw, h: lh };
    const g = { u, win, lcd, cx: bx + bw / 2, cy: by + WHEEL_Y * u, R: WHEEL_R * u, r: BUTTON_R * u, f };
    const { cx, cy, R, r } = g;
    const img = canvas(w, h), c = img.getContext('2d');
    const rc = CORNER * u, noise = grain(c, f.grain);

    c.fillStyle = radial(c, w / 2, by + bh * 0.4, Math.max(w, h) * 0.7, [0xff3d4046, 0xff1d1f22, 0xff09090a], [0, 0.55, 1]);
    c.fillRect(0, 0, w, h);
    shadow(c, 0xb0000000, 3.4 * u, () => rr(c, bx, by + 1.9 * u, bw, bh, rc));
    shadow(c, 0x90000000, 0.8 * u, () => rr(c, bx, by + 0.5 * u, bw, bh, rc));

    // hold switch peeking over the top edge
    const sy = by - 0.55 * u;
    c.fillStyle = vert(c, sy, by, [0xfff4f5f6, 0xffa9acb0]);
    rr(c, bx + bw * 0.137, sy, bw * 0.173, 2.05 * u, 0.3 * u);
    c.fill();

    // polished steel back, seen as a thin rim around the face
    c.fillStyle = vert(c, by, by + bh,
      [0xffffffff, 0xffb1b4b9, 0xffe9eaec, 0xffa3a6ab, 0xff6c6f75, 0xffc2c5c9, 0xff7f8288], [0, 0.03, 0.085, 0.5, 0.93, 0.975, 1]);
    rr(c, bx, by, bw, bh, rc);
    c.fill();
    c.fillStyle = lin(c, bx, 0, bx + bw, 0, [0x80ffffff, 0x00ffffff, 0x00000000, 0x50000000], [0, 0.05, 0.95, 1]);
    c.fill();

    // anodised face
    const e = 0.42 * u, rf = rc - e, fx = bx + e, fy = by + e, fw = bw - 2 * e, fh = bh - 2 * e;
    rr(c, fx, fy, fw, fh, rf);
    c.strokeStyle = col(0x70000000);
    c.lineWidth = 0.12 * u;
    c.stroke();
    c.fillStyle = vert(c, fy, fy + fh, f.face, [0, 0.45, 1]);
    c.fill();
    c.save();
    rr(c, fx, fy, fw, fh, rf);
    c.clip();
    soft(c, w, h, 1.1 * u, t => {                       // the face rolls off toward its edge
      rr(t, fx, fy, fw, fh, rf);
      t.strokeStyle = col(f.roll);
      t.lineWidth = 2.6 * u;
      t.stroke();
    });
    const i2 = 0.55 * u;
    soft(c, w, h, 0.45 * u, t => {                      // light catching that rolled edge
      rr(t, fx + i2, fy + i2, fw - 2 * i2, fh - 2 * i2, rf - i2);
      t.strokeStyle = vert(t, by, by + bh, f.edge, [0, 0.1, 0.6, 1]);
      t.lineWidth = 0.4 * u;
      t.stroke();
    });
    c.fillStyle = noise;
    c.fillRect(0, 0, w, h);
    c.restore();

    // screen: bevelled opening, black glass, dark panel
    const o = 0.22 * u;
    c.fillStyle = lin(c, win.x - o, win.y - o, win.x + win.w + o, win.y + win.h + o, f.recess, [0, 0.5, 1]);
    rr(c, win.x - o, win.y - o, win.w + 2 * o, win.h + 2 * o, 1.2 * u);
    c.fill();
    c.fillStyle = vert(c, win.y, win.y + win.h, [0xff17181a, 0xff050506]);
    rr(c, win.x, win.y, win.w, win.h, u);
    c.fill();
    c.fillStyle = '#000';
    c.fillRect(lcd.x, lcd.y, lcd.w, lcd.h);

    // click wheel
    disc(c, cx, cy, R + 0.2 * u, lin(c, cx - R, cy - R, cx + R, cy + R, f.recess, [0, 0.5, 1]));
    disc(c, cx, cy, R, lin(c, cx - R * 0.5, cy - R, cx + R * 0.5, cy + R, f.wheel, [0, 0.5, 1]));
    disc(c, cx, cy, R, radial(c, cx, cy, R, [0, 0, 0x14000000, 0x38000000], [0, 0.95, 0.985, 1]));
    c.fillStyle = col(f.label);
    menuLabel(c, cx, cy - 0.815 * R, 0.1015 * R);
    const ih = 0.049 * R;
    skipLabel(c, cx + 0.81 * R, cy, ih, 1, R);
    skipLabel(c, cx - 0.81 * R, cy, ih, -1, R);
    playLabel(c, cx, cy + 0.822 * R, ih, R);

    // centre button
    disc(c, cx, cy, r + 0.2 * u, lin(c, cx - r, cy - r, cx + r, cy + r, f.recess, [0, 0.5, 1]));
    disc(c, cx, cy, r, vert(c, cy - r, cy + r, f.button, [0, 0.55, 1]));
    c.save();
    c.beginPath();
    c.arc(cx, cy, r, 0, Math.PI * 2);
    c.clip();
    soft(c, w, h, 0.5 * u, t => {
      t.beginPath();
      t.arc(cx, cy, r, 0, Math.PI * 2);
      t.strokeStyle = vert(t, cy - r, cy + r, [0x58000000, 0x00000000, 0x00ffffff, f.dark ? 0x40ffffff : 0xc0ffffff], [0, 0.45, 0.55, 1]);
      t.lineWidth = u;
      t.stroke();
    });
    c.fillStyle = noise;
    c.fillRect(0, 0, w, h);
    c.restore();

    g.img = img;
    return g;
  }

  function tri(c, x, y, half, width) {
    c.moveTo(x, y - half);
    c.lineTo(x + width, y);
    c.lineTo(x, y + half);
    c.closePath();
  }
  function skipLabel(c, x, y, ih, dir, R) {
    const t = 0.0714 * R, bar = 0.022 * R, start = x - dir * (t * 2 + bar) / 2;
    c.beginPath();
    tri(c, start, y, ih, dir * t);
    tri(c, start + dir * t, y, ih, dir * t);
    const b0 = start + dir * t * 2, b1 = b0 + dir * bar;
    c.rect(Math.min(b0, b1), y - ih, Math.abs(b1 - b0), ih * 2);
    c.fill();
  }
  function playLabel(c, x, y, ih, R) {
    const t = 0.0943 * R, gap = 0.053 * R, bar = 0.022 * R, between = 0.0238 * R;
    x -= (t + gap + bar * 2 + between) / 2;
    c.beginPath();
    tri(c, x, y, ih, t);
    x += t + gap;
    c.rect(x, y - ih, bar, ih * 2);
    c.rect(x + bar + between, y - ih, bar, ih * 2);
    c.fill();
  }
  /** "MENU" drawn as shapes, matching the printed label. hc = cap height. */
  function menuLabel(c, x, y, hc) {
    const gap = 0.26, total = 1.03 + 0.596 + 0.761 + 0.771 + 3 * gap;
    c.save();
    c.translate(x - total * hc / 2, y - hc / 2);
    c.scale(hc, hc);
    c.beginPath();
    const m = [0, 1, 0.105, 0, 0.29, 0, 0.515, 0.66, 0.74, 0, 0.925, 0, 1.03, 1, 0.86, 1, 0.79, 0.36, 0.572, 1, 0.458, 1, 0.24, 0.36, 0.17, 1];
    c.moveTo(m[0], m[1]);
    for (let i = 2; i < m.length; i += 2) c.lineTo(m[i], m[i + 1]);
    c.closePath();
    let p = 1.03 + gap;
    c.rect(p, 0, 0.175, 1); c.rect(p, 0, 0.57, 0.155); c.rect(p, 0.42, 0.53, 0.15); c.rect(p, 0.845, 0.596, 0.155);
    p += 0.596 + gap;
    c.rect(p, 0, 0.17, 1); c.rect(p + 0.591, 0, 0.17, 1);
    c.moveTo(p, 0); c.lineTo(p + 0.19, 0); c.lineTo(p + 0.761, 1); c.lineTo(p + 0.571, 1); c.closePath();
    p += 0.761 + gap;
    c.moveTo(p, 0);
    c.lineTo(p + 0.175, 0);
    c.lineTo(p + 0.175, 0.62);
    c.ellipse(p + 0.3855, 0.62, 0.2105, 0.225, 0, Math.PI, 0, true);
    c.lineTo(p + 0.596, 0);
    c.lineTo(p + 0.771, 0);
    c.lineTo(p + 0.771, 0.62);
    c.ellipse(p + 0.3855, 0.62, 0.3855, 0.39, 0, 0, Math.PI, false);
    c.closePath();
    c.fill();
    c.restore();
  }

  // ------------------------------------------------------------------ state

  const MAIN = 0, MUSIC = 1, SETTINGS = 2, ARTISTS = 3, ALBUMS = 4, SONGS = 5, NOW = 6, MSG = 7;
  const ROWS = 8, STEP = Math.PI * 2 / 24, SLIDE_MS = 260, NOW_PLAYING = 'Now Playing';

  const store = {
    get(k, d) { try { const v = localStorage.getItem('pod.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('pod.' + k, JSON.stringify(v)); } catch (e) { /* private mode */ } },
  };

  class Screen {
    constructor(type, title, items = []) { Object.assign(this, { type, title, items: [...items], songs: null, sel: 0, top: 0 }); }
    split() { return this.type === MAIN || this.type === MUSIC || this.type === SETTINGS || this.type === MSG; }
  }

  let all = [];
  let stack = [new Screen(MSG, 'iPod', ['Loading…'])];
  let leaving = null, slideStart = 0, slideDir = 1;
  let scrub = false, volUntil = 0, scrubUntil = 0, clicker = store.get('clicker', true);
  let battery = 100, volume = store.get('volume', 0.8);
  const cur = () => stack[stack.length - 1];
  const now = () => performance.now();

  // ------------------------------------------------------------------ playback

  const audio = new Audio();
  audio.preload = 'auto';
  audio.volume = volume;
  const P = { queue: [], index: -1, playing: false, loading: false, shuffle: store.get('shuffle', false),
    repeat: store.get('repeat', 0), shuffled: false };
  const current = () => (P.index >= 0 && P.index < P.queue.length ? P.queue[P.index] : null);

  function play(list, i, shuffleAll) {
    P.queue = [...list];
    P.shuffled = shuffleAll || P.shuffle;
    if (P.shuffled) {
      const first = shuffleAll ? null : P.queue.splice(i, 1)[0];
      for (let k = P.queue.length - 1; k > 0; k--) {
        const j = Math.floor(Math.random() * (k + 1));
        [P.queue[k], P.queue[j]] = [P.queue[j], P.queue[k]];
      }
      if (first) P.queue.unshift(first);
      i = 0;
    }
    P.index = i;
    start();
  }
  function start() {
    const s = current();
    if (!s) { P.playing = false; return publish(); }
    P.playing = true;
    P.loading = true;
    audio.src = s.preview;
    audio.play().catch(() => { P.loading = false; P.playing = false; publish(); });
    publish();
  }
  function toggle() {
    if (!current()) { if (all.length) play(all, 0, false); return; }
    if (!audio.src) return start();
    if (P.playing) { audio.pause(); P.playing = false; }
    else { P.playing = true; audio.play().catch(() => { P.playing = false; publish(); }); }
    publish();
  }
  function next(auto) {
    if (!P.queue.length) return;
    if (auto && P.repeat === 1) { audio.currentTime = 0; audio.play().catch(() => {}); return; }
    if (P.index + 1 < P.queue.length) { P.index++; start(); }
    else if (!auto || P.repeat === 2) { P.index = 0; start(); }
    else { P.index = 0; P.playing = false; audio.removeAttribute('src'); audio.load(); publish(); }
  }
  function prev() {
    if (!P.queue.length) return;
    if (audio.currentTime > 3) { audio.currentTime = 0; return publish(); }
    P.index = Math.max(0, P.index - 1);
    start();
  }
  function seekBy(ms) {
    if (!audio.duration) return;
    audio.currentTime = Math.max(0, Math.min(audio.duration - 0.5, audio.currentTime + ms / 1000));
    publish();
  }
  const position = () => Math.round((audio.currentTime || 0) * 1000);
  const duration = () => (audio.duration && isFinite(audio.duration) ? Math.round(audio.duration * 1000) : 30000);

  audio.addEventListener('playing', () => { P.loading = false; P.playing = true; publish(); });
  audio.addEventListener('pause', () => { if (!P.loading) { P.playing = false; publish(); } });
  audio.addEventListener('ended', () => next(true));
  audio.addEventListener('error', () => { if (audio.src) { P.loading = false; P.playing = false; publish(); } });
  audio.addEventListener('loadedmetadata', publish);

  /** Tells the browser and the phone what is playing: lock screen, notification and headset buttons. */
  function publish() {
    dirty = true;
    const ms = navigator.mediaSession, s = current();
    if (!ms) return;
    if (s) {
      ms.metadata = new MediaMetadata({ title: s.title, artist: s.artist, album: s.album,
        artwork: [{ src: s.art, sizes: '600x600', type: 'image/jpeg' }] });
    }
    ms.playbackState = s ? (P.playing ? 'playing' : 'paused') : 'none';
    try {
      if (audio.duration && isFinite(audio.duration))
        ms.setPositionState({ duration: audio.duration, playbackRate: 1, position: Math.min(audio.currentTime, audio.duration) });
    } catch (e) { /* not supported */ }
  }
  if (navigator.mediaSession) {
    const on = (a, f) => { try { navigator.mediaSession.setActionHandler(a, f); } catch (e) { /* unsupported action */ } };
    on('play', () => { if (!P.playing) toggle(); });
    on('pause', () => { if (P.playing) toggle(); });
    on('stop', () => { if (P.playing) toggle(); });
    on('nexttrack', () => next(false));
    on('previoustrack', prev);
    on('seekto', d => { audio.currentTime = d.seekTime; publish(); });
    on('seekforward', () => seekBy(10000));
    on('seekbackward', () => seekBy(-10000));
  }

  // ------------------------------------------------------------------ feel

  let actx = null, clickBuf = null;
  function audioReady() {
    if (actx) { if (actx.state === 'suspended') actx.resume(); return; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    actx = new AC();
    const rate = actx.sampleRate, n = Math.floor(rate * 0.03);
    clickBuf = actx.createBuffer(1, n, rate);
    const d = clickBuf.getChannelData(0);
    for (let i = 0; i < n; i++) { const t = i / rate; d[i] = Math.sin(2 * Math.PI * 2500 * t) * Math.exp(-t / 0.0007) * 0.35; }
  }
  function click() {
    if (!clicker || !actx || !clickBuf) return;
    const s = actx.createBufferSource();
    s.buffer = clickBuf;
    s.connect(actx.destination);
    s.start();
  }
  const buzz = strong => { try { if (navigator.vibrate) navigator.vibrate(strong ? 12 : 5); } catch (e) { /* ignore */ } };

  // ------------------------------------------------------------------ menus

  function push(s) {
    leaving = cur();
    slideDir = 1;
    slideStart = now();
    stack.push(s);
    dirty = true;
  }
  function songScreen(title, songs) {
    const s = new Screen(SONGS, title, songs.map(x => x.title));
    s.songs = songs;
    return s;
  }
  function nowPlaying() {
    scrub = false;
    volUntil = 0;
    push(new Screen(NOW, NOW_PLAYING));
  }
  const byName = (a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' });

  function select() {
    const s = cur();
    if (s.type === NOW) { scrub = !scrub; scrubUntil = now() + 5000; volUntil = 0; dirty = true; return; }
    if (!s.items.length) return;
    const item = s.items[s.sel];
    switch (s.type) {
      case MSG:
        if (item === 'Try Again') load();
        break;
      case MAIN:
        if (item === 'Music') push(new Screen(MUSIC, 'Music', ['Top Songs', 'Artists', 'Albums', 'Songs']));
        else if (item === 'Settings') push(new Screen(SETTINGS, 'Settings', ['Shuffle', 'Repeat', 'Clicker']));
        else if (item === NOW_PLAYING) { if (current()) nowPlaying(); }
        else if (all.length) { play(all, 0, true); nowPlaying(); }
        break;
      case MUSIC:
        if (item === 'Top Songs') push(songScreen('Top Songs', all));
        else if (item === 'Songs') push(songScreen('Songs', [...all].sort((a, b) => byName(a.title, b.title))));
        else {
          const key = item === 'Artists' ? 'artist' : 'album';
          push(new Screen(key === 'artist' ? ARTISTS : ALBUMS, item, [...new Set(all.map(x => x[key]))].sort(byName)));
        }
        break;
      case SETTINGS:
        if (s.sel === 0) P.shuffle = !P.shuffle;
        else if (s.sel === 1) P.repeat = (P.repeat + 1) % 3;
        else clicker = !clicker;
        store.set('shuffle', P.shuffle);
        store.set('repeat', P.repeat);
        store.set('clicker', clicker);
        break;
      case ARTISTS:
      case ALBUMS: {
        const key = s.type === ARTISTS ? 'artist' : 'album';
        push(songScreen(item, all.filter(x => x[key] === item)
          .sort((a, b) => byName(a.album, b.album) || a.track - b.track || byName(a.title, b.title))));
        break;
      }
      case SONGS:
        play(s.songs, s.sel, false);
        nowPlaying();
        break;
    }
    dirty = true;
  }
  function menu() {
    if (stack.length <= 1) return false;
    leaving = stack.pop();
    slideDir = -1;
    slideStart = now();
    scrub = false;
    dirty = true;
    return true;
  }
  function settingValue(i) {
    if (i === 0) return P.shuffle ? 'Songs' : 'Off';
    if (i === 1) return ['Off', 'One', 'All'][P.repeat];
    return clicker ? 'On' : 'Off';
  }
  function tick(dir) {
    const s = cur(), t = now();
    if (s.type === NOW) {
      if (scrub && t < scrubUntil) { seekBy(dir * 4000); scrubUntil = t + 5000; }
      else {
        scrub = false;
        volume = Math.max(0, Math.min(1, Math.round((volume + dir / 16) * 16) / 16));
        audio.volume = volume;
        store.set('volume', volume);
        volUntil = t + 2500;
      }
    } else {
      const n = s.sel + dir;
      if (n < 0 || n >= s.items.length) return;
      s.sel = n;
      if (n < s.top) s.top = n;
      if (n >= s.top + ROWS) s.top = n - ROWS + 1;
    }
    buzz(false);
    click();
    dirty = true;
  }

  // ------------------------------------------------------------------ input

  const el = document.getElementById('pod');
  let hw = null, dpr = 1, dirty = true;
  let onWheel = false, onCenter = false, moved = false, held = false, sector = -1;
  let lastAngle = 0, accum = 0, travelled = 0, holdTimer = 0, pointerId = null;

  function pt(e) {
    const r = el.getBoundingClientRect();
    const x = (e.clientX - r.left) * dpr - hw.cx, y = (e.clientY - r.top) * dpr - hw.cy;
    return { d: Math.hypot(x, y), a: Math.atan2(y, x) };
  }
  el.addEventListener('pointerdown', e => {
    if (!hw || pointerId !== null) return;
    audioReady();
    const { d, a } = pt(e);
    onCenter = d < hw.r;
    onWheel = !onCenter && d <= hw.R * 1.06;
    if (!onCenter && !onWheel) return;
    pointerId = e.pointerId;
    el.setPointerCapture(e.pointerId);
    moved = held = false;
    lastAngle = a;
    accum = travelled = 0;
    if (onWheel) {
      const deg = a * 180 / Math.PI;
      sector = deg > -135 && deg <= -45 ? 0 : deg > -45 && deg <= 45 ? 1 : deg > 45 && deg <= 135 ? 2 : 3;
      holdTimer = setTimeout(onHold, 600);
    } else sector = -1;
    dirty = true;
    e.preventDefault();
  });
  el.addEventListener('pointermove', e => {
    if (e.pointerId !== pointerId || !onWheel) return;
    const { d, a } = pt(e);
    if (d < hw.r * 0.6) { lastAngle = a; return; }
    let da = a - lastAngle;
    if (da > Math.PI) da -= 2 * Math.PI;
    if (da < -Math.PI) da += 2 * Math.PI;
    lastAngle = a;
    accum += da;
    travelled += Math.abs(da);
    if (!moved && travelled > STEP * 0.7) { moved = true; clearTimeout(holdTimer); dirty = true; }
    if (held) return;
    while (accum >= STEP) { accum -= STEP; tick(1); }
    while (accum <= -STEP) { accum += STEP; tick(-1); }
  });
  function release(e, cancelled) {
    if (e.pointerId !== pointerId) return;
    clearTimeout(holdTimer);
    if (!cancelled) {
      const { d } = pt(e);
      if (onCenter && d < hw.r * 1.4) { buzz(true); select(); }
      else if (onWheel && !moved && !held) {
        buzz(true);
        if (sector === 0) menu();
        else if (sector === 1) next(false);
        else if (sector === 2) toggle();
        else prev();
      }
    }
    onCenter = onWheel = false;
    sector = -1;
    pointerId = null;
    dirty = true;
  }
  el.addEventListener('pointerup', e => release(e, false));
  el.addEventListener('pointercancel', e => release(e, true));
  el.addEventListener('contextmenu', e => e.preventDefault());

  function onHold() {
    if (!onWheel || moved) return;
    if (sector === 0 && !held) {
      held = true;
      if (stack.length > 1) {
        buzz(true);
        leaving = cur();
        stack = [stack[0]];
        slideDir = -1;
        slideStart = now();
        scrub = false;
      }
    } else if ((sector === 1 || sector === 3) && current()) {
      if (!held) buzz(true);
      held = true;
      seekBy(sector === 1 ? 5000 : -5000);
      holdTimer = setTimeout(onHold, 250);
    }
    dirty = true;
  }

  // keyboard and mouse wheel for desktops
  let wheelAcc = 0;
  el.addEventListener('wheel', e => {
    e.preventDefault();
    audioReady();
    wheelAcc += e.deltaY;
    const stepPx = e.deltaMode === 1 ? 1 : 40;
    while (wheelAcc >= stepPx) { wheelAcc -= stepPx; tick(1); }
    while (wheelAcc <= -stepPx) { wheelAcc += stepPx; tick(-1); }
  }, { passive: false });
  window.addEventListener('keydown', e => {
    const k = e.key;
    let handled = true;
    audioReady();
    if (k === 'ArrowDown' || k === 'ArrowRight') tick(1);
    else if (k === 'ArrowUp' || k === 'ArrowLeft') tick(-1);
    else if (k === 'Enter') select();
    else if (k === 'Escape' || k === 'Backspace') menu();
    else if (k === ' ') toggle();
    else if (k === 'MediaTrackNext') next(false);
    else if (k === 'MediaTrackPrevious') prev();
    else handled = false;
    if (handled) e.preventDefault();
  });

  // ------------------------------------------------------------------ screen drawing (320x240 grid)

  let G = null;          // gradients, made for the main context
  function gradients(c) {
    return {
      header: vert(c, 0, 19, [0xfffdfdfd, 0xffededed, 0xffe7e7e7, 0xffd8d8d8, 0xffbbbbbb, 0xffababab], [0, 0.2, 0.39, 0.5, 0.76, 1]),
      selected: vert(c, 1, 21, [0xff4aabd6, 0xff3eabe3, 0xff3ba1e3, 0xff2b93da, 0xff1584d3], [0, 0.1, 0.35, 0.6, 1]),
      battery: vert(c, 4, 14, [0xff98ca85, 0xffc1fda7, 0xffd1febb, 0xffbef0a9, 0xff9ce685, 0xff4da537, 0xff3e9f2e, 0xff339d21, 0xff388a28],
        [0, 0.11, 0.22, 0.33, 0.47, 0.5, 0.62, 0.85, 1]),
      batteryLow: vert(c, 4, 14, [0xffe59a8c, 0xffffc1b3, 0xfff2a092, 0xffd9432f, 0xffc0301e, 0xffa82a1a], [0, 0.2, 0.47, 0.5, 0.8, 1]),
      batteryEmpty: vert(c, 4, 14, [0xffc9c9c9, 0xfff4f4f4, 0xffe2e2e2]),
      batteryCase: vert(c, 3, 15, [0xff3a4b3a, 0xff5c7357]),
      play: vert(c, 3.3, 14.3, [0xff2a77a5, 0xff3b9ed9, 0xff52dcff, 0xff36a8e4, 0xff236a96], [0, 0.22, 0.5, 0.76, 1]),
      track: vert(c, 207, 219, [0xfffbfbfb, 0xffffffff, 0xfff6f6f6, 0xfff0f0f0, 0xffe3e3e3, 0xffe5e5e5, 0xfff2f2f2, 0xffededed],
        [0, 0.08, 0.25, 0.42, 0.58, 0.67, 0.83, 1]),
      progress: vert(c, 207, 219, [0xff87abff, 0xff6c99f6, 0xff6da3f9, 0xff66a3ff, 0xff3d89eb, 0xff3698f9, 0xff32a3ff, 0xff41b3fc, 0xff5cbbff],
        [0, 0.17, 0.33, 0.46, 0.5, 0.58, 0.67, 0.83, 1]),
      glow: vert(c, 220, 229, [0x405cbbff, 0x385cbbff, 0x005cbbff], [0, 0.3, 1]),
      pane: lin(c, 160, 0, 171, 0, [0x66000000, 0x42000000, 0x22000000, 0x00000000], [0, 0.15, 0.5, 1]),
      thumb: lin(c, 313, 0, 319, 0, [0xffa9aeb6, 0xff8b9199, 0xff6d737b]),
      diamond: vert(c, 202.5, 223.5, [0xff8e9299, 0xff3b3e44]),
    };
  }

  function fit(c, s, max) {
    if (c.measureText(s).width <= max) return s;
    let lo = 0, hi = s.length;
    while (lo < hi) {
      const m = (lo + hi + 1) >> 1;
      if (c.measureText(s.slice(0, m).trimEnd() + '…').width <= max) lo = m; else hi = m - 1;
    }
    return lo ? s.slice(0, lo).trimEnd() + '…' : '';
  }
  function text(c, s, x, y, max, size, color, bold, align) {
    c.font = `${bold ? 700 : 400} ${size}px Heros, Helvetica, Arial, sans-serif`;
    c.fillStyle = col(color);
    c.textAlign = align || 'left';
    c.fillText(fit(c, s, max), x, y);
    return c.measureText(fit(c, s, max)).width;
  }

  function drawScreens(c, t) {
    const s = cur();
    if (s.type === MAIN) {                      // "Now Playing" exists only once something is loaded
      const has = s.items.includes(NOW_PLAYING), want = !!current();
      if (want && !has) s.items.push(NOW_PLAYING);
      if (!want && has) { s.items.splice(s.items.indexOf(NOW_PLAYING), 1); s.sel = Math.min(s.sel, s.items.length - 1); }
    }
    const p = leaving ? (t - slideStart) / SLIDE_MS : 1;
    if (p >= 1) {
      leaving = null;
      if (s.split()) drawArt(c, t);
      drawScreen(c, s);
      return false;
    }
    const ease = 1 - (1 - p) * (1 - p);
    const menus = s.split() && leaving.split(), span = menus ? 160 : 320;
    if (menus) {
      drawArt(c, t);
      c.save();
      c.beginPath();
      c.rect(0, 0, 160, 240);
      c.clip();
    }
    [leaving, s].forEach((which, i) => {
      c.save();
      c.translate(i === 0 ? -slideDir * ease * span : slideDir * (1 - ease) * span, 0);
      if (!menus) { c.beginPath(); c.rect(0, 0, 320, 240); c.clip(); }
      if (!menus && which.split()) drawArt(c, t);
      drawScreen(c, which);
      c.restore();
    });
    if (menus) c.restore();
    return true;
  }

  function drawScreen(c, s) {
    if (s.type === NOW) return drawNowPlaying(c);
    const split = s.split(), w = split ? 160 : 320, inset = split ? 6 : 9, n = s.items.length;
    const bar = !split && n > ROWS, right = bar ? 311 : w;
    c.fillStyle = '#fff';
    c.fillRect(0, 0, w, 240);
    const arrows = s.type !== SONGS && s.type !== SETTINGS && s.type !== MSG;
    for (let i = s.top; i < Math.min(n, s.top + ROWS + 1); i++) {
      const y = 20 + 25 * (i - s.top), sel = i === s.sel, item = s.items[i];
      const value = s.type === SETTINGS ? settingValue(i) : null;
      const arrow = sel && arrows && item !== 'Shuffle Songs';
      c.save();
      c.translate(0, y);
      if (sel) {
        c.fillStyle = col(0xff9addf8); c.fillRect(0, 0, right, 1);
        c.fillStyle = G.selected; c.fillRect(0, 1, right, 20);
        c.fillStyle = col(0xff1d7bc1); c.fillRect(0, 21, right, 1);
        c.fillStyle = col(0xff154e83); c.fillRect(0, 22, right, 1);
      }
      const ink = sel ? 0xffffffff : 0xff000000;
      let room = right - inset - (arrow ? 17 : 6);
      if (value) room -= text(c, value, right - 7, 16, 60, 15, ink, true, 'right') + 6;
      text(c, item, inset, 16, room, 15, ink, true, 'left');
      if (arrow) {
        c.beginPath();
        c.moveTo(right - 11.4, 7.5);
        c.lineTo(right - 7.5, 11.5);
        c.lineTo(right - 11.4, 15.5);
        c.strokeStyle = '#fff';
        c.lineWidth = 2.9;
        c.lineCap = c.lineJoin = 'round';
        c.stroke();
      }
      c.restore();
    }
    if (!n) text(c, s.type === SONGS ? 'No Songs' : 'No Music', w / 2, 128, w - 20, 15, 0xff8a8a8a, true, 'center');
    if (bar) {
      c.fillStyle = col(0xfff1f1f1); c.fillRect(311, 20, 9, 220);
      c.fillStyle = col(0xffc4c4c4); c.fillRect(311, 20, 1, 220);
      const th = Math.max(18, 220 * ROWS / n), ty = 20 + (220 - th) * s.top / (n - ROWS);
      c.fillStyle = G.thumb;
      rr(c, 313.5, ty + 2, 5, th - 4, 2.5);
      c.fill();
    }
    header(c, w, s.title, inset);
  }

  function header(c, w, title, inset) {
    c.fillStyle = G.header; c.fillRect(0, 0, w, 19);
    c.fillStyle = col(0xff6a6a6a); c.fillRect(0, 19, w, 1);
    text(c, title, inset, 14, w - inset - 54, 12, 0xff000000, true, 'left');
    const nudge = w < 320 ? 1 : 0;
    c.save();
    c.translate(w - 29 - nudge, 0);
    c.fillStyle = G.batteryCase; c.fillRect(0, 3, 24, 12);
    c.fillStyle = col(0xff6f716d); c.fillRect(24, 7, 2, 4);
    c.fillStyle = G.batteryEmpty; c.fillRect(1, 4, 22, 10);
    c.fillStyle = battery > 20 ? G.battery : G.batteryLow; c.fillRect(1, 4, 22 * battery / 100, 10);
    c.restore();
    if (!current()) return;
    c.save();
    c.translate(w - 45 - nudge, 0);
    c.beginPath();
    if (P.playing) { c.moveTo(0.4, 3.3); c.lineTo(10.9, 8.8); c.lineTo(0.4, 14.3); c.closePath(); }
    else { c.rect(0.6, 3.9, 3.6, 9.8); c.rect(6.4, 3.9, 3.6, 9.8); }
    c.fillStyle = G.play;
    c.fill();
    c.strokeStyle = col(0xff16528a);
    c.lineWidth = 0.9;
    c.lineJoin = 'round';
    c.stroke();
    c.restore();
  }

  // ---- artwork

  const images = new Map();
  /** Returns the loaded cover for a song, or null while it is still on its way. */
  function cover(s, onReady) {
    let e = images.get(s.art);
    if (!e) {
      e = { img: new Image(), ok: false };
      e.img.decoding = 'async';
      e.img.onload = () => { e.ok = true; dirty = true; if (onReady) onReady(e.img); };
      e.img.src = s.art;
      images.set(s.art, e);
    }
    return e.ok ? e.img : null;
  }

  let noArt = null;
  function placeholder() {
    if (noArt) return noArt;
    noArt = canvas(300, 300);
    const c = noArt.getContext('2d');
    c.fillStyle = vert(c, 0, 300, [0xfff0f1f3, 0xffc5c9cf]);
    c.fillRect(0, 0, 300, 300);
    c.fillStyle = col(0xff9a9fa7);
    [[122, 205], [202, 185]].forEach(([x, y]) => {
      c.beginPath(); c.ellipse(x, y, 26, 17, -20 * Math.PI / 180, 0, Math.PI * 2); c.fill();
    });
    c.fillRect(138, 92, 9, 108);
    c.fillRect(218, 72, 9, 108);
    c.beginPath(); c.moveTo(138, 92); c.lineTo(227, 72); c.lineTo(227, 100); c.lineTo(138, 120); c.closePath(); c.fill();
    return noArt;
  }
  const side = img => Math.min(img.width || img.naturalWidth, img.height || img.naturalHeight);

  let slide = null, oldSlide = null, slideNext = 0, slideLoading = false;
  function drawArt(c, t) {
    if (!slideLoading && t >= slideNext && all.length) {
      slideLoading = true;
      const pick = all[Math.floor(Math.random() * all.length)];
      const ready = img => {
        slideLoading = false;
        slideNext = now() + 11000;
        const zin = Math.random() < 0.5, y0 = Math.random();
        oldSlide = slide;
        slide = { img, start: now(), z0: zin ? 1.12 : 1.34, z1: zin ? 1.34 : 1.12,
          x0: Math.random() * 0.5, x1: 0.5 + Math.random() * 0.5, y0, y1: 1 - y0 };
        dirty = true;
      };
      const img = cover(pick, ready);
      if (img) ready(img);
      setTimeout(() => { if (slideLoading) { slideLoading = false; slideNext = now() + 3000; } }, 8000);
    }
    c.save();
    c.beginPath();
    c.rect(160, 0, 160, 240);
    c.clip();
    if (!slide) c.drawImage(placeholder(), 120, 0, 240, 240);
    else {
      const fade = Math.min(1, (t - slide.start) / 1200);
      if (oldSlide && fade < 1) drawSlide(c, oldSlide, t, 1); else oldSlide = null;
      drawSlide(c, slide, t, fade);
    }
    c.fillStyle = G.pane;
    c.fillRect(160, 0, 11, 240);
    c.restore();
  }
  function drawSlide(c, s, t, alpha) {
    const p = Math.min(1, (t - s.start) / 14000), size = 240 * (s.z0 + (s.z1 - s.z0) * p);
    const x = 160 - (size - 160) * (s.x0 + (s.x1 - s.x0) * p), y = -(size - 240) * (s.y0 + (s.y1 - s.y0) * p);
    const n = side(s.img), sx = ((s.img.naturalWidth || s.img.width) - n) / 2, sy = ((s.img.naturalHeight || s.img.height) - n) / 2;
    c.globalAlpha = alpha;
    c.drawImage(s.img, sx, sy, n, n, x, y, size, size);
    c.globalAlpha = 1;
  }

  // ---- now playing

  function drawNowPlaying(c) {
    c.fillStyle = '#fff';
    c.fillRect(0, 0, 320, 240);
    const s = current();
    const img = (s && cover(s)) || lastCover || placeholder();
    if (s && cover(s)) lastCover = img;
    drawCover(c, img);
    header(c, 320, NOW_PLAYING, 9);
    if (!s) return;
    if (P.shuffled) shuffleIcon(c);
    if (P.repeat) repeatIcon(c, P.repeat === 1);
    text(c, s.title, 160, 71, 154, 16, 0xff000000, true);
    text(c, s.artist, 160, 90, 154, 14, 0xff333333, false);
    text(c, s.album, 160, 108, 154, 14, 0xff333333, false);
    text(c, `${P.index + 1} of ${P.queue.length}`, 160, 144, 154, 14, 0xff262626, false);

    const t = now();
    if (scrub && t >= scrubUntil) scrub = false;
    const vol = !scrub && t < volUntil, dur = duration(), pos = Math.min(position(), dur);
    const frac = Math.max(0, Math.min(1, vol ? volume : dur ? pos / dur : 0));
    const x0 = 58, x1 = 262, fx = x0 + (x1 - x0) * frac;
    c.fillStyle = col(0xffe1e1e1); c.fillRect(x0, 206, x1 - x0, 1);
    c.fillStyle = G.track; c.fillRect(x0, 207, x1 - x0, 12);
    c.fillStyle = col(0xffc5c5c5); c.fillRect(x0, 219, x1 - x0, 1);
    if (fx > x0 + 0.5) {
      c.fillStyle = col(0xff7e97d7); c.fillRect(x0, 206, fx - x0, 1);
      c.fillStyle = G.progress; c.fillRect(x0, 207, fx - x0, 12);
      c.fillStyle = col(0xff5c9ed0); c.fillRect(x0, 219, fx - x0, 1);
      c.fillStyle = col(0xff6796e4); c.fillRect(x0, 206, 1, 14);
      c.fillStyle = G.glow; c.fillRect(x0, 220, fx - x0, 9);
    }
    if (vol) { speaker(c, 36, false); speaker(c, 275, true); }
    else {
      text(c, time(pos), 47.5, 219, 50, 13, 0xff000000, true, 'right');
      text(c, '-' + time(dur - pos), 273.5, 219, 50, 13, 0xff000000, true, 'left');
    }
    if (scrub) {
      c.beginPath();
      c.moveTo(fx, 202.5); c.lineTo(fx + 7, 213); c.lineTo(fx, 223.5); c.lineTo(fx - 7, 213); c.closePath();
      c.fillStyle = G.diamond;
      c.fill();
      c.strokeStyle = '#fff';
      c.lineWidth = 1.2;
      c.lineJoin = 'miter';
      c.stroke();
    }
  }
  let lastCover = null;
  const time = ms => { const s = Math.floor(Math.max(0, ms) / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

  /** The cover stands at an angle with its mirror image fading below, drawn as thin vertical slices. */
  function drawCover(c, img) {
    const n = side(img), sx = ((img.naturalWidth || img.width) - n) / 2, sy = ((img.naturalHeight || img.height) - n) / 2;
    const N = 48, sw = n / N;
    for (let k = 0; k < N; k++) {
      const u0 = k / N, um = (k + 0.5) / N, x = 10 + 134.5 * u0, w = 134.5 / N + 0.6;
      const top = 34.5 + 9.8 * um, bot = 168.9 - 8.5 * um, h = bot - top;
      c.drawImage(img, sx + sw * k, sy, sw, n, x, top, w, h);
      c.save();
      c.translate(0, bot);
      c.scale(1, -1);
      c.drawImage(img, sx + sw * k, sy, sw, n, x, -h, w, h);
      c.restore();
    }
    // one fade over the whole reflection, starting at the cover's bottom edge
    c.beginPath();
    c.moveTo(10, 168.9); c.lineTo(144.5 + 0.6, 160.4); c.lineTo(145.1, 240); c.lineTo(10, 240); c.closePath();
    c.fillStyle = vert(c, 164.6, 164.6 + 0.24 * 125, [0xb0ffffff, 0xffffffff]);
    c.fill();
  }
  function lineStyle(c, w) {
    c.strokeStyle = col(0xff1e1e1e);
    c.lineWidth = w;
    c.lineCap = 'butt';
    c.lineJoin = 'round';
  }
  function arrowHead(c, x, y, dir) {
    c.beginPath();
    c.moveTo(x, y); c.lineTo(x - dir * 4.2, y - 3.3); c.lineTo(x - dir * 4.2, y + 3.3); c.closePath();
    c.fillStyle = col(0xff1e1e1e);
    c.fill();
  }
  function shuffleIcon(c) {
    c.beginPath();
    c.moveTo(268, 26.3); c.lineTo(273.2, 26.3); c.lineTo(279.3, 34); c.lineTo(284, 34);
    c.moveTo(268, 34); c.lineTo(273.2, 34); c.lineTo(279.3, 26.3); c.lineTo(284, 26.3);
    lineStyle(c, 2);
    c.stroke();
    arrowHead(c, 288, 26.3, 1);
    arrowHead(c, 288, 34, 1);
  }
  function repeatIcon(c, one) {
    c.beginPath();
    c.moveTo(294, 30.6); c.lineTo(294, 29.3); c.quadraticCurveTo(294, 26.3, 297, 26.3); c.lineTo(305, 26.3);
    c.moveTo(311.5, 29.7); c.lineTo(311.5, 31); c.quadraticCurveTo(311.5, 34, 308.5, 34); c.lineTo(300.5, 34);
    lineStyle(c, 2);
    c.stroke();
    arrowHead(c, 309, 26.3, 1);
    arrowHead(c, 296.5, 34, -1);
    if (one) text(c, '1', 302.8, 33.2, 10, 7.5, 0xff1e1e1e, true, 'center');
  }
  function speaker(c, x, loud) {
    c.beginPath();
    c.moveTo(x, 210); c.lineTo(x + 3.5, 210); c.lineTo(x + 8, 206); c.lineTo(x + 8, 220); c.lineTo(x + 3.5, 216); c.lineTo(x, 216);
    c.closePath();
    c.fillStyle = col(0xff3a3a3a);
    c.fill();
    if (!loud) return;
    c.strokeStyle = col(0xff3a3a3a);
    c.lineWidth = 1.4;
    c.lineCap = 'round';
    for (let i = 0; i < 3; i++) {
      c.beginPath();
      c.arc(x + 8, 213, 3.2 + i * 3, -42 * Math.PI / 180, 42 * Math.PI / 180);
      c.stroke();
    }
  }

  // ------------------------------------------------------------------ frame loop

  const finish = pickFinish();
  const ctx = el.getContext('2d');
  const footer = document.querySelector('footer');

  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 3);
    const w = Math.round(el.clientWidth * dpr), h = Math.round(el.clientHeight * dpr);
    if (!w || !h) return;
    el.width = w;
    el.height = h;
    hw = renderHardware(w, h, (footer ? footer.offsetHeight + 4 : 0) * dpr, finish);
    G = gradients(ctx);
    dirty = true;
  }

  let lastFrame = 0;
  function frame(t) {
    requestAnimationFrame(frame);
    if (!hw) return;
    const s = cur();
    const animating = !!leaving;
    const every = animating ? 0 : s.split() ? 100                    // drifting artwork: moves < 1 px a frame at 10 fps
      : s.type === NOW && t < Math.max(volUntil, scrubUntil) ? 100
      : s.type === NOW && P.playing ? 1005 - position() % 1000              // when the clock ticks over
      : Infinity;
    if (!dirty && t - lastFrame < every) return;
    dirty = false;
    lastFrame = t;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.drawImage(hw.img, 0, 0);
    const L = hw.lcd;
    ctx.save();
    ctx.beginPath();
    ctx.rect(L.x, L.y, L.w, L.h);
    ctx.clip();
    ctx.translate(L.x, L.y);
    ctx.scale(L.w / 320, L.h / 240);
    ctx.imageSmoothingQuality = 'high';
    drawScreens(ctx, t);
    ctx.restore();
    // reflection on the glass
    rr(ctx, hw.win.x, hw.win.y, hw.win.w, hw.win.h, hw.u);
    ctx.fillStyle = lin(ctx, hw.win.x, hw.win.y, hw.win.x + hw.win.w * 0.55, hw.win.y + hw.win.h * 0.95,
      [0x26ffffff, 0x10ffffff, 0x00ffffff], [0, 0.45, 1]);
    ctx.fill();
    // the part of the wheel under the thumb dips
    const { cx, cy, R, r, f } = hw;
    if (onCenter) {
      disc(ctx, cx, cy, r, radial(ctx, cx, cy, r, f.dark ? [0x22ffffff, 0x10ffffff, 0x24ffffff] : [0x2e000000, 0x16000000, 0x34000000], [0, 0.8, 1]));
    } else if (onWheel && !moved && sector >= 0) {
      const ox = [0, 1, 0, -1][sector], oy = [-1, 0, 1, 0][sector];
      const px = cx + ox * R * 0.72, py = cy + oy * R * 0.72;
      ctx.save();
      ctx.beginPath();
      ctx.arc(cx, cy, R, 0, Math.PI * 2);
      ctx.arc(cx, cy, r, 0, Math.PI * 2, true);
      ctx.clip();
      disc(ctx, px, py, R * 0.75, radial(ctx, px, py, R * 0.75,
        f.dark ? [0x26ffffff, 0x10ffffff, 0x00ffffff] : [0x30000000, 0x14000000, 0x00000000], [0, 0.5, 1]));
      ctx.restore();
    }
  }

  // ------------------------------------------------------------------ start up

  async function load() {
    stack = [new Screen(MSG, 'iPod', ['Loading…'])];
    dirty = true;
    try {
      const r = await fetch('top.json', { cache: 'no-cache' });
      if (!r.ok) throw new Error(r.status);
      all = (await r.json()).songs.filter(s => s.preview);
      if (!all.length) throw new Error('empty');
      stack = [new Screen(MAIN, 'iPod', ['Music', 'Settings', 'Shuffle Songs'])];
    } catch (e) {
      stack = [new Screen(MSG, 'iPod', ["Couldn't Load Songs", 'Try Again'])];
      stack[0].sel = 1;
    }
    dirty = true;
  }

  if (navigator.getBattery) {
    navigator.getBattery().then(b => {
      const set = () => { battery = Math.round(b.level * 100) || 100; dirty = true; };
      set();
      b.addEventListener('levelchange', set);
    }).catch(() => {});
  }
  window.addEventListener('resize', resize);
  document.addEventListener('visibilitychange', () => { dirty = true; });
  (document.fonts ? Promise.all([document.fonts.load('700 15px Heros'), document.fonts.load('400 15px Heros')]).catch(() => {}) : Promise.resolve())
    .then(() => { resize(); requestAnimationFrame(frame); el.focus(); });
  load();

  // read-only peek for automated tests
  window.podDebug = () => ({ cx: hw.cx / dpr, cy: hw.cy / dpr, R: hw.R / dpr, r: hw.r / dpr, playing: P.playing,
    index: P.index, queue: P.queue.length, song: current() && current().title, time: audio.currentTime,
    screen: cur().title, sel: cur().sel, items: cur().items.length, songs: all.length, volume });
})();
