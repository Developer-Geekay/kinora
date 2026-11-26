'use strict';
/* Kinora front end. Sections: helpers, settings, data, router, player, captions,
   popovers/dialogs, cards, queue sidebar, pages, boot. */
(() => { // function scope, so names like `history` don't collide with window globals

/* ================= helpers ================= */
const $ = s => document.querySelector(s);
const SVGNS = 'http://www.w3.org/2000/svg';
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const icon = (name, cls = '') => { const s = document.createElementNS(SVGNS, 'svg'); s.setAttribute('class', 'i ' + cls); s.setAttribute('aria-hidden', 'true'); const u = document.createElementNS(SVGNS, 'use'); u.setAttribute('href', '#i-' + name); s.append(u); return s; };
const button = (cls, label, iconName, fn) => { const b = el('button', cls); b.type = 'button'; if (iconName) b.append(icon(iconName)); if (label) b.append(document.createTextNode(label)); if (fn) b.onclick = fn; return b; };
const iconBtn = (iconName, label, fn, cls = 'ib') => { const b = el('button', cls); b.type = 'button'; b.setAttribute('aria-label', label); b.title = label; b.append(icon(iconName)); b.onclick = fn; return b; };
const store = {
  get(k, d) { try { const x = JSON.parse(localStorage.getItem('lt.' + k)); return x ?? d; } catch { return d; } },
  set(k, val) { try { localStorage.setItem('lt.' + k, JSON.stringify(val)); } catch {} },
};

const v = $('#v'), stage = $('#stage'), seek = $('#seek'), seekWrap = $('#seekWrap'), watchEl = $('#watch'), pageEl = $('#page');

function fmt(s) {
  if (!s || !isFinite(s)) return '0:00';
  s = Math.floor(s);
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
  return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(sec).padStart(2, '0');
}
const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });
const fmtViews = n => (n == null ? '' : `${compact.format(n)} view${n === 1 ? '' : 's'}`);
function fmtDate(ymd) {
  if (!/^\d{8}$/.test(ymd || '')) return '';
  return new Date(+ymd.slice(0, 4), +ymd.slice(4, 6) - 1, +ymd.slice(6)).toLocaleDateString('en', { year: 'numeric', month: 'short', day: 'numeric' });
}
function dayLabel(ts) {
  const d = new Date(ts), t = new Date(), sod = x => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((sod(t) - sod(d)) / 864e5);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  if (diff < 7) return d.toLocaleDateString('en', { weekday: 'long' });
  return d.toLocaleDateString('en', { month: 'short', day: 'numeric', year: d.getFullYear() === t.getFullYear() ? undefined : 'numeric' });
}
function videoIdFrom(text) {
  const m = text.match(/(?:v=|youtu\.be\/|shorts\/|embed\/|live\/)([\w-]{11})/);
  return m ? m[1] : (/^[\w-]{11}$/.test(text) ? text : null);
}
const thumbOf = id => `https://i.ytimg.com/vi/${id}/mqdefault.jpg`;
const slim = it => ({ id: it.id, title: it.title || it.id, channel: it.channel || '', duration: it.duration || 0 });

/* ================= settings: global defaults + per-player overrides ================= */
const HEIGHTS = [360, 480, 720, 1080, 1440, 2160];
const qLabel = h => (h === 2160 ? '4K' : h + 'p');
const RATES = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const speedLabel = r => (r === 1 ? 'Normal' : `${r}×`);
const DEFAULTS = { theme: 'system', stream: 'video', quality: 720, view: 'normal', autoplay: true, subs: false, history: true };
let settings = { ...DEFAULTS, ...store.get('settings', {}) };
let ov = {};                       // overrides for this player session (cleared by Reset or a page reload)
let rate = store.get('rate', 1);
const eff = k => ov[k] ?? settings[k];
const mode = () => eff('stream');
const mq = matchMedia('(prefers-color-scheme: light)');
function applyTheme() {
  const t = settings.theme;
  document.documentElement.dataset.theme = t === 'dark' || (t === 'system' && !mq.matches) ? 'dark' : 'light';
}
mq.addEventListener?.('change', applyTheme);

const snap = () => ({ stream: eff('stream'), quality: eff('quality') });
function changed(before) {
  applyUI();
  const now = snap();
  if (current && (before.stream !== now.stream || (now.stream === 'video' && before.quality !== now.quality))) startItem(current, pos(), !v.paused);
  refreshPop();
}
function setGlobal(key, val) {
  const before = snap();
  settings[key] = val; store.set('settings', settings);
  if (key === 'theme') applyTheme();
  changed(before);
}
function setOv(key, val) {
  const before = snap();
  if (val === settings[key]) delete ov[key]; else ov[key] = val;
  changed(before);
}
function resetOv() { const before = snap(); ov = {}; changed(before); toast('Back to your default settings'); }

/* ================= data ================= */
// Video mode streams from a pipe, so the browser can't seek it. We remember the start offset
// we asked for and add it to v.currentTime; seeking reloads with ?t=.
let current = null, offset = 0, duration = 0, dragging = false, token = 0, hideTimer = 0, lastSave = 0, histLogged = false;
let queue = { items: [], i: -1, source: null };    // the playing queue; source = { type: 'playlist', id, name } | null
let playlists = store.get('playlists', []);
let posMap = store.get('pos', {});                 // resume positions
let recsSince = store.get('recsSince', 0);         // Home ignores what you watched before this moment (Settings → Reset recommendations)
let history = store.get('history', null);
if (!history) { // migrate the old "Recent" list
  const old = store.get('recent', []);
  history = old.map((r, i) => ({ ...slim(r), at: Date.now() - i * 60000, done: false }));
  store.set('history', history);
}
const itemCache = new Map();                       // id -> slim item, so a video opens instantly with its title
const stash = it => { itemCache.set(it.id, { ...(itemCache.get(it.id) || {}), ...slim(it) }); return it; };
history.forEach(stash); playlists.forEach(p => p.items.forEach(stash));

// Favorites: a plain list of videos kept in localStorage (Playlists cover everything else)
let favs = store.get('favs', []);
favs.forEach(stash);
const saved = {
  favs: { name: 'Favorites', get: () => favs, set: a => { favs = a; store.set('favs', a); }, empty: 'Tap ⋮ on any video and choose “Favorite” to keep it here.' },
};
const isSaved = (kind, id) => saved[kind].get().some(x => x.id === id);
function toggleSaved(kind, item) {
  const L = saved[kind], had = isSaved(kind, item.id);
  L.set(had ? L.get().filter(x => x.id !== item.id) : [slim(item), ...L.get()]);
  toast(had ? `Removed from ${L.name}` : `Added to ${L.name}`, { label: 'Undo', fn: () => { toggleSaved(kind, item); } });
  if (refreshFn) refreshFn();
}

let pendingAt = null; // while a video seek is loading: where we are heading, so the clock/bar show it and quick repeated seeks add up
const pos = () => pendingAt ?? offset + (v.currentTime || 0);
const savePl = () => store.set('playlists', playlists);
const plById = id => playlists.find(p => p.id === id);
const histOf = id => history.find(h => h.id === id);
function progressOf(id, dur) {
  if (histOf(id)?.done) return 100;
  const p = posMap[id];
  return p && dur ? Math.min(100, p / dur * 100) : 0;
}

function recordHistory(item) {
  if (!settings.history) return;
  history = [{ ...slim(item), at: Date.now(), done: false }, ...history.filter(h => h.id !== item.id)].slice(0, 500);
  store.set('history', history);
}
function markDone(id) { const h = histOf(id); if (h) { h.done = true; store.set('history', history); } }
// started and not finished, newest first
const continueWatching = () => history.filter(h => !h.done && posMap[h.id] > 15 && h.duration);
const continueIds = () => continueWatching().map(h => h.id);
// off "Continue watching": where you stopped is forgotten, the history entry stays
function forgetPositions(ids, message) {
  const old = ids.map(id => [id, posMap[id]]);
  for (const id of ids) delete posMap[id];
  store.set('pos', posMap); refreshView();
  toast(message, { label: 'Undo', fn: () => { for (const [id, p] of old) posMap[id] = p; store.set('pos', posMap); refreshView(); } });
}

/* ================= snackbar + ripple ================= */
let toastTimer = 0;
function toast(msg, action, ms = 5000) {
  $('#toastMsg').textContent = msg;
  const b = $('#toastAct');
  b.hidden = !action;
  if (action) { b.textContent = action.label; b.onclick = () => { action.fn(); $('#toast').classList.remove('show'); }; }
  $('#toast').classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('#toast').classList.remove('show'), ms);
}
document.addEventListener('pointerdown', e => {
  const t = e.target.closest('button, .card, .menuitem, .pressable');
  if (!t || t.disabled || t.classList.contains('switch') || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  const r = t.getBoundingClientRect(), size = Math.max(r.width, r.height) * 2.2;
  const w = el('span', 'wave');
  w.style.cssText = `width:${size}px;height:${size}px;left:${e.clientX - r.left - size / 2}px;top:${e.clientY - r.top - size / 2}px`;
  t.append(w);
  w.addEventListener('animationend', () => w.remove());
});

/* ================= router ================= */
const curRoute = () => document.body.dataset.route;
function parseHash() {
  const [path, qs = ''] = (location.hash.slice(1) || '/').split('?');
  return { path, p: new URLSearchParams(qs), parts: path.split('/').filter(Boolean) };
}
function nav(path, { replace = false } = {}) {
  const h = '#' + path;
  if (location.hash === h) { route(); return; }
  if (replace) location.replace(h); else location.hash = h;
}
const watchPath = id => `/watch?v=${id}` + (queue.source?.type === 'playlist' ? `&list=${queue.source.id}` : '');
function openVideo(item) { stash(item); nav(`/watch?v=${item.id}`); }
addEventListener('hashchange', route);

/* phone side menu: opened by the bottom bar's More; closes on scrim, Escape, a left swipe or navigation */
const drawerEl = $('#drawer'), scrimEl = $('#scrim');
function openDrawer() {
  scrimEl.hidden = false; drawerEl.setAttribute('aria-hidden', 'false'); $('#moreBtn').setAttribute('aria-expanded', 'true');
  requestAnimationFrame(() => { scrimEl.classList.add('open'); drawerEl.classList.add('open'); });
  const first = drawerEl.querySelector('.di.on') || drawerEl.querySelector('.di'); if (first) first.focus({ preventScroll: true });
}
function closeDrawer() {
  if (!drawerEl.classList.contains('open')) return;
  drawerEl.classList.remove('open'); scrimEl.classList.remove('open'); drawerEl.setAttribute('aria-hidden', 'true'); $('#moreBtn').setAttribute('aria-expanded', 'false');
  setTimeout(() => { if (!drawerEl.classList.contains('open')) scrimEl.hidden = true; }, 260);
}
$('#moreBtn').addEventListener('click', openDrawer);
scrimEl.addEventListener('click', closeDrawer);
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeDrawer(); });
{ let x0 = null; drawerEl.addEventListener('touchstart', e => { x0 = e.touches[0].clientX; }, { passive: true });
  drawerEl.addEventListener('touchend', e => { if (x0 !== null && e.changedTouches[0].clientX - x0 < -60) closeDrawer(); x0 = null; }, { passive: true }); }
matchMedia('(min-width: 801px)').addEventListener('change', e => { if (e.matches) closeDrawer(); });

function route() {
  const { parts, p } = parseHash();
  const name = parts[0] || 'home';
  closePop();
  if (name !== 'shorts') closeShortsFeed();
  if (name !== 'network') closeLocalPlayer();
  document.body.dataset.route = ['home', 'results', 'watch', 'history', 'playlists', 'playlist', 'shorts', 'music', 'favorites', 'settings', 'network'].includes(name) ? name : 'home';
  $('#navHistory').classList.toggle('active', name === 'history');
  document.querySelectorAll('#rail .ri[data-r], #drawer .di[data-r]').forEach(a => a.classList.toggle('on', a.dataset.r === (name === 'playlist' ? 'playlists' : document.body.dataset.route)));
  closeDrawer();
  $('#navPlaylists').classList.toggle('active', name === 'playlists' || name === 'playlist');
  updateMini();
  refreshFn = null;
  if (name === 'watch') return enterWatch(p);
  window.scrollTo(0, 0);
  if (name === 'results') return pageEl.replaceChildren(viewResults((p.get('q') || '').trim()));
  if (name === 'history') { document.title = 'History · Kinora'; return pageEl.replaceChildren(viewHistory()); }
  if (name === 'settings') { document.title = 'Settings · Kinora'; return pageEl.replaceChildren(viewSettings()); }
  if (name === 'network') { document.title = 'Network · Kinora'; return pageEl.replaceChildren(viewNetwork()); }
  if (name === 'shorts') {
    document.title = 'Shorts · Kinora';
    pageEl.replaceChildren(viewShorts());
    const id = p.get('v');
    if (id) openShortsFeed(id); else closeShortsFeed();
    return;
  }
  if (name === 'music') { document.title = 'Music · Kinora'; return pageEl.replaceChildren(viewMusic()); }
  if (name === 'favorites') { document.title = 'Favorites · Kinora'; return pageEl.replaceChildren(viewSaved('favs')); }
  if (name === 'playlists') { document.title = 'Playlists · Kinora'; return pageEl.replaceChildren(viewPlaylists()); }
  if (name === 'playlist') { document.title = 'Playlist · Kinora'; return pageEl.replaceChildren(viewPlaylist(parts[1])); }
  document.title = 'Kinora';
  pageEl.replaceChildren(viewHome());
}

function enterWatch(p) {
  const id = p.get('v');
  if (!id || !/^[\w-]{11}$/.test(id)) return nav('/', { replace: true });
  if (current?.id !== id) {
    const list = p.get('list'), pl = list && plById(list);
    const at = pl ? pl.items.findIndex(x => x.id === id) : -1;
    if (at >= 0) startList(pl.items, at, { type: 'playlist', id: pl.id, name: pl.name }, { show: false });
    else startList([itemCache.get(id) || { id }], 0, null, { show: false });
  }
  if (current) document.title = `${current.title || 'Kinora'} · Kinora`;
  renderWatch();
}

/* ================= player core ================= */
function updateMini() {
  document.body.dataset.mini = current && curRoute() !== 'watch' ? 'on' : 'off';
  $('#miniTitle').textContent = current?.title || '';
  stage.querySelector('[data-c="title"]').textContent = current?.title || '';
  stage.querySelector('[data-c="sub"]').textContent = current?.channel || '';
}
function setState(s, msg) {
  stage.dataset.state = s; document.body.dataset.state = s;
  if (s === 'error') $('#errMsg').textContent = msg || 'Something went wrong.';
  const glyph = s === 'playing' ? 'pause' : 'play';
  for (const b of [$('#pp'), $('#midPP'), $('#miniPP')]) setIcon(b, glyph);
  $('#pp').setAttribute('aria-label', s === 'playing' ? 'Pause (space)' : 'Play (space)');
  if ('mediaSession' in navigator) navigator.mediaSession.playbackState = s === 'playing' ? 'playing' : 'paused';
  wake();
}
const wake = () => ui.wake(); // the controls come back, and hide again by themselves while playing

let loadSeq = 0;
async function load(start, play = true) {
  const seq = ++loadSeq, audio = mode() === 'audio', id = current.id;
  v.preload = 'auto';
  let at = 0;
  if (!audio && start > 0) {
    // Video is copied rather than re-encoded, so it can only start on a keyframe, which is a few seconds before
    // the time asked for. The server finds that keyframe and starts audio and video together there; we use its
    // real time as the stream's start so the clock, seek bar and subtitles match what is on screen.
    if (play) { setState('loading'); v.pause(); }
    try {
      const r = await fetch(`/api/start/${id}?q=${eff('quality')}&t=${start}`);
      at = (await r.json()).start;
    } catch { at = start; }
    if (seq !== loadSeq || current?.id !== id) return; // a newer seek or video took over
  }
  offset = at; pendingAt = null;
  if (audio || !IOS) v.src = `/stream/${id}?mode=${mode()}&q=${eff('quality')}` + (at ? `&t=${at}` : '');
  else {
    if (play) setState('loading');
    let u;
    try { u = await videoUrl(id, eff('quality'), at); }
    catch (e) { if (seq === loadSeq && current?.id === id) setState('error', `Couldn’t prepare the video for this device (${e.message}).`); return; }
    if (seq !== loadSeq || current?.id !== id) return;
    v.src = u;
    // Safari gives no error when it just never starts: say what state it is stuck in
    setTimeout(() => { if (seq === loadSeq && current?.id === id && stage.dataset.state === 'loading' && v.readyState < 3) setState('error', `Safari did not start the video (readyState ${v.readyState}, network ${v.networkState}${v.error ? ', error ' + v.error.code : ''}).`); }, 20000);
  }
  v.defaultPlaybackRate = rate; v.playbackRate = rate;
  if (audio && start) v.addEventListener('loadedmetadata', () => { v.currentTime = start; }, { once: true });
  if (play) { setState('loading'); v.play().catch(() => setState('paused')); } else setState('paused'); // a seek while paused stays paused
}

// iPhone / iPad: Safari can't play the piped MP4 (no length, no byte ranges), so video comes as HLS from /api/hls.
const IOS = /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
async function videoUrl(id, q, at = 0) {
  const plain = `/stream/${id}?mode=video&q=${q}` + (at ? `&t=${at}` : '');
  if (!IOS) return plain;
  const r = await fetch(`/api/hls/${id}?q=${q}` + (at ? `&t=${at}` : ''));
  if (!r.ok) throw new Error((await r.text().catch(() => '')).slice(0, 200) || `HTTP ${r.status}`);
  const j = await r.json();
  return j.url || plain; // no url = a live stream, which already comes as HLS
}

// Pre-resolve the next queue item (the slow yt-dlp step) shortly before the current one ends, so it starts at once.
let prefetched = '';
function prefetchNext() {
  const n = queue.items[queue.i + 1];
  if (!n || !duration || prefetched === n.id || (duration - pos() > 90 && pos() < duration * .5)) return;
  prefetched = n.id;
  fetch(`/api/info/${n.id}?mode=${mode()}&q=${eff('quality')}`).catch(() => {});
}

// Play queue.items[i]. From the watch page the address follows along (replace, so Back isn't polluted).
function playIndex(i, start = null) {
  queue.i = i;
  startItem(queue.items[i], start);
  if (curRoute() === 'watch') nav(watchPath(current.id), { replace: true });
}
function startList(items, i, source = null, { show = true } = {}) {
  queue = { items: items.map(slim), i, source };
  items.forEach(stash);
  playIndex(i);
  if (show && curRoute() !== 'watch') nav(watchPath(current.id));
}

async function startItem(item, start = null, play = true) {
  if (!item) return;
  const my = ++token;
  current = { ...item };
  histLogged = false; prefetched = ''; pendingAt = null;
  v.removeAttribute('src'); v.load();
  v.poster = thumbOf(item.id);
  $('#artImg').src = thumbOf(item.id);
  $('#artBg').style.backgroundImage = `url(${thumbOf(item.id)})`;
  duration = item.duration || 0; seek.max = duration; offset = 0; seek.disabled = false;
  resetCaptions([]);
  setState('loading');
  $('#loadmsg').textContent = 'Finding the stream…';
  renderWatch(); render(); updateMini();
  if (curRoute() === 'watch') document.title = `${item.title || 'Kinora'} · Kinora`;
  try {
    const r = await fetch(`/api/info/${item.id}?mode=${mode()}&q=${eff('quality')}`);
    const info = await r.json();
    if (my !== token) return;
    if (!r.ok) throw new Error(info.error || r.statusText);
    duration = info.duration || duration; seek.max = duration;
    if (info.live) { duration = 0; seek.max = 0; seek.disabled = true; } // a live stream has no length and nothing to seek in
    current = { ...item, title: info.title || item.title, channel: info.channel || item.channel, duration,
      views: info.views, uploaded: info.uploaded, description: info.description, live: !!info.live, related: item.related };
    stash(current);
    if (queue.items[queue.i]?.id === item.id) queue.items[queue.i] = slim(current);
    if (curRoute() === 'watch') document.title = `${current.title} · Kinora`;
    const h = histOf(item.id); if (h) { h.title = current.title; h.channel = current.channel; h.duration = duration; }
    setSession(); renderWatch(); updateMini();
    resetCaptions(info.subs || []);
    loadRelated(item.id);

    let from = start;
    if (from === null) {
      const saved = posMap[item.id];
      if (!current.live && saved && saved > 15 && saved < duration - 20) {
        from = saved;
        toast(`Resumed at ${fmt(saved)}`, { label: 'Start over', fn: () => seekTo(0) });
      } else from = 0;
    }
    load(from, play);
  } catch (e) { if (my === token) setState('error', e.message); }
}

function stopPlayback() {
  ++token; v.removeAttribute('src'); v.load();
  current = null; queue = { items: [], i: -1, source: null };
  resetCaptions([]);
  setState('idle'); document.title = 'Kinora';
  renderWatch(); updateMini();
}

function seekTo(t) {
  if (!current) return;
  if (current.live) return toast('A live stream can\'t be seeked');
  t = Math.max(0, Math.min(t, Math.max(duration - 1, 0)));
  if (mode() === 'audio') v.currentTime = t;
  else { pendingAt = t; load(t, !v.paused || stage.dataset.state === 'loading'); }
  render();
}
function next() { if (queue.i + 1 < queue.items.length) playIndex(queue.i + 1); else toast('End of the queue'); }
function prev() { if (pos() > 5 || queue.i <= 0) seekTo(0); else playIndex(queue.i - 1); }

function render() {
  const p = pos(), shown = dragging ? Number(seek.value) : p;
  if (!dragging) seek.value = p;
  let b = 0;
  try { for (let i = 0; i < v.buffered.length; i++) if (v.buffered.start(i) <= v.currentTime + .5) b = Math.max(b, v.buffered.end(i)); } catch {}
  const pct = x => (duration ? Math.min(100, x / duration * 100) : 0) + '%';
  seekWrap.style.setProperty('--p', current?.live ? '100%' : pct(shown));
  seekWrap.style.setProperty('--b', pct(offset + b));
  showTime($('#time'), current?.live ? null : shown, duration);
  updateCaps();
}
function savePos(force) {
  if (!current || !duration) return;
  const now = Date.now();
  if (!force && now - lastSave < 4000) return;
  lastSave = now;
  const p = pos();
  if (p > 15 && p < duration - 20) posMap[current.id] = Math.floor(p); else delete posMap[current.id];
  store.set('pos', posMap);
}

v.addEventListener('timeupdate', () => { render(); savePos(); prefetchNext(); });
v.addEventListener('progress', render);
v.addEventListener('playing', () => {
  setState('playing');
  if (!histLogged && current) { histLogged = true; recordHistory(current); }
});
v.addEventListener('waiting', () => { if (current && stage.dataset.state !== 'error') { setState('loading'); $('#loadmsg').textContent = 'Buffering…'; } });
v.addEventListener('pause', () => { if (!v.ended && stage.dataset.state === 'playing') setState('paused'); });
v.addEventListener('error', () => { if (v.getAttribute('src')) setState('error', 'The stream stopped. The link may have expired or the video may be unavailable.' + (v.error ? ` (player error ${v.error.code}${v.error.message ? ': ' + v.error.message : ''}${IOS ? ', HLS' : ''})` : '')); });
v.addEventListener('ended', () => {
  if (current) { delete posMap[current.id]; store.set('pos', posMap); markDone(current.id); }
  if (eff('autoplay') && queue.i + 1 < queue.items.length) next(); else setState('paused');
  renderSide();
});
addEventListener('beforeunload', () => savePos(true));

/* ================= controls ================= */
const toggle = () => { if (!current) return; v.paused ? v.play().catch(() => {}) : v.pause(); };
const ui = playerChrome(stage, v, {
  toggle,
  skip: d => {
    if (!current) return false;
    if (current.live || !duration) { toast('This can\'t be seeked'); return false; }
    seekTo(pos() + d); return true;
  },
});
$('#pp').onclick = $('#midPP').onclick = ui.toggle; $('#miniPP').onclick = toggle;
$('#nextBtn').onclick = $('#midNext').onclick = next; $('#prevBtn').onclick = $('#midPrev').onclick = prev;
$('#retry').onclick = () => current && startItem(current, pos() || null);
$('#errNext').onclick = next;
$('#miniExpand').onclick = () => current && nav(watchPath(current.id));
$('#miniClose').onclick = stopPlayback;
$('#fs').onclick = () => toggleFullscreen(stage, v);
$('#thBtn').onclick = () => setOv('view', eff('view') === 'theater' ? 'normal' : 'theater');
document.addEventListener('fullscreenchange', () => {
  closePop();
  const fsOn = !!document.fullscreenElement;
  for (const b of $$fs()) setIcon(b, fsOn ? 'fullexit' : 'full');
  $('#thBtn').hidden = fsOn;
  wake();
});
const $$fs = () => document.querySelectorAll('[data-c="fs"]');

seek.addEventListener('input', () => { dragging = true; render(); });
seek.addEventListener('change', () => { dragging = false; seekTo(Number(seek.value)); });
seekWrap.addEventListener('pointermove', e => {
  const r = seekWrap.getBoundingClientRect(), f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
  $('#tip').textContent = fmt(f * duration);
  $('#tip').style.left = Math.min(r.width - 24, Math.max(24, f * r.width)) + 'px';
});
function setVolume(x, persist = true) {
  v.volume = x; v.muted = x === 0; $('#vol').value = x;
  $('#vi').firstChild.setAttribute('href', `#i-${x === 0 ? 'mute' : 'vol'}`);
  if (persist) store.set('vol', x);
}
$('#vol').addEventListener('input', e => setVolume(Number(e.target.value)));
let preMute = 1;
$('#mute').onclick = () => { if (v.volume > 0) { preMute = v.volume; setVolume(0); } else setVolume(preMute || 1); };
setVolume(store.get('vol', 1), false);
v.playbackRate = rate;
function setRate(r) { rate = r; v.playbackRate = r; v.defaultPlaybackRate = r; store.set('rate', r); refreshPop(); }

/* ----- what the two web players (YouTube here, files under Network) share: the design is the one the apps use too ----- */
const touchUI = matchMedia('(pointer: coarse), (max-width: 600px)');
function setIcon(button, name) { button.querySelector('use').setAttribute('href', '#i-' + name); }
function showTime(node, at, length) { // at = null: a live stream
  node.classList.toggle('live', at === null);
  node.textContent = at === null ? 'LIVE' : `${fmt(at)} / ${fmt(length)}`;
}
function toggleFullscreen(root, vid) {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  else if (root.requestFullscreen) root.requestFullscreen().catch(() => {});
  else vid.webkitEnterFullscreen?.(); // iPhone: only the video itself can go full screen
}
// on a touch screen captions and settings sit at the top right, as in the YouTube app
function layoutChrome(root) {
  const right = root.querySelector('.cright');
  if (touchUI.matches) root.querySelector('[data-c="topend"]').append(right);
  else root.querySelector('[data-c="list"]').before(right);
}
touchUI.addEventListener?.('change', () => document.querySelectorAll('.stage').forEach(layoutChrome));
layoutChrome(stage);

// Controls that hide by themselves, tap / double-tap gestures, the play/pause flash and the "10 seconds" ripple.
// `skip(seconds)` seeks and returns whether it could; `busy()` = a menu is open, so the controls must stay.
function playerChrome(root, vid, { toggle, skip, busy = () => !pop.hidden }) {
  const flash = el('div', 'bezel'), ripple = el('div', 'skipfx');
  root.append(flash, ripple);
  let hideT = 0, tapT = 0, tapSide = 0, single = 0, total = 0, rippleT = 0;
  const playing = () => root.dataset.state === 'playing';
  const hide = () => { if (playing() && !busy()) root.classList.add('ui-hidden'); };
  const wake = () => {
    root.classList.remove('ui-hidden'); clearTimeout(hideT);
    if (playing()) hideT = setTimeout(hide, 2800);
  };
  const flashToggle = () => {
    if (!vid.getAttribute('src')) return toggle();
    flash.replaceChildren(icon(vid.paused ? 'play' : 'pause'));
    toggle();
    flash.classList.remove('go'); void flash.offsetWidth; flash.classList.add('go');
  };
  const seekBy = d => {
    if (!skip(d)) return;
    total = ripple.classList.contains('on') && Math.sign(total) === Math.sign(d) ? total + d : d;
    ripple.className = 'skipfx on ' + (d < 0 ? 'left' : 'right');
    ripple.replaceChildren(el('b', '', d < 0 ? '‹‹‹' : '›››'), el('span', '', `${Math.abs(total)} seconds`));
    clearTimeout(rippleT); rippleT = setTimeout(() => ripple.classList.remove('on'), 700);
  };
  root.addEventListener('pointermove', e => { if (e.pointerType === 'mouse') wake(); });
  root.addEventListener('pointerleave', e => { if (e.pointerType === 'mouse') hide(); });
  root.addEventListener('pointerup', e => { if (e.target.closest('.chrome')) wake(); });
  root.addEventListener('click', e => {
    if (e.target.closest('button, input, .errorbox, .minibar, .lpanel, .pop')) return;
    if (!pop.hidden) return closePop();
    if (!touchUI.matches) return flashToggle();
    // touch: a tap shows or hides the controls; two quick taps on a side skip 10 seconds, and each further tap 10 more
    const r = root.getBoundingClientRect(), x = (e.clientX - r.left) / r.width, side = x < .35 ? -1 : x > .65 ? 1 : 0, now = performance.now();
    const again = side && side === tapSide && now - tapT < 350;
    tapT = now; tapSide = side; clearTimeout(single);
    if (again) return seekBy(side * 10);
    single = setTimeout(() => (root.classList.contains('ui-hidden') || !playing() ? wake() : hide()), side ? 260 : 0);
  });
  root.addEventListener('dblclick', e => {
    if (touchUI.matches || e.target.closest('button, input, .lpanel') || (root === stage && curRoute() !== 'watch')) return;
    toggleFullscreen(root, vid);
  });
  return { wake, seekBy, toggle: flashToggle, stop: () => { clearTimeout(hideT); clearTimeout(single); clearTimeout(rippleT); } };
}

function setSession() {
  if (!('mediaSession' in navigator) || !current) return;
  navigator.mediaSession.metadata = new MediaMetadata({ title: current.title || '', artist: current.channel || '', artwork: [{ src: thumbOf(current.id), sizes: '320x180' }] });
  for (const [a, f] of [['play', toggle], ['pause', toggle], ['nexttrack', next], ['previoustrack', prev], ['seekbackward', () => seekTo(pos() - 10)], ['seekforward', () => seekTo(pos() + 10)]]) {
    try { navigator.mediaSession.setActionHandler(a, f); } catch {}
  }
}

document.addEventListener('keydown', e => {
  if (dlg.open) return;
  const typing = e.target.matches('input[type=text], input[type=search]');
  if (e.key === '/' && !typing) { e.preventDefault(); $('#q').focus(); return; }
  if (e.key === 'Escape' && typing) { e.target.blur(); return; }
  if (typing || e.ctrlKey || e.metaKey || e.altKey || !current || lplayer) return;
  const k = e.key.toLowerCase();
  if (k === ' ' || k === 'k') { if (e.target.matches('button, input, a')) return; e.preventDefault(); ui.toggle(); wake(); }
  else if (k === 'j') ui.seekBy(-10);
  else if (k === 'l') ui.seekBy(10);
  else if (e.key === 'ArrowLeft') ui.seekBy(-5);
  else if (e.key === 'ArrowRight') ui.seekBy(5);
  else if (e.key === 'ArrowUp') { e.preventDefault(); setVolume(Math.min(1, v.volume + .05)); }
  else if (e.key === 'ArrowDown') { e.preventDefault(); setVolume(Math.max(0, v.volume - .05)); }
  else if (k === 'm') $('#mute').click();
  else if (k === 'f') $('#fs').click();
  else if (k === 't') $('#thBtn').click();
  else if (k === 'c') $('#ccBtn').click();
  else if (k === 'a') setOv('stream', mode() === 'audio' ? 'video' : 'audio');
  else if (k === 'n') next();
  else if (k === 'p') prev();
});

/* ================= subtitles ================= */
const cc = { tracks: [], lang: null, auto: false, cues: [], req: 0 };
const capSpan = $('#caps span');
function showCaption(text) { if (capSpan.textContent !== text) capSpan.textContent = text; }
function resetCaptions(tracks) {
  cc.tracks = tracks; cc.lang = null; cc.auto = false; cc.cues = []; cc.req++;
  showCaption('');
  $('#ccBtn').title = tracks.length ? 'Subtitles (C)' : 'No subtitles for this video';
  syncCaptions();
}
function preferredTrack() {
  const pref = store.get('subLang', 'en'), base = pref.split('-')[0], by = t => t.lang.split('-')[0];
  return cc.tracks.find(t => t.lang === pref && !t.auto) || cc.tracks.find(t => t.lang === pref) ||
    cc.tracks.find(t => by(t) === base && !t.auto) || cc.tracks.find(t => by(t) === base) || cc.tracks[0];
}
async function loadTrack(t) {
  const my = ++cc.req;
  cc.lang = t.lang; cc.auto = t.auto; cc.cues = []; showCaption('');
  try {
    const r = await fetch(`/api/subs/${current.id}?lang=${encodeURIComponent(t.lang)}&auto=${t.auto ? 1 : 0}`);
    const data = await r.json();
    if (my !== cc.req) return;
    if (!r.ok) throw new Error(data.error || r.statusText);
    cc.cues = data;
  } catch {
    if (my === cc.req) { cc.lang = null; toast('Could not load subtitles for this video'); }
  }
  syncCaptions();
}
function syncCaptions() {
  $('#ccBtn').setAttribute('aria-pressed', !!(eff('subs') && cc.lang));
  $('#ccBtn').disabled = !cc.tracks.length;
  if (!eff('subs') || !cc.tracks.length) return showCaption('');
  if (!cc.lang && current) loadTrack(preferredTrack());
}
function updateCaps() {
  if (!eff('subs') || !cc.cues.length) return showCaption('');
  const t = pos(), c = cc.cues;
  let lo = 0, hi = c.length - 1, f = -1;
  while (lo <= hi) { const m = (lo + hi) >> 1; if (c[m].s <= t) { f = m; lo = m + 1; } else hi = m - 1; }
  showCaption(f >= 0 && t < c[f].e ? c[f].t : '');
}
function chooseTrack(t) {
  store.set('subLang', t.lang);
  setOv('subs', true);
  if (cc.lang !== t.lang || cc.auto !== t.auto) loadTrack(t);
}
// the button switches captions on and off; the language is picked under Settings > Captions
$('#ccBtn').onclick = () => setOv('subs', !eff('subs'));

/* ================= popovers, menus, dialogs ================= */
const pop = $('#pop');
let popState = null; // { anchor, build }
function openPop(anchor, build) {
  const node = typeof build === 'function' ? build() : build;
  popState = { anchor, build: typeof build === 'function' ? build : null };
  (document.fullscreenElement || document.body).append(pop); // must live inside the fullscreen element to be visible there
  const dark = node.classList.contains('ytm'); // the player's own menu
  pop.classList.toggle('yt', dark);
  pop.textContent = ''; pop.append(node); pop.hidden = false;
  const a = anchor.getBoundingClientRect(), pw = pop.offsetWidth, ph = pop.offsetHeight, pad = 8;
  const left = Math.min(Math.max(pad, a.right - pw), innerWidth - pw - pad);
  let top = a.bottom + 4;
  if (dark && a.top - ph - 8 >= pad) top = a.top - ph - 8; // above the control bar when there is room
  else if (top + ph > innerHeight - pad) top = Math.max(pad, a.top - ph - 4);
  pop.style.left = left + 'px'; pop.style.top = top + 'px';
}
function closePop() { pop.hidden = true; popState = null; }
function refreshPop() { if (popState?.build && !pop.hidden) { const { scrollTop } = pop; openPop(popState.anchor, popState.build); pop.scrollTop = scrollTop; } }
document.addEventListener('pointerdown', e => { if (!pop.hidden && !pop.contains(e.target) && !popState?.anchor.contains(e.target)) closePop(); }, true);
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !pop.hidden) { closePop(); e.stopPropagation(); } }, true);
addEventListener('resize', closePop);
addEventListener('scroll', closePop, { passive: true });

function menu(anchor, items) {
  openPop(anchor, () => {
    const w = el('div'); w.setAttribute('role', 'menu');
    for (const it of items) {
      if (it === '-') { w.append(el('div', 'menudiv')); continue; }
      if (it.head) { w.append(el('div', 'menuhead', it.head)); continue; }
      const b = el('button', 'menuitem' + (it.danger ? ' danger' : '') + (it.check ? ' checked' : '')); b.type = 'button'; b.setAttribute('role', 'menuitem');
      b.append(it.check ? icon('check') : it.icon ? icon(it.icon) : el('span', 'sp'), document.createTextNode(it.label));
      b.onclick = () => { closePop(); it.fn(); };
      w.append(b);
    }
    return w;
  });
}

function seg(opts, val, pick, disabled) {
  const g = el('div', 'seg'); g.setAttribute('role', 'group');
  for (const o of opts) {
    const on = o.v === val, b = el('button', 'segb' + (on ? ' on' : '')); b.type = 'button';
    b.setAttribute('aria-pressed', on); b.disabled = !!disabled;
    if (on) b.append(icon('check'));
    b.append(document.createTextNode(o.label));
    b.onclick = () => pick(o.v);
    g.append(b);
  }
  return g;
}
function sw(on, pick, label) {
  const b = el('button', 'switch' + (on ? ' on' : '')); b.type = 'button';
  b.setAttribute('role', 'switch'); b.setAttribute('aria-checked', on); b.setAttribute('aria-label', label);
  b.append(el('span', 'knob')); b.onclick = () => pick(!on);
  return b;
}

// The player's settings menu, YouTube style: a list of settings, each opening its choices. rows() returns
// { id, icon, label, value, options: [{ label, on, fn }], extra: () => node } or { icon, label, toggle, fn } or { node }.
function ytMenu(anchor, rows) {
  let page = null;
  openPop(anchor, () => {
    const w = el('div', 'ytm'); w.setAttribute('role', 'menu');
    const list = rows(), sub = list.find(r => r.id && r.id === page && r.options);
    const line = (cls, fn, ...kids) => { const b = el('button', 'ytrow' + cls); b.type = 'button'; b.setAttribute('role', 'menuitem'); b.append(...kids); b.onclick = fn; return b; };
    if (sub) {
      w.append(line(' ythead', () => { page = null; refreshPop(); }, icon('back'), el('span', 'ytl', sub.label)));
      for (const o of sub.options) w.append(line(' opt' + (o.on ? ' on' : ''), () => { page = null; o.fn(); refreshPop(); }, icon('check'), el('span', 'ytl', o.label)));
      if (sub.extra) w.append(sub.extra());
    } else for (const r of list) {
      if (r.node) { w.append(r.node); continue; }
      const kids = [icon(r.icon), el('span', 'ytl', r.label)];
      if (r.toggle !== undefined) kids.push(el('span', 'yttoggle' + (r.toggle ? ' on' : '')));
      else if (r.value) kids.push(el('span', 'ytv', r.value));
      if (r.options) kids.push(icon('chev'));
      const b = line('', () => { if (r.options) page = r.id; else r.fn(); refreshPop(); }, ...kids);
      if (r.toggle !== undefined) { b.setAttribute('role', 'menuitemcheckbox'); b.setAttribute('aria-checked', r.toggle); }
      b.disabled = !r.options && !r.fn;
      w.append(b);
    }
    return w;
  });
}
const speedRow = (now, set) => ({ id: 'speed', icon: 'speed', label: 'Playback speed', value: speedLabel(now), options: RATES.map(r => ({ label: speedLabel(r), on: r === now, fn: () => set(r) })) });
// the same rows, in the same order, as the apps' settings
function playerSettings() {
  const audio = eff('stream') === 'audio', on = eff('subs') && cc.lang;
  const trackName = t => t.name + (t.auto ? ' (auto-generated)' : '');
  const sel = on && cc.tracks.find(t => t.lang === cc.lang && t.auto === cc.auto);
  const rows = [];
  if (!audio) rows.push({ id: 'quality', icon: 'hd', label: 'Quality', value: qLabel(eff('quality')), options: HEIGHTS.map(h => ({ label: qLabel(h), on: eff('quality') === h, fn: () => setOv('quality', h) })) });
  rows.push(speedRow(rate, setRate));
  rows.push({ id: 'captions', icon: 'cc', label: 'Captions', value: !cc.tracks.length ? 'Unavailable' : sel ? trackName(sel) : 'Off',
    options: cc.tracks.length ? [{ label: 'Off', on: !eff('subs'), fn: () => setOv('subs', false) }, ...cc.tracks.map(t => ({ label: trackName(t), on: sel === t, fn: () => chooseTrack(t) }))] : null });
  rows.push({ icon: 'audio', label: 'Audio only', toggle: audio, fn: () => setOv('stream', audio ? 'video' : 'audio') });
  rows.push({ icon: 'autoplay', label: 'Autoplay next', toggle: eff('autoplay'), fn: () => setOv('autoplay', !eff('autoplay')) });
  if (Object.keys(ov).length) rows.push({ icon: 'retry', label: 'Reset to my defaults', fn: resetOv });
  return rows;
}
const menuIsOpen = anchor => popState?.anchor === anchor && !pop.hidden;
$('#gear').onclick = e => (menuIsOpen(e.currentTarget) ? closePop() : ytMenu(e.currentTarget, playerSettings));
$('#modeChip').onclick = e => ytMenu(e.currentTarget, playerSettings);

function applyUI() {
  const m = mode() === 'audio', theater = eff('view') === 'theater';
  stage.dataset.mode = mode();
  watchEl.classList.toggle('theater', theater);
  $('#thi').firstChild.setAttribute('href', theater ? '#i-normal' : '#i-theater');
  const thLabel = theater ? 'Default view (T)' : 'Theater mode (T)';
  $('#thBtn').title = thLabel; $('#thBtn').setAttribute('aria-label', thLabel);
  $('#modeChipText').textContent = m ? 'Audio only' : `Video · ${qLabel(eff('quality'))}`;
  $('#modeChip').querySelector('use').setAttribute('href', m ? '#i-audio' : '#i-video');
  const anyOv = Object.keys(ov).length > 0;
  $('#modeChip').classList.toggle('on', anyOv);
  syncCaptions();
}

const dlg = $('#dlg');
dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close(); });
function openDialog({ title, body, actions }) {
  dlg.textContent = '';
  const b = el('div', 'body'); b.append(...[].concat(body));
  const a = el('div', 'acts');
  for (const x of actions) a.append(button(`btn ${x.primary ? 'filled' : 'text'}`, x.label, null, () => { if (x.fn?.() !== false) dlg.close(); }));
  dlg.append(el('h2', '', title), b, a);
  if (!dlg.open) dlg.showModal();
}
const openSettings = () => nav('/settings');
function viewSettings() {
  return page((root, setRefresh) => {
    const draw = () => {
      root.textContent = '';
      const row = (title, desc, ctl, inline) => { const r = el('div', 'setrow' + (inline ? ' inline' : '')); const l = el('div'); l.append(el('div', 'lbl', title)); if (desc) l.append(el('div', 'cap', desc)); r.append(l, ctl); return r; };
      const set = (k, x) => { setGlobal(k, x); draw(); };
      const head = el('div', 'ph'); head.append(el('h1', '', 'Settings')); root.append(head);
      const box = el('div', 'setpage');
      box.append(
        row('Theme', null, seg([{ v: 'system', label: 'System' }, { v: 'light', label: 'Light' }, { v: 'dark', label: 'Dark' }], settings.theme, x => set('theme', x))),
        row('Stream type', 'What plays by default', seg([{ v: 'video', label: 'Video' }, { v: 'audio', label: 'Audio only' }], settings.stream, x => set('stream', x))),
        row('Video quality', 'Highest resolution to use by default. 1440p and 4K are VP9, which needs a fast machine', seg(HEIGHTS.map(h => ({ v: h, label: qLabel(h) })), settings.quality, x => set('quality', x))),
        row('Player view', 'Theater makes the player span the page', seg([{ v: 'normal', label: 'Normal' }, { v: 'theater', label: 'Theater' }], settings.view, x => set('view', x))),
        row('Subtitles', 'Show them when a video has them', sw(settings.subs, x => set('subs', x), 'Subtitles'), true),
        row('Autoplay next', 'Continue with the next item in the queue', sw(settings.autoplay, x => set('autoplay', x), 'Autoplay next'), true),
        row('Save watch history', 'Videos you play are listed under History', sw(settings.history, x => set('history', x), 'Save watch history'), true),
        row('Recommendations', 'Tired of the same kind of videos? Home stops using what you watched before now and starts fresh. Your history stays.', button('btn outlined sm', 'Reset recommendations', null, () => confirmDialog({
          title: 'Reset recommendations?', text: 'Home will build its suggestions again from what you watch from now on. Your watch history is kept.', confirm: 'Reset',
          fn: () => { recsSince = Date.now(); store.set('recsSince', recsSince); feed.items = []; feed.key = ''; feed.at = 0; loadFeed(true); toast('Recommendations reset'); },
        })), true),
        el('p', 'note', 'These are your defaults for everything. The settings button on the player overrides stream, quality, view, subtitles, speed and autoplay just for what you are watching now, without changing these.'),
      );
      root.append(box);
    };
    draw(); setRefresh(draw);
  });
}
$('#openSettings').onclick = openSettings;
$('#navHistory').onclick = () => nav(curRoute() === 'history' ? '/' : '/history');
$('#navPlaylists').onclick = () => nav(curRoute() === 'playlists' ? '/' : '/playlists');

function promptName({ title, value = '', confirm = 'Save' }) {
  return new Promise(resolve => {
    const f = el('div', 'field'); const inp = el('input'); inp.type = 'text'; inp.id = 'nameIn'; inp.maxLength = 80; inp.value = value; inp.placeholder = ' ';
    const lb = el('label', '', 'Name'); lb.htmlFor = 'nameIn'; f.append(inp, lb);
    let done = false;
    const finish = val => { if (!done) { done = true; resolve(val); } };
    const ok = () => { const n = inp.value.trim(); if (!n) { inp.focus(); return false; } finish(n); };
    inp.onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); if (ok() !== false) dlg.close(); } };
    dlg.addEventListener('close', () => finish(null), { once: true });
    openDialog({ title, body: f, actions: [{ label: 'Cancel', fn: () => { finish(null); } }, { label: confirm, primary: true, fn: ok }] });
    inp.focus(); inp.select();
  });
}
function confirmDialog({ title, text, confirm, fn }) {
  openDialog({ title, body: el('p', 'note', text), actions: [{ label: 'Cancel' }, { label: confirm, primary: true, fn }] });
}

/* ================= playlists + queue actions ================= */
function createPlaylist(name, items = []) {
  const p = { id: 'p' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5), name, items: items.map(slim) };
  playlists.unshift(p); savePl(); return p;
}
function pickPlaylist(item) {
  const render = () => {
    const wrap = el('div', 'plpick');
    const nw = el('button', 'menuitem'); nw.type = 'button'; nw.append(icon('add'), document.createTextNode('New playlist…'));
    nw.onclick = async () => { const n = await promptName({ title: 'New playlist', confirm: 'Create' }); if (n) { const p = createPlaylist(n, [item]); toast(`Saved to “${p.name}”`); refreshView(); } else render(); };
    wrap.append(nw);
    for (const p of playlists) {
      const has = p.items.some(x => x.id === item.id);
      const b = el('button', 'menuitem'); b.type = 'button';
      b.append(has ? icon('check') : icon('list'), document.createTextNode(p.name));
      const c = el('span', '', String(p.items.length)); c.style.cssText = 'margin-left:auto;color:var(--on-surface-var)'; b.append(c);
      b.onclick = () => {
        if (has) { p.items = p.items.filter(x => x.id !== item.id); toast(`Removed from “${p.name}”`); }
        else { p.items.push(slim(item)); toast(`Added to “${p.name}”`); }
        savePl(); refreshView(); render();
      };
      wrap.append(b);
    }
    openDialog({ title: 'Save to playlist', body: [wrap], actions: [{ label: 'Done', primary: true }] });
  };
  render();
}
async function saveQueueAsPlaylist() {
  if (!queue.items.length) return;
  const n = await promptName({ title: 'Save queue as playlist', value: current?.title ? current.title.slice(0, 40) : 'My playlist', confirm: 'Save' });
  if (!n) return;
  const p = createPlaylist(n, queue.items);
  toast(`Saved ${p.items.length} videos to “${p.name}”`, { label: 'Open', fn: () => nav('/playlist/' + p.id) });
}

function enqueue(item) {
  stash(item);
  if (!current) return startList([item], 0);
  queue.items.push(slim(item));
  renderSide(); toast('Added to queue');
}
function playNext(item) {
  stash(item);
  if (!current) return startList([item], 0);
  queue.items.splice(queue.i + 1, 0, slim(item));
  renderSide(); toast('Playing next');
}
function moveQ(from, to) {
  if (from === to || to < 0 || to >= queue.items.length) return;
  const [it] = queue.items.splice(from, 1); queue.items.splice(to, 0, it);
  if (queue.i === from) queue.i = to;
  else if (from < queue.i && to >= queue.i) queue.i--;
  else if (from > queue.i && to <= queue.i) queue.i++;
  renderSide();
}
function removeQ(r) {
  const [gone] = queue.items.splice(r, 1);
  if (r < queue.i) queue.i--; else if (r === queue.i) queue.i = r - 1; // keeps "next" pointing at what followed
  renderSide();
  toast('Removed from queue', { label: 'Undo', fn: () => { queue.items.splice(r, 0, gone); if (r <= queue.i) queue.i++; renderSide(); } });
}

/* ================= cards ================= */
// hq720 is sharp but missing for some videos, and YouTube answers with a small grey placeholder (not an
// error), so fall back to the 320px thumbnail whenever what loaded is tiny.
function hqImg(img, id) {
  img.src = `https://i.ytimg.com/vi/${id}/hq720.jpg`;
  const fallback = () => { img.onload = img.onerror = null; img.src = thumbOf(id); };
  img.onload = () => { if (img.naturalWidth < 400) fallback(); };
  img.onerror = fallback;
}
function thumbEl(id, { hq = false, dur = 0, live = false } = {}) {
  const th = el('div', 'thumb'), img = el('img');
  img.loading = 'lazy'; img.alt = '';
  if (hq) hqImg(img, id); else img.src = thumbOf(id);
  th.append(img);
  if (live) th.append(el('span', 'live', 'LIVE')); else if (dur) th.append(el('span', 'dur', fmt(dur)));
  const pct = progressOf(id, dur);
  if (pct) { const r = el('div', 'resume'), i = el('i'); i.style.width = pct + '%'; r.append(i); th.append(r); }
  th.insertAdjacentHTML('beforeend', '<span class="eq" aria-hidden="true"><i></i><i></i><i></i></span>');
  return th;
}
// Look a video's stream up ahead of time (the server keeps it for 20 minutes), so playing it skips the ~3.5 s lookup.
const warmed = new Set();
function warmStream(id, m = mode(), q = eff('quality')) {
  const k = `${id}:${m}:${q}`;
  if (warmed.has(k)) return;
  warmed.add(k); setTimeout(() => warmed.delete(k), 15 * 60_000);
  fetch(`/api/info/${id}?mode=${m}&q=${q}`).catch(() => {});
}
// Hover a card for a moment (or put a finger on it) and the lookup starts before the click.
function hoverWarm(node, item) {
  let t = 0;
  node.addEventListener('pointerenter', e => { if (e.pointerType === 'mouse' && !item.live && /^[\w-]{11}$/.test(item.id || '')) t = setTimeout(() => warmStream(item.id), 250); });
  node.addEventListener('pointerleave', () => clearTimeout(t));
  node.addEventListener('pointerdown', () => { if (!item.live && /^[\w-]{11}$/.test(item.id || '')) warmStream(item.id); });
}
const activate = (node, fn) => {
  node.tabIndex = 0; node.setAttribute('role', 'button'); node.onclick = fn;
  node.onkeydown = e => { if (e.key === 'Enter' && e.target === node) { e.preventDefault(); fn(); } };
};

function itemMenu(item, ctx = {}) {
  const m = [
    { label: 'Play next', icon: 'next', fn: () => playNext(item) },
    { label: 'Add to queue', icon: 'queue', fn: () => enqueue(item) },
    { label: 'Save to playlist…', icon: 'pladd', fn: () => pickPlaylist(item) },
    { label: isSaved('favs', item.id) ? 'Remove from Favorites' : 'Favorite', icon: 'heart', fn: () => toggleSaved('favs', item) },
  ];
  if (ctx.where === 'queue') {
    m.push('-');
    if (ctx.index > 0) m.push({ label: 'Move up', icon: 'back', fn: () => moveQ(ctx.index, ctx.index - 1) });
    if (ctx.index < queue.items.length - 1) m.push({ label: 'Move down', icon: 'next', fn: () => moveQ(ctx.index, ctx.index + 1) });
    m.push({ label: 'Remove from queue', icon: 'delete', danger: true, fn: () => removeQ(ctx.index) });
  } else if (ctx.where === 'playlist') {
    const pl = plById(ctx.plId);
    m.push('-');
    if (ctx.index > 0) m.push({ label: 'Move up', icon: 'back', fn: () => { const [x] = pl.items.splice(ctx.index, 1); pl.items.splice(ctx.index - 1, 0, x); savePl(); refreshView(); } });
    if (ctx.index < pl.items.length - 1) m.push({ label: 'Move down', icon: 'next', fn: () => { const [x] = pl.items.splice(ctx.index, 1); pl.items.splice(ctx.index + 1, 0, x); savePl(); refreshView(); } });
    m.push({ label: 'Remove from playlist', icon: 'delete', danger: true, fn: () => {
      const old = pl.items.slice(); pl.items.splice(ctx.index, 1); savePl(); refreshView();
      toast('Removed from playlist', { label: 'Undo', fn: () => { pl.items = old; savePl(); refreshView(); } });
    } });
  } else if (ctx.where === 'history') {
    m.push('-', { label: 'Remove from history', icon: 'delete', danger: true, fn: () => removeHistory(item.id) });
  } else if (ctx.where === 'continue') {
    m.push('-', { label: 'Remove from Continue watching', icon: 'delete', danger: true, fn: () => forgetPositions([item.id], 'Removed from Continue watching') });
  }
  return m;
}
const moreBtn = (item, ctx, cls = 'ib sm more') => { const b = iconBtn('more', 'More actions', e => { e.stopPropagation(); menu(b, itemMenu(item, ctx)); }, cls); return b; };

// YouTube-style search / history row
function rcard(item, ctx, onPlay) {
  const c = el('div', 'rcard pressable' + (ctx.where === 'history' ? ' hrow' : ''));
  c.append(thumbEl(item.id, { hq: ctx.where !== 'history', dur: item.duration, live: item.live }));
  const info = el('div', 'info'); info.append(el('div', 't', item.title));
  if (ctx.where === 'history') {
    info.append(el('div', 'c', item.channel || ''), el('div', 'meta1', 'Watched ' + new Date(item.at).toLocaleTimeString('en', { hour: 'numeric', minute: '2-digit' })));
  } else {
    const meta = [fmtViews(item.views)].filter(Boolean).join(' · ');
    if (meta) info.append(el('div', 'meta1', meta));
    const ch = el('div', 'chan'); ch.append(document.createTextNode(item.channel || ''));
    if (item.verified) ch.append(icon('verified'));
    info.append(ch);
    if (item.desc) info.append(el('div', 'snip', item.desc));
  }
  const tail = el('div', 'tail');
  if (ctx.where === 'history') tail.append(iconBtn('close', 'Remove from history', e => { e.stopPropagation(); removeHistory(item.id); }, 'ib sm'));
  tail.append(moreBtn(item, ctx));
  tail.classList.add('more');
  c.append(info, tail);
  activate(c, onPlay); hoverWarm(c, item);
  return c;
}
// grid card (home)
function gcard(item, ctx, onPlay) {
  const c = el('div', 'gcard pressable');
  c.append(thumbEl(item.id, { hq: true, dur: item.duration, live: item.live }));
  const gi = el('div', 'gi'), t = el('div'); t.append(el('div', 't', item.title), el('div', 'c', item.channel || ''));
  const v = fmtViews(item.views); if (v) t.append(el('div', 'c', item.live ? v.replace(/views?$/, 'watching') : v));
  gi.append(t, moreBtn(item, ctx));
  c.append(gi); activate(c, onPlay); hoverWarm(c, item);
  return c;
}
function plCard(p) {
  const c = el('div', 'gcard pressable');
  const st = el('div', 'pstack');
  if (p.items[0]) { const im = el('img'); im.alt = ''; hqImg(im, p.items[0].id); st.append(im); }
  const cnt = el('div', 'cnt'); cnt.append(el('b', '', String(p.items.length)), icon('list')); st.append(cnt);
  const gi = el('div', 'gi'), t = el('div'); t.append(el('div', 't', p.name), el('div', 'c', 'View full playlist'));
  gi.append(t, iconBtn('play', `Play ${p.name}`, e => { e.stopPropagation(); if (p.items.length) startList(p.items, 0, { type: 'playlist', id: p.id, name: p.name }); }, 'ib sm more'));
  c.append(st, gi); activate(c, () => nav('/playlist/' + p.id));
  return c;
}

/* ================= watch page: meta + queue sidebar ================= */
const hasSide = () => queue.items.length > 1 || queue.source?.type === 'playlist';
const sideVisible = () => hasSide() || !!current?.related?.length; // the queue, or what YouTube suggests next

// Web addresses and timestamps in a description become links: a YouTube link plays here, 12:34 seeks to that time.
const LINK_RE = /(https?:\/\/[^\s<>"]+)|(?<![\w:/.])((?:\d{1,2}:)?\d{1,2}:\d{2})(?![\d:])/g;
const YT_LINK = /(?:youtube\.com\/watch\?(?:[^#\s]*&)?v=|youtu\.be\/|youtube\.com\/(?:shorts|live)\/)([\w-]{11})/;
function linkify(text) {
  const frag = document.createDocumentFragment();
  let last = 0;
  for (const m of text.matchAll(LINK_RE)) {
    frag.append(text.slice(last, m.index));
    last = m.index + m[0].length;
    if (m[1]) {
      const url = m[1].replace(/[.,;:!?)\]]+$/, '');
      last -= m[1].length - url.length; // punctuation after the address stays plain text
      const a = el('a', 'lnk', url), vid = YT_LINK.exec(url);
      if (vid) a.href = `#/watch?v=${vid[1]}`; else { a.href = url; a.target = '_blank'; a.rel = 'noopener noreferrer'; }
      frag.append(a);
    } else {
      const secs = m[2].split(':').reduce((x, y) => x * 60 + Number(y), 0);
      if (!duration || secs >= duration) { frag.append(m[2]); continue; }
      const a = el('a', 'lnk ts', m[2]); a.href = '#'; a.onclick = e => { e.preventDefault(); seekTo(secs); window.scrollTo({ top: 0, behavior: 'smooth' }); };
      frag.append(a);
    }
  }
  frag.append(text.slice(last));
  return frag;
}

function loadRelated(id) {
  fetch(`/api/related/${id}`).then(r => (r.ok ? r.json() : [])).then(list => {
    if (current?.id !== id || !Array.isArray(list)) return;
    list.forEach(stash);
    current.related = list;
    renderSide();
  }).catch(() => {});
}

function renderWatch() { renderMeta(); renderSide(); applyUI(); }

function renderMeta() {
  const m = $('#meta'); m.hidden = !current;
  watchEl.classList.toggle('no-side', !sideVisible());
  if (!current) return;
  $('#title').textContent = current.title || current.id;
  $('#by').replaceChildren(el('b', '', current.channel || ''));
  const stats = [fmtViews(current.views), fmtDate(current.uploaded)].filter(Boolean).join(' · ');
  $('#stats').textContent = current.live ? ['LIVE', fmtViews(current.views)].filter(Boolean).join(' · ') : stats;
  $('#descText').replaceChildren(linkify(current.description || ''));
  const d = $('#desc'); d.classList.toggle('empty-desc', !stats && !current.description);
  $('#descToggle').hidden = !current.description || current.description.length < 120;
}
$('#descToggle').onclick = () => { const d = $('#desc'); d.classList.toggle('open'); $('#descToggle').textContent = d.classList.contains('open') ? 'Show less' : 'Show more'; };
$('#saveBtn').onclick = () => current && pickPlaylist(current);
$('#queueAddBtn').onclick = () => {
  if (!current) return;
  if (queue.items.some(x => x.id === current.id) && queue.items.length === 1) return toast('Pick another video, then use its menu to add it here.');
  enqueue(current);
};

let dragFrom = -1;
function sideCard(item, index) {
  const ctx = { where: 'queue', index };
  const c = el('div', 'card'); c.dataset.index = index;
  c.append(el('span', 'idx', String(index + 1)), thumbEl(item.id, { dur: item.duration }));
  const info = el('div', 'info'); info.append(el('div', 't', item.title), el('div', 'c', item.channel || ''));
  const tail = el('div', 'tail'), g = el('span', 'grip'); g.append(icon('grip'));
  tail.append(g, moreBtn(item, ctx));
  c.append(info, tail);
  activate(c, () => playIndex(index));
  c.draggable = true;
  c.ondragstart = e => { dragFrom = index; e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(index)); c.classList.add('dragging'); };
  c.ondragend = () => { dragFrom = -1; $('#sidelist').querySelectorAll('.card').forEach(x => x.classList.remove('dragging', 'drop-before', 'drop-after')); };
  const after = e => { const r = c.getBoundingClientRect(); return e.clientY > r.top + r.height / 2; };
  c.ondragover = e => { if (dragFrom < 0) return; e.preventDefault(); const a = after(e); c.classList.toggle('drop-after', a); c.classList.toggle('drop-before', !a); };
  c.ondragleave = () => c.classList.remove('drop-before', 'drop-after');
  c.ondrop = e => { e.preventDefault(); const p = index + (after(e) ? 1 : 0), from = dragFrom; dragFrom = -1; moveQ(from, from < p ? p - 1 : p); };
  return c;
}
// a suggested video, in the list beside the player
function relCard(item) {
  const c = el('div', 'card rel pressable');
  c.append(thumbEl(item.id, { dur: item.duration, live: item.live }));
  const info = el('div', 'info');
  info.append(el('div', 't', item.title), el('div', 'c', item.channel || ''));
  const v = fmtViews(item.views); if (v) info.append(el('div', 'c', item.live ? v.replace(/views?$/, 'watching') : v));
  const tail = el('div', 'tail'); tail.append(moreBtn(item, { where: 'related' }));
  c.append(info, tail);
  activate(c, () => openVideo(item));
  return c;
}
function renderSide() {
  const side = $('#side'), show = sideVisible() && !!current;
  side.hidden = !show;
  watchEl.classList.toggle('no-side', !show);
  if (!show) return;
  const q = hasSide(), rel = current.related || [];
  const head = $('#shead'); head.textContent = '';
  if (q) {
    const total = queue.items.reduce((s, x) => s + (x.duration || 0), 0);
    const ttl = el('div', 'ttl'); ttl.append(el('strong', '', queue.source?.name || 'Queue'), el('span', '', `${Math.max(queue.i + 1, 1)} / ${queue.items.length} · ${fmt(total)}`));
    head.append(ttl);
    if (queue.source?.type !== 'playlist') head.append(iconBtn('pladd', 'Save queue as playlist', saveQueueAsPlaylist));
    head.append(iconBtn('close', queue.source ? 'Stop playing this playlist' : 'Clear queue', () => {
      const old = { items: queue.items, i: queue.i, source: queue.source };
      queue = { items: [slim(current)], i: 0, source: null };
      renderWatch(); nav(watchPath(current.id), { replace: true });
      toast(old.source ? 'Playlist closed' : 'Queue cleared', { label: 'Undo', fn: () => { queue = old; renderWatch(); nav(watchPath(current.id), { replace: true }); } });
    }));
  } else {
    const ttl = el('div', 'ttl'); ttl.append(el('strong', '', 'Related videos'), el('span', '', 'Suggested by YouTube'));
    head.append(ttl);
  }
  const list = $('#sidelist'), top = list.scrollTop;
  const nodes = q ? queue.items.map(sideCard) : [];
  if (rel.length) { if (q) nodes.push(el('div', 'rhead', 'Related videos')); nodes.push(...rel.map(relCard)); }
  list.replaceChildren(...nodes);
  list.querySelectorAll('.card:not(.rel)').forEach(c => c.classList.toggle('on', Number(c.dataset.index) === queue.i));
  list.scrollTop = top;
  const on = list.querySelector('.card.on');
  if (on && (on.offsetTop < list.scrollTop || on.offsetTop + on.offsetHeight > list.scrollTop + list.clientHeight)) list.scrollTop = on.offsetTop - 8; // no scrollIntoView: it can scroll the whole page
}

/* ================= pages ================= */
let refreshFn = null;
const refreshView = () => { renderSide(); if (refreshFn) refreshFn(); };
function page(build) { refreshFn = null; const root = el('div'); build(root, fn => { refreshFn = fn; }); return root; }
function emptyBox(title, text, btn) {
  const d = el('div', 'empty'); d.append(el('strong', '', title), el('span', '', text));
  if (btn) d.append(button('btn tonal', btn.label, null, btn.fn));
  return d;
}

/* ----- home ----- */
// YouTube builds its home page from what you watched. We do the same, locally: the videos YouTube suggests after each of
// your last few, mixed, minus what you've seen; plus videos from the channels you watch. Nothing yet: a few general searches.
const feed = { items: [], at: 0, key: '', loading: false, error: '' };
async function loadFeed(force = false) {
  const seeds = history.filter(h => h.at > recsSince).slice(0, 5);
  const key = seeds.map(h => h.id).join(',') + '|' + recsSince;
  if (feed.loading || (!force && feed.items.length && feed.key === key && Date.now() - feed.at < 30 * 60_000)) return;
  feed.loading = true; feed.error = '';
  const J = u => fetch(u).then(r => (r.ok ? r.json() : [])).catch(() => []);
  try {
    let lists;
    if (!seeds.length) lists = await Promise.all(['trending now', 'popular music', 'news today'].map(q => J(`/api/search?q=${encodeURIComponent(q)}&n=12`)));
    else {
      const chans = [...new Set(seeds.map(h => h.channel).filter(Boolean))].slice(0, 3);
      lists = await Promise.all([...seeds.map(h => J(`/api/related/${h.id}`)), ...chans.map(c => J(`/api/search?q=${encodeURIComponent(c)}&n=8`))]);
    }
    const seen = new Set(history.map(h => h.id)), out = [], ids = new Set();
    for (let i = 0; lists.some(l => i < l.length); i++) for (const l of lists) { const x = l[i]; if (x && !seen.has(x.id) && !ids.has(x.id)) { ids.add(x.id); out.push(x); } }
    feed.items = out.slice(0, 48); feed.items.forEach(stash);
    if (!feed.items.length) feed.error = 'Couldn\'t load suggestions. Is the internet connection up?';
  } catch (e) { feed.error = e.message; }
  feed.loading = false; feed.at = Date.now(); feed.key = key;
  if (curRoute() === 'home' && refreshFn) refreshFn();
}
function viewHome() {
  return page((root, setRefresh) => {
    const draw = () => {
      root.textContent = '';
      const cont = continueWatching().slice(0, 8);
      if (!history.length) {
        const hero = el('div', 'hero'); hero.append(el('h1', '', 'What are we watching?'), el('p', '', 'Search above or paste a YouTube link. It plays here without ads and remembers where you stopped.'));
        const hint = el('div', 'hint');
        for (const [k, t] of [['/', 'search'], ['Space', 'play'], ['J L', '±10s'], ['T', 'theater'], ['C', 'subtitles'], ['A', 'audio only']]) { const s = el('span'); s.append(el('span', 'kbd', k), document.createTextNode(t)); hint.append(s); }
        hero.append(hint); root.append(hero);
      }
      if (cont.length) {
        const s = el('section', 'section'), head = el('h2', '', 'Continue watching');
        head.append(button('btn text sm has-icon', 'Remove all', 'delete', () => confirmDialog({
          title: 'Remove all from Continue watching?', text: 'Where you stopped is forgotten. Your watch history stays.', confirm: 'Remove all',
          fn: () => forgetPositions(continueIds(), 'Continue watching cleared'),
        })));
        const g = el('div', 'ggrid'); g.append(...cont.map(h => gcard(h, { where: 'continue' }, () => openVideo(h))));
        s.append(head, g); root.append(s);
      }

      const chips = el('div', 'chips');
      chips.append(button('chipb on', 'All', null, () => {}));
      for (const c of ['Music', 'Live', 'Gaming', 'News', 'Podcasts', 'Movies', 'Cooking', 'Science', 'Sports', 'Comedy', 'Learning']) chips.append(button('chipb', c, null, () => nav('/results?q=' + encodeURIComponent(c === 'Live' ? 'live now' : c))));
      root.prepend(chips);
      const s = el('section', 'section'), h = el('h2', '', history.some(h => h.at > recsSince) ? 'Recommended for you' : 'Popular right now');
      h.append(button('btn text sm has-icon', feed.loading ? 'Loading…' : 'Refresh', 'retry', () => { loadFeed(true).then(draw); draw(); }));
      s.append(h);
      const g = el('div', 'ggrid');
      if (feed.items.length) g.append(...feed.items.map(x => gcard(x, { where: 'home' }, () => openVideo(x))));
      else if (feed.loading || !feed.error) {
        for (let i = 0; i < 8; i++) { const d = el('div', 'gcard sk'); d.innerHTML = '<div class="thumb"></div><div class="gi"><div><div class="l" style="width:90%;height:16px"></div><div class="l" style="width:50%"></div></div></div>'; g.append(d); }
      } else s.append(emptyBox('No suggestions yet', feed.error, { label: 'Try again', fn: () => { loadFeed(true).then(draw); draw(); } }));
      s.append(g); root.append(s);

      if (playlists.length) {
        const s2 = el('section', 'section'), h2 = el('h2', '', 'Your playlists'); h2.append(button('btn text sm', 'See all', null, () => nav('/playlists'))); s2.append(h2);
        const g2 = el('div', 'ggrid'); g2.append(...playlists.slice(0, 4).map(plCard)); s2.append(g2); root.append(s2);
      }
    };
    draw(); setRefresh(draw);
    loadFeed();
  });
}

/* ----- shorts, music, watch later, favorites ----- */
const fetchJson = u => fetch(u).then(r => (r.ok ? r.json() : [])).catch(() => []);
const shorts = { items: [], loading: false, error: '' };
// The server lists real Shorts (hashtag pages and channels' Shorts tabs); a plain search can't tell a Short from a video.
async function loadShorts(force = false) {
  if (shorts.loading || (shorts.items.length && !force)) return;
  shorts.loading = true; shorts.error = '';
  try {
    const r = await fetch('/api/shorts' + (force ? '?force=1' : ''));
    const data = await r.json();
    if (!r.ok) throw new Error(data.error || r.statusText);
    shorts.items = data; data.forEach(stash);
    if (!data.length) shorts.error = 'No Shorts came back. Is the internet connection up?';
  } catch (e) { shorts.error = e.message; }
  shorts.loading = false;
  if (curRoute() === 'shorts' && refreshFn) refreshFn();
}
function viewShorts() {
  return page((root, setRefresh) => {
    const draw = () => {
      root.textContent = '';
      const head = el('div', 'ph'); head.append(el('h1', '', 'Shorts'), el('span', 'sub', 'Swipe or scroll through vertical clips'));
      head.append(button('btn text sm has-icon', shorts.loading ? 'Loading…' : 'Refresh', 'retry', () => { loadShorts(true); draw(); }));
      root.append(head);
      const g = el('div', 'ggrid sgrid');
      if (shorts.items.length) g.append(...shorts.items.map(x => { const c = gcard(x, { where: 'shorts' }, () => nav('/shorts?v=' + x.id)); c.classList.add('scard'); return c; }));
      else if (shorts.loading || !shorts.error) for (let i = 0; i < 10; i++) { const d = el('div', 'gcard scard sk'); d.innerHTML = '<div class="thumb"></div><div class="gi"><div><div class="l" style="width:90%;height:16px"></div><div class="l" style="width:50%"></div></div></div>'; g.append(d); }
      else root.append(emptyBox('Shorts', shorts.error, { label: 'Try again', fn: () => { loadShorts(true); draw(); } }));
      root.append(g);
    };
    draw(); setRefresh(draw); loadShorts().then(draw);
  });
}

/* The full-screen feed: one snap-scrolling column of clips. Only the clip on screen plays; the next one is warmed up so
   it starts quickly, anything further away is released. A clip that ends moves on to the next. */
let sfeed = null;
function closeShortsFeed() {
  if (!sfeed) return;
  sfeed.obs.disconnect(); sfeed.slides.forEach(s => s.release());
  removeEventListener('keydown', sfeed.key); sfeed.root.remove(); document.body.classList.remove('sf-open'); sfeed = null;
}
function openShortsFeed(startId) {
  if (!shorts.items.length) { loadShorts().then(() => { if (curRoute() === 'shorts' && parseHash().p.get('v') === startId && shorts.items.length) openShortsFeed(startId); }); return; }
  if (sfeed) { const i = sfeed.items.findIndex(x => x.id === startId); if (i >= 0 && i !== sfeed.at) sfeed.go(i, false); return; }
  const items = shorts.items.slice(), startAt = Math.max(0, items.findIndex(x => x.id === startId));
  if (current && !v.paused) v.pause();
  const root = el('div', 'sfeed'), sc = el('div', 'sscroll');
  const st = sfeed = { root, items, at: -1, muted: false, slides: [], go: null, key: null, obs: null };
  const go = (i, smooth = true) => { i = Math.max(0, Math.min(items.length - 1, i)); st.slides[i].scrollIntoView({ block: 'start', behavior: smooth ? 'smooth' : 'instant' }); };
  st.go = go;

  items.forEach((it, i) => {
    const s = el('section', 'sslide'), col = el('div', 'scol'), vid = el('video');
    vid.playsInline = true; vid.preload = 'auto'; vid.poster = thumbOf(it.id); vid.muted = false;
    const spin = el('div', 'sspin'), err = el('div', 'serr', 'Can’t play this one');
    const info = el('div', 'sinfo'); info.append(el('div', 'sc', it.channel || ''), el('div', 'st', it.title || ''));
    const prog = el('div', 'sprog'), bar = el('i'); prog.append(bar);
    const side = el('div', 'sside');
    const fav = iconBtn('heart', 'Favorite', () => { toggleSaved('favs', it); fav.classList.toggle('on', isSaved('favs', it.id)); }, 'ib sbtn');
    fav.classList.toggle('on', isSaved('favs', it.id));
    side.append(fav);
    col.append(vid, spin, err, info, side, prog); s.append(col);
    let attached = false;
    s.warm = () => { if (!attached) { attached = true; videoUrl(it.id, 1080).then(u => { if (attached) vid.src = u; }, () => { s.classList.remove('loading'); s.classList.add('err'); }); } }; // height cap: a 1080p Short is 608x1080
    s.play = () => { if (vid.ended) { attached = false; vid.removeAttribute('src'); } s.warm(); s.classList.remove('err'); vid.muted = st.muted; vid.play().catch(() => {}); };
    s.release = () => { vid.pause(); if (attached) { attached = false; vid.removeAttribute('src'); vid.load(); } s.classList.remove('loading'); };
    s.pause = () => vid.pause();
    s.toggle = () => { if (vid.paused) vid.play().catch(() => {}); else vid.pause(); };
    s.setMuted = m => { vid.muted = m; };
    vid.addEventListener('waiting', () => s.classList.add('loading'));
    vid.addEventListener('playing', () => s.classList.remove('loading', 'err'));
    vid.addEventListener('timeupdate', () => { if (vid.duration) bar.style.width = (vid.currentTime / vid.duration * 100) + '%'; });
    vid.addEventListener('ended', () => { if (st.at === i) go(i + 1); });
    vid.addEventListener('error', () => { if (!attached) return; s.classList.remove('loading'); s.classList.add('err'); if (st.at === i) setTimeout(() => { if (st.at === i && sfeed === st) go(i + 1); }, 1500); });
    vid.addEventListener('click', () => { vid.paused ? vid.play().catch(() => {}) : vid.pause(); });
    st.slides.push(s); sc.append(s);
  });

  const setActive = i => {
    if (i === st.at) return;
    st.at = i;
    st.slides.forEach((s, k) => { if (k !== i) s.pause(); if (Math.abs(k - i) > 1) s.release(); });
    st.slides[i].classList.add('loading'); st.slides[i].play();
    st.slides[i + 1]?.warm(); // the next clip starts loading for real,
    for (const k of [i + 2, i + 3]) if (items[k]) warmStream(items[k].id, 'video', 1080); // and the two after it get their lookup done
    window.history.replaceState(null, '', '#/shorts?v=' + items[i].id);
  };
  st.obs = new IntersectionObserver(es => { for (const e of es) if (e.isIntersecting && e.intersectionRatio >= 0.6) setActive(st.slides.indexOf(e.target)); }, { root: sc, threshold: [0.6] });
  st.slides.forEach(s => st.obs.observe(s));

  const bar = el('div', 'sbar');
  bar.append(iconBtn('back', 'Close Shorts', () => nav('/shorts'), 'ib sclose'));
  const mute = iconBtn('vol', 'Mute', () => { st.muted = !st.muted; st.slides.forEach(s => s.setMuted(st.muted)); mute.replaceChildren(icon(st.muted ? 'mute' : 'vol')); mute.setAttribute('aria-label', st.muted ? 'Unmute' : 'Mute'); }, 'ib sclose');
  bar.append(mute);
  const nav2 = el('div', 'snav');
  nav2.append(iconBtn('back', 'Previous Short', () => go(st.at - 1), 'ib snavb up'), iconBtn('back', 'Next Short', () => go(st.at + 1), 'ib snavb down'));
  root.append(sc, bar, nav2);
  document.body.append(root); document.body.classList.add('sf-open');
  st.key = e => {
    if (e.target.closest?.('input,textarea')) return;
    if (e.key === 'ArrowDown' || e.key === 'PageDown' || e.key === 'j') { e.preventDefault(); go(st.at + 1); }
    else if (e.key === 'ArrowUp' || e.key === 'PageUp' || e.key === 'k') { e.preventDefault(); go(st.at - 1); }
    else if (e.key === 'Escape') nav('/shorts');
    else if (e.key === ' ') { e.preventDefault(); st.slides[st.at]?.toggle(); }
    else if (e.key === 'm') mute.click();
  };
  addEventListener('keydown', st.key);
  requestAnimationFrame(() => go(startAt, false));
}

const musicCache = new Map(), MUSIC = { 'Top hits': 'top music hits', Tamil: 'tamil songs', Hindi: 'hindi songs', English: 'english pop songs', Lofi: 'lofi music', Rock: 'rock songs', 'Hip hop': 'hip hop songs', Classical: 'classical music', Devotional: 'devotional songs', Remix: 'remix songs' };
let musicSel = 'Top hits';
function viewMusic() {
  return page((root, setRefresh) => {
    const load = async () => {
      const sel = musicSel;
      if (musicCache.has(sel)) return;
      const data = (await fetchJson(`/api/search?q=${encodeURIComponent(MUSIC[sel])}&n=36`)).filter(x => !x.live);
      data.forEach(stash); musicCache.set(sel, data);
      if (curRoute() === 'music' && musicSel === sel) draw();
    };
    const draw = () => {
      root.textContent = '';
      const chips = el('div', 'chips');
      for (const g of Object.keys(MUSIC)) chips.append(button('chipb' + (g === musicSel ? ' on' : ''), g, null, () => { musicSel = g; draw(); load(); }));
      const head = el('div', 'ph'); head.append(el('h1', '', 'Music'));
      const items = musicCache.get(musicSel);
      if (items?.length) head.append(button('btn tonal sm has-icon', 'Play all', 'play', () => startList(items, 0, null)));
      root.append(chips, head);
      const g = el('div', 'ggrid');
      if (items) { if (items.length) g.append(...items.map((x, i) => gcard(x, { where: 'music' }, () => startList(items, i, null)))); else root.append(emptyBox('No songs', 'Try another genre.')); }
      else for (let i = 0; i < 8; i++) { const d = el('div', 'gcard sk'); d.innerHTML = '<div class="thumb"></div><div class="gi"><div><div class="l" style="width:90%;height:16px"></div><div class="l" style="width:50%"></div></div></div>'; g.append(d); }
      root.append(g);
    };
    draw(); setRefresh(draw); load();
  });
}

function viewSaved(kind) {
  const L = saved[kind];
  return page((root, setRefresh) => {
    const draw = () => {
      root.textContent = '';
      const items = L.get();
      const head = el('div', 'ph'); head.append(el('h1', '', L.name), el('span', 'sub', `${items.length} video${items.length === 1 ? '' : 's'}`));
      if (items.length) {
        head.append(button('btn tonal sm has-icon', 'Play all', 'play', () => startList(items, 0, null)));
        head.append(button('btn outlined sm has-icon', 'Clear all', 'delete', () => confirmDialog({
          title: `Clear ${L.name}?`, text: 'This empties the list. The videos themselves are not affected.', confirm: 'Clear',
          fn: () => { const old = L.get(); L.set([]); draw(); toast(`${L.name} cleared`, { label: 'Undo', fn: () => { L.set(old); draw(); } }); },
        })));
      }
      root.append(head);
      if (!items.length) return root.append(emptyBox(`${L.name} is empty`, L.empty));
      const g = el('div', 'ggrid'); g.append(...items.map((x, i) => gcard(x, { where: kind }, () => startList(items, i, null)))); root.append(g);
    };
    draw(); setRefresh(draw);
  });
}

/* ----- Network: play files from a folder on this device or a network drive ----- */
// A browser can't speak SMB, and the server may be on another network entirely. But a share that is mounted on THIS computer
// (a mapped drive like Z:, or \\nas\media opened in Explorer) is just a folder to the browser. The user picks it once; the
// folder handle is remembered, and files play straight from disk into a <video>. Nothing goes through the server.
const FS_OK = 'showDirectoryPicker' in window;
const NET_MEDIA = /\.(mp4|m4v|webm|mkv|mov|ogv|mp3|m4a|aac|ogg|oga|opus|wav|flac)$/i;
const netDb = {
  open: () => new Promise((res, rej) => { const r = indexedDB.open('lt-net', 1); r.onupgradeneeded = () => r.result.createObjectStore('dirs', { keyPath: 'id' }); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); }),
  async run(mode, fn) { const db = await this.open(); return new Promise((res, rej) => { const tx = db.transaction('dirs', mode), st = tx.objectStore('dirs'), q = fn(st); tx.oncomplete = () => res(q?.result); tx.onerror = () => rej(tx.error); }); },
  all() { return this.run('readonly', st => st.getAll()).then(x => x || []); },
  put(rec) { return this.run('readwrite', st => st.put(rec)); },
  del(id) { return this.run('readwrite', st => st.delete(id)); },
};
async function canRead(handle) {
  const o = { mode: 'read' };
  return (await handle.queryPermission(o)) === 'granted' || (await handle.requestPermission(o)) === 'granted'; // must come from a click
}
const sizeText = b => (b > 1e9 ? (b / 1e9).toFixed(1) + ' GB' : b > 1e6 ? Math.round(b / 1e6) + ' MB' : Math.max(1, Math.round(b / 1e3)) + ' KB');

/* ----- the local player: files from a folder or the helper ----- */
// Subtitles next to the video (movie.srt, movie.en.srt, .vtt, .ass) are found and offered, or you can add a file yourself.
// They are turned into WebVTT and shown by the browser's own caption renderer, so the CC button and styling work as usual.
const SUB_EXT = /\.(srt|vtt|ass|ssa)$/i;
const subsFor = (videoName, subs) => { const b = videoName.replace(/\.[^.]+$/, '').toLowerCase() + '.'; return subs.filter(x => x.name.toLowerCase().startsWith(b)); };
function attachSubs(files, subs) { // files: [{ name, ... }] gets .subs (the one video in a folder gets every subtitle file there)
  for (const f of files) f.subs = subsFor(f.name, subs);
  if (files.length === 1 && !files[0].subs.length) files[0].subs = subs.slice();
  return files;
}
function subLabel(videoName, subName) {
  const b = videoName.replace(/\.[^.]+$/, ''), nb = subName.replace(/\.[^.]+$/, '');
  const mid = nb.length > b.length ? nb.slice(b.length + 1) : ''; // "en", "eng.forced", ""
  if (!mid) return `Subtitles (${(/\.([^.]+)$/.exec(subName)?.[1] || '').toUpperCase()})`;
  const [first, ...rest] = mid.split('.');
  let nm = null;
  if (/^[a-z]{2,3}(-[a-z]{2,4})?$/i.test(first)) { try { nm = new Intl.DisplayNames(['en'], { type: 'language' }).of(first); } catch {} }
  return (nm && nm !== first ? nm : first) + (rest.length ? ` (${rest.join(' ')})` : '');
}
async function readText(buf) { // UTF-8, UTF-16 with a BOM, else Windows-1252 (old subtitle files)
  const u = new Uint8Array(buf);
  if (u[0] === 0xFF && u[1] === 0xFE) return new TextDecoder('utf-16le').decode(u);
  if (u[0] === 0xFE && u[1] === 0xFF) return new TextDecoder('utf-16be').decode(u);
  try { return new TextDecoder('utf-8', { fatal: true }).decode(u); } catch { return new TextDecoder('windows-1252').decode(u); }
}
const clockSec = t => { const m = /(?:(\d+):)?(\d+):(\d+)[.,](\d+)/.exec(t); return m ? (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + (+m[3]) + (+m[4].padEnd(3, '0').slice(0, 3)) / 1000 : NaN; };
const tidyCue = t => t.replace(/<(?!\/?[ibu]>)[^>]*>/gi, '').replace(/\{\\[^}]*\}/g, '').replace(/&(?!amp;|lt;|gt;|nbsp;)/g, '&amp;').replace(/-->/g, '→').trim();
function parseSubs(text, name) { // SRT, VTT or ASS/SSA -> [{ s, e, t }]
  text = text.replace(/^\uFEFF/, '').replace(/\r/g, '');
  const cues = [];
  if (/\.(ass|ssa)$/i.test(name)) {
    let fmt = null;
    for (const line of text.split('\n')) {
      if (/^Format:/i.test(line) && /start/i.test(line) && /text/i.test(line)) fmt = line.slice(7).split(',').map(x => x.trim().toLowerCase());
      else if (/^Dialogue:/i.test(line) && fmt) {
        const parts = line.slice(9).split(','), n = fmt.length, row = parts.slice(0, n - 1); row.push(parts.slice(n - 1).join(','));
        const g = k => row[fmt.indexOf(k)] || '', raw = g('text');
        if (/\{[^}]*\\p[1-9]/.test(raw)) continue; // vector drawings, not text
        const t = raw.replace(/\{[^}]*\}/g, '').replace(/\\[Nn]/g, '\n').replace(/\\h/g, ' ').trim(), s = clockSec(g('start')), e = clockSec(g('end'));
        if (t && e > s) cues.push({ s, e, t: tidyCue(t) });
      }
    }
    return cues.sort((x, y) => x.s - y.s);
  }
  for (const block of text.split(/\n\n+/)) {
    const ls = block.split('\n'), at = ls.findIndex(l => l.includes('-->'));
    if (at < 0) continue;
    const [a, rest] = ls[at].split('-->'), s = clockSec(a), e = clockSec(rest.trim().split(/\s+/)[0]), body = ls.slice(at + 1).join('\n');
    if (e > s && body.trim()) cues.push({ s, e, t: tidyCue(body) });
  }
  return cues;
}
const vttClock = x => { x = Math.max(0, x); return `${String(Math.floor(x / 3600)).padStart(2, '0')}:${String(Math.floor(x / 60) % 60).padStart(2, '0')}:${(x % 60).toFixed(3).padStart(6, '0')}`; };
const cuesToVtt = (cues, delay) => 'WEBVTT\n\n' + cues.map(c => `${vttClock(c.s + delay)} --> ${vttClock(c.e + delay)}\n${c.t}\n`).join('\n');

let lplayer = null;
function closeLocalPlayer() {
  if (!lplayer) return;
  lplayer.save();
  const { vid, root } = lplayer;
  vid.pause(); vid.removeAttribute('src'); vid.load();
  lplayer.urls.forEach(u => URL.revokeObjectURL(u));
  removeEventListener('keydown', lplayer.key); lplayer.ui?.stop();
  closePop(); document.body.append(pop); // the menu may be inside the player that is going away
  if (document.fullscreenElement === root) document.exitFullscreen().catch(() => {});
  root.remove(); lplayer = null;
}
function openLocalPlayer(list, start) {
  closeLocalPlayer();
  if (current && !v.paused) v.pause();
  const root = el('div', 'lplayer stage'), vid = el('video'), msg = el('div', 'lmsg'), hudEl = el('div', 'lhud'), panel = el('div', 'lpanel'), styleEl = el('style');
  vid.playsInline = true; panel.hidden = true; root.dataset.state = 'loading';
  // the same controls as the main player, copied from it
  const chrome = $('#chrome').cloneNode(true);
  for (const n of [chrome, ...chrome.querySelectorAll('[id]')]) n.removeAttribute('id');
  const c = name => chrome.querySelector(`[data-c="${name}"]`);
  c('close').hidden = c('list').hidden = false; c('theater').hidden = true;
  c('cc').disabled = c('seek').disabled = false; c('cc').setAttribute('aria-pressed', 'false');
  setIcon(c('fs'), 'full');
  const spin = el('div', 'center'); spin.append(el('span', 'spinner'));
  const st = lplayer = { root, vid, urls: [], i: start, key: null, ui: null, save: () => {}, subs: [], subSel: null, delay: 0, size: 100, fill: false };
  const pick = el('input'); pick.type = 'file'; pick.accept = '.srt,.vtt,.ass,.ssa'; pick.hidden = true;
  let hudT = 0;
  const hud = text => { hudEl.textContent = text; hudEl.classList.add('on'); clearTimeout(hudT); hudT = setTimeout(() => hudEl.classList.remove('on'), 1000); };
  const applySize = () => { styleEl.textContent = `.lplayer video::cue { font-size: ${st.size}%; }`; };

  // ---- where you stopped (per file name + size) ----
  const posMap = store.get('netpos', {});
  let keyNow = '', lastSave = 0;
  st.save = () => {
    if (!keyNow || !(vid.duration > 60)) return;
    delete posMap[keyNow];
    if (vid.currentTime > 10 && vid.currentTime < vid.duration - 15) posMap[keyNow] = { t: Math.floor(vid.currentTime), d: Math.floor(vid.duration) };
    const ks = Object.keys(posMap); for (const k of ks.slice(0, Math.max(0, ks.length - 300))) delete posMap[k];
    store.set('netpos', posMap);
  };
  vid.addEventListener('timeupdate', () => { if (Date.now() - lastSave > 5000) { lastSave = Date.now(); st.save(); } });
  vid.addEventListener('loadedmetadata', () => {
    const at = posMap[keyNow]?.t;
    if (at && at > 10 && at < vid.duration - 15) { vid.currentTime = at; hud(`Resumed at ${fmt(at)}`); }
    renderMenu();
  });

  // ---- subtitles ----
  const embedded = () => [...vid.textTracks].map((t, n) => ({ t, n })).filter(x => !x.t._lt && ['subtitles', 'captions'].includes(x.t.kind));
  const showCues = s => {
    s.track?.remove();
    const url = URL.createObjectURL(new Blob([cuesToVtt(s.cues, st.delay)], { type: 'text/vtt' })); st.urls.push(url);
    const t = document.createElement('track'); t.kind = 'subtitles'; t.label = s.label; t.src = url; vid.append(t);
    t.track._lt = true; t.track.mode = 'showing'; s.track = t;
  };
  const setSub = async id => { // id: null = off, 'sN' / 'uN' = a file, 'eN' = a track inside the video
    for (const t of vid.textTracks) t.mode = 'disabled';
    st.subSel = id;
    if (id == null) { hud('Captions off'); return renderMenu(); }
    let label = '';
    if (id[0] === 'e') { const t = vid.textTracks[+id.slice(1)]; if (t) { t.mode = 'showing'; label = t.label || t.language || 'Embedded'; } }
    else {
      const s = st.subs.find(x => x.id === id);
      try {
        if (!s.cues) s.cues = parseSubs(await readText(await (s.src.handle ? (await s.src.handle.getFile()).arrayBuffer() : fetch(s.src.url).then(r => r.arrayBuffer()))), s.src.name);
        if (!s.cues.length) throw new Error('no subtitle lines found in that file');
      } catch (e) { toast('Couldn’t read the subtitles: ' + e.message); st.subSel = null; return renderMenu(); }
      showCues(s); label = s.label;
    }
    hud('Subtitles: ' + label); renderMenu();
  };
  const setDelay = d => {
    st.delay = Math.round(d * 10) / 10;
    const s = st.subs.find(x => x.id === st.subSel); if (s?.cues) { for (const t of vid.textTracks) t.mode = 'disabled'; showCues(s); }
    hud(`Subtitle delay ${st.delay > 0 ? '+' : ''}${st.delay.toFixed(1)} s`); renderMenu();
  };
  pick.addEventListener('change', () => {
    const f = pick.files[0]; pick.value = ''; if (!f) return;
    const id = 'u' + st.subs.length; st.subs.push({ id, label: f.name, src: { name: f.name, handle: { getFile: async () => f } }, cues: null, track: null });
    setSub(id);
  });

  // ---- the settings menu: the same one as the main player's ----
  const setRate = r => { vid.playbackRate = r; hud(`Speed ${speedLabel(r)}`); renderMenu(); };
  const subChoices = () => [...st.subs.map(x => ({ id: x.id, label: x.label })), ...embedded().map(x => ({ id: 'e' + x.n, label: x.t.label || x.t.language || `Track ${x.n + 1}` }))];
  function renderMenu() { // after any change: the caption button, and the menu if it is open
    c('cc').setAttribute('aria-pressed', st.subSel != null);
    refreshPop();
  }
  const settingsRows = () => {
    const choices = subChoices(), at = vid.audioTracks ? [...vid.audioTracks] : [], trackName = (t, n) => t.label || (t.language ? langOf(t.language) : `Track ${n + 1}`);
    const stepper = (name, text, onMinus, onPlus) => { const d = el('div', 'lstep'); d.append(el('span', 'ytl', name), button('chipb', '−', null, onMinus), el('span', 'lval', text), button('chipb', '+', null, onPlus)); return d; };
    const captionExtra = () => {
      const d = el('div', 'ytextra');
      if (st.subSel) d.append(stepper('Delay', `${st.delay > 0 ? '+' : ''}${st.delay.toFixed(1)} s`, () => setDelay(st.delay - 0.1), () => setDelay(st.delay + 0.1)));
      d.append(stepper('Size', `${st.size}%`, () => { st.size = Math.max(50, st.size - 10); applySize(); renderMenu(); }, () => { st.size = Math.min(300, st.size + 10); applySize(); renderMenu(); }));
      return d;
    };
    return [
      speedRow(vid.playbackRate, setRate),
      { id: 'captions', icon: 'cc', label: 'Captions', value: choices.find(x => x.id === st.subSel)?.label || 'Off', extra: captionExtra,
        options: [{ label: 'Off', on: st.subSel == null, fn: () => setSub(null) }, ...choices.map(x => ({ label: x.label, on: st.subSel === x.id, fn: () => setSub(x.id) })), { label: 'Add a subtitle file…', fn: () => pick.click() }] },
      { id: 'track', icon: 'audio', label: 'Audio track', value: at.length > 1 ? trackName(at.find(t => t.enabled) || at[0], Math.max(0, at.findIndex(t => t.enabled))) : vid.audioTracks ? 'Only one' : 'Not supported by this browser',
        options: at.length > 1 ? at.map((t, n) => ({ label: trackName(t, n), on: t.enabled, fn: () => { at.forEach((x, k) => { x.enabled = k === n; }); hud('Audio: ' + trackName(t, n)); } })) : null },
      { icon: 'retry', label: 'Repeat this file', toggle: vid.loop, fn: () => { vid.loop = !vid.loop; } },
      { icon: 'full', label: 'Fill the screen (crop edges)', toggle: st.fill, fn: () => { st.fill = !st.fill; vid.style.objectFit = st.fill ? 'cover' : 'contain'; } },
      { node: el('div', 'lkeys', 'Space play · ← → 5 s · J L 10 s · ↑ ↓ volume · M mute · F full screen · C captions · Z X delay · [ ] speed · N P next/previous · Esc close') },
    ];
  };
  const langOf = code => { try { return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) || code; } catch { return code; } };

  // ---- the playlist panel ----
  function renderPanel() {
    panel.textContent = '';
    panel.append(el('div', 'lrt lpt', `In this folder (${list.length})`));
    list.forEach((it, k) => { const r = el('div', 'lpi' + (k === st.i ? ' on' : ''), it.name); r.onclick = () => load(k); panel.append(r); if (k === st.i) setTimeout(() => r.scrollIntoView({ block: 'nearest' }), 0); });
  }

  // ---- loading a file ----
  const load = async k => {
    st.save();
    st.i = Math.max(0, Math.min(list.length - 1, k)); msg.textContent = '';
    vid.querySelectorAll('track').forEach(t => t.remove()); st.subs = []; st.subSel = null; st.delay = 0;
    const item = list[st.i];
    c('title').textContent = item.name;
    c('sub').textContent = list.length > 1 ? `${st.i + 1} / ${list.length}` : '';
    c('prev').disabled = c('midprev').disabled = st.i === 0;
    c('next').disabled = c('midnext').disabled = st.i === list.length - 1;
    setState('loading');
    try {
      let size = item.size || 0;
      const oldUrl = st.urls.pop();
      if (item.url) vid.src = item.url; // from the helper: the browser streams it with Range requests
      else { const f = await item.handle.getFile(); size = f.size; const u = URL.createObjectURL(f); st.urls.push(u); vid.src = u; } // read from disk as asked, so seeking is instant
      if (oldUrl) URL.revokeObjectURL(oldUrl);
      keyNow = item.name + '|' + size;
      st.subs = (item.subs || []).map((x, n) => ({ id: 's' + n, label: subLabel(item.name, x.name), src: x, cues: null, track: null }));
      vid.play().catch(() => {});
    } catch (e) { msg.textContent = 'Couldn’t open this file: ' + e.message; setState('paused'); }
    renderMenu(); renderPanel();
  };
  vid.addEventListener('ended', () => { delete posMap[keyNow]; store.set('netpos', posMap); keyNow = ''; if (st.i + 1 < list.length) load(st.i + 1); });
  vid.addEventListener('error', () => { setState('paused'); msg.textContent = 'Your browser can’t play this file format (MP4, WebM, MP3, M4A, FLAC, WAV and OGG usually work; some MKV and HEVC files do not).'; });
  vid.addEventListener('ratechange', renderMenu);
  vid.textTracks.addEventListener('addtrack', renderMenu);

  // ---- the controls and the keyboard ----
  function setState(s) {
    root.dataset.state = s;
    for (const n of ['pp', 'midpp']) setIcon(c(n), s === 'playing' ? 'pause' : 'play');
    st.ui?.wake();
  }
  vid.addEventListener('playing', () => setState('playing'));
  vid.addEventListener('waiting', () => setState('loading'));
  vid.addEventListener('pause', () => { if (!vid.ended) setState('paused'); });
  vid.addEventListener('canplay', () => { if (vid.paused) setState('paused'); });

  let dragging = false;
  const bar = c('seek');
  const render = () => {
    const length = isFinite(vid.duration) ? vid.duration : 0, at = dragging ? Number(bar.value) : vid.currentTime;
    bar.max = length; if (!dragging) bar.value = at;
    let loaded = 0;
    for (let i = 0; i < vid.buffered.length; i++) if (vid.buffered.start(i) <= vid.currentTime + .5) loaded = Math.max(loaded, vid.buffered.end(i));
    const pct = x => (length ? Math.min(100, x / length * 100) : 0) + '%';
    c('seekwrap').style.setProperty('--p', pct(at)); c('seekwrap').style.setProperty('--b', pct(loaded));
    showTime(c('time'), at, length);
  };
  for (const ev of ['timeupdate', 'progress', 'durationchange', 'emptied']) vid.addEventListener(ev, render);
  bar.addEventListener('input', () => { dragging = true; render(); });
  bar.addEventListener('change', () => { dragging = false; vid.currentTime = Number(bar.value); render(); });
  c('seekwrap').addEventListener('pointermove', e => {
    const r = c('seekwrap').getBoundingClientRect(), f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), tip = c('tip');
    tip.textContent = fmt(f * (vid.duration || 0)); tip.style.left = Math.min(r.width - 24, Math.max(24, f * r.width)) + 'px';
  });

  const toggle = () => (vid.paused ? vid.play().catch(() => {}) : vid.pause());
  const jump = d => { if (!isFinite(vid.duration)) return false; vid.currentTime = Math.max(0, Math.min(vid.duration, vid.currentTime + d)); return true; };
  const fs = () => toggleFullscreen(root, vid);
  const cycle = () => { if (st.subSel != null) setSub(null); else if (subChoices().length) setSub(subChoices()[0].id); else pick.click(); };
  const volume = x => {
    vid.volume = x; vid.muted = x === 0; c('vol').value = x; setIcon(c('mute'), x === 0 ? 'mute' : 'vol'); store.set('vol', x);
  };
  volume(store.get('vol', 1));
  let beforeMute = 1;
  c('mute').onclick = () => { if (vid.volume > 0) { beforeMute = vid.volume; volume(0); } else volume(beforeMute || 1); };
  c('vol').oninput = e => volume(Number(e.target.value));
  c('close').onclick = closeLocalPlayer;
  c('prev').onclick = c('midprev').onclick = () => load(st.i - 1);
  c('next').onclick = c('midnext').onclick = () => load(st.i + 1);
  c('cc').onclick = cycle;
  c('gear').onclick = () => { panel.hidden = true; menuIsOpen(c('gear')) ? closePop() : ytMenu(c('gear'), settingsRows); };
  c('list').onclick = () => { closePop(); panel.hidden = !panel.hidden; if (!panel.hidden) renderPanel(); st.ui.wake(); };
  c('fs').onclick = fs;
  root.addEventListener('click', e => { if (!e.target.closest('button, .lpanel')) panel.hidden = true; });

  st.key = e => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const k = e.key;
    if (k === 'Escape') { if (!panel.hidden) panel.hidden = true; else if (!document.fullscreenElement) closeLocalPlayer(); return; }
    else if (k === ' ' || k === 'k') { if (e.target.matches('button, input')) return; e.preventDefault(); st.ui.toggle(); }
    else if (k === 'ArrowRight') { e.preventDefault(); st.ui.seekBy(5); } else if (k === 'ArrowLeft') { e.preventDefault(); st.ui.seekBy(-5); }
    else if (k === 'l') st.ui.seekBy(10); else if (k === 'j') st.ui.seekBy(-10);
    else if (k === 'ArrowUp') { e.preventDefault(); volume(Math.min(1, vid.volume + 0.05)); hud(`Volume ${Math.round(vid.volume * 100)}%`); }
    else if (k === 'ArrowDown') { e.preventDefault(); volume(Math.max(0, vid.volume - 0.05)); hud(`Volume ${Math.round(vid.volume * 100)}%`); }
    else if (k === 'm') c('mute').click();
    else if (k === 'f') fs();
    else if (k === 'c') cycle();
    else if (k === 'z') setDelay(st.delay - 0.1); else if (k === 'x') setDelay(st.delay + 0.1);
    else if (k === '[' || k === ']') { const at = RATES.indexOf(vid.playbackRate); const n = RATES[Math.max(0, Math.min(RATES.length - 1, (at < 0 ? RATES.indexOf(1) : at) + (k === ']' ? 1 : -1)))]; setRate(n); }
    else if (k === 'n') load(st.i + 1); else if (k === 'p') load(st.i - 1);
    else return;
    st.ui.wake();
  };
  addEventListener('keydown', st.key);
  root.append(vid, spin, hudEl, msg, chrome, panel, pick, styleEl); document.body.append(root); applySize(); layoutChrome(root);
  st.ui = playerChrome(root, vid, { toggle, skip: jump, busy: () => !pop.hidden || !panel.hidden });
  load(start);
}

/* The helper: a small program on the client's computer that speaks SMB for the page (see /helper in the project). */
const helperCfg = () => store.get('helper', { port: 47821, token: '' });
const helperBase = () => `http://127.0.0.1:${helperCfg().port}`;
async function helperGet(path, init) {
  const sep = path.includes('?') ? '&' : '?';
  const r = await fetch(`${helperBase()}${path}${sep}t=${encodeURIComponent(helperCfg().token)}`, init);
  if (!r.ok) { const e = new Error((await r.text().catch(() => '')) || r.statusText); e.status = r.status; throw e; }
  return r.json();
}
const helperFileUrl = (sess, path) => `${helperBase()}/file?s=${sess.id}&share=${encodeURIComponent(sess.share)}&path=${encodeURIComponent(path)}&t=${encodeURIComponent(helperCfg().token)}`;
function askFields(title, fields, confirm) { // small form dialog: [{ id, label, type, value }] -> { id: value } or null
  return new Promise(resolve => {
    const box = [], inputs = {};
    for (const f of fields) { const w = el('div', 'field'), i = el('input'); i.type = f.type || 'text'; i.id = 'af_' + f.id; i.placeholder = ' '; i.value = f.value || ''; i.autocomplete = f.type === 'password' ? 'current-password' : 'off'; const l = el('label', '', f.label); l.htmlFor = i.id; w.append(i, l); box.push(w); inputs[f.id] = i; }
    if (fields.note) box.push(el('p', 'note', fields.note));
    let done = false;
    const finish = v => { if (!done) { done = true; resolve(v); } };
    const ok = () => { finish(Object.fromEntries(Object.entries(inputs).map(([k, i]) => [k, i.value]))); };
    Object.values(inputs).forEach(i => i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); ok(); dlg.close(); } }));
    dlg.addEventListener('close', () => finish(null), { once: true });
    openDialog({ title, body: box, actions: [{ label: 'Cancel', fn: () => { finish(null); } }, { label: confirm, primary: true, fn: ok }] });
    Object.values(inputs)[0]?.focus();
  });
}

/* video thumbnails for the grid: one frame from a hidden <video>, two at a time, cached */
const thumbCache = new Map(), thumbQ = [];
let thumbBusy = 0;
function thumbPump() {
  while (thumbBusy < 2 && thumbQ.length) { const j = thumbQ.shift(); thumbBusy++; makeThumb(j).catch(() => {}).finally(() => { thumbBusy--; thumbPump(); }); }
}
// one frame of a video as a data URL: seek to ~10% in; if the browser can't seek that file, use the first frame instead
async function grabFrame(url, seek) {
  const vid = document.createElement('video'); vid.muted = true; vid.preload = 'metadata'; vid.src = url;
  try {
    await new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error('timeout')), 9000);
      vid.onerror = () => { clearTimeout(t); rej(new Error('unsupported')); };
      if (seek) { vid.onloadedmetadata = () => { vid.currentTime = Math.min((Number.isFinite(vid.duration) ? vid.duration : 10) * 0.1, 20); }; vid.onseeked = () => { clearTimeout(t); res(); }; }
      else vid.onloadeddata = () => { clearTimeout(t); res(); };
    });
    const c = document.createElement('canvas'), w = 320; c.width = w; c.height = Math.round(w * vid.videoHeight / vid.videoWidth) || 180;
    const g = c.getContext('2d'); g.drawImage(vid, 0, 0, c.width, c.height);
    const px = g.getImageData(0, 0, c.width, c.height).data; let sum = 0;
    for (let i = 0; i < px.length; i += 16) sum += px[i] + px[i + 1] + px[i + 2];
    if (sum / (px.length / 16 * 3) < 8) throw new Error('blank frame'); // a black first frame says nothing: keep the icon
    return c.toDataURL('image/jpeg', 0.7);
  } finally { vid.removeAttribute('src'); vid.load(); }
}
async function makeThumb({ img, key, open }) {
  if (!img.isConnected) return;
  const { url, done } = await open();
  try {
    let data; try { data = await grabFrame(url, true); } catch { data = await grabFrame(url, false); }
    thumbCache.set(key, data);
    if (img.isConnected) { img.src = data; img.classList.add('on'); }
  } finally { done?.(); }
}
const thumbObs = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(es => {
  for (const e of es) if (e.isIntersecting) { thumbObs.unobserve(e.target); const j = e.target._thumb; if (j) { thumbQ.push(j); thumbPump(); } }
}, { rootMargin: '200px' });

function viewNetwork() {
  return page((root, setRefresh) => {
    let roots = [];                                   // folders you added
    let browse = null;                                // null = the home view
    let smb = { stage: 'idle', hosts: [], msg: '' };  // network search: idle | searching | ready | signing | error
    let helperUp = null;                              // null = not checked yet
    let q = '', sort = store.get('netsort', 'name'), mode = store.get('netview', 'list');
    let posCache = {};
    const resumeOf = f => (f.size ? posCache[f.name + '|' + f.size] : null);

    // ---------------- helpers ----------------
    const sortEntries = list => list.slice().sort((a, b) => (b.dir - a.dir) || (sort === 'size' && !a.dir ? (b.size || 0) - (a.size || 0) : 0) || a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));
    const kind = n => (/\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)$/i.test(n) ? 'audio' : 'video');
    const label = (iconName, text, cls = 'btn tonal sm has-icon', fn) => button(cls, text, iconName, fn);

    // ---------------- folders on this computer ----------------
    async function addRoot() {
      try {
        const handle = await showDirectoryPicker({ id: 'lt-net', mode: 'read' });
        for (const r of roots) if (await r.handle.isSameEntry(handle)) return toast('That folder is already here');
        const rec = { id: crypto.randomUUID(), name: handle.name, handle };
        await netDb.put(rec); roots.push(rec); draw();
      } catch (e) { if (e.name !== 'AbortError') toast('Couldn’t add the folder: ' + e.message); }
    }
    async function openFs(stack) {
      const frame = stack[stack.length - 1];
      try { if (!(await canRead(frame.handle))) return toast('Access to that folder was not allowed'); } catch (e) { return toast('Couldn’t open the folder: ' + e.message); }
      browse = { kind: 'fs', title: stack[0].name, crumbs: [{ label: 'Network', go: () => { browse = null; draw(); } }, ...stack.map((f, i) => ({ label: f.name, go: () => openFs(stack.slice(0, i + 1)) }))], entries: null, loading: true };
      q = ''; draw();
      const mine = browse;
      try {
        const list = [], subs = [];
        for await (const [name, h] of frame.handle.entries()) {
          if (name.startsWith('.')) continue;
          if (h.kind === 'directory') list.push({ name, handle: h, dir: true });
          else if (NET_MEDIA.test(name)) list.push({ name, handle: h, dir: false });
          else if (SUB_EXT.test(name)) subs.push({ name, handle: h });
        }
        attachSubs(list.filter(x => !x.dir), subs);
        mine.entries = list; mine.loading = false; mine.enter = e => openFs([...stack, { name: e.name, handle: e.handle }]);
        if (browse === mine) drawList();
        let t = 0; const later = () => { clearTimeout(t); t = setTimeout(() => { if (browse === mine) drawList(); }, 120); }; // sizes arrive one by one
        for (const f of list) if (!f.dir) f.handle.getFile().then(x => { f.size = x.size; later(); }).catch(() => {});
      } catch (e) { mine.loading = false; mine.error = 'Couldn’t read this folder: ' + e.message; if (browse === mine) draw(); }
    }

    // ---------------- network devices (through the helper) ----------------
    async function probeHelper() {
      try { await fetch(helperBase() + '/ping').then(r => r.json()); helperUp = true; } catch { helperUp = false; }
      return helperUp;
    }
    async function findDevices() {
      smb = { stage: 'searching', hosts: [], msg: '' }; draw();
      if (!(await probeHelper())) { smb = { stage: 'idle', hosts: [], msg: '' }; return draw(); }
      if (!helperCfg().token) {
        const r = await askFields('Pair with the helper', [{ id: 'code', label: 'Pairing code (shown in the helper window)' }], 'Pair');
        if (!r?.code?.trim()) { smb = { stage: 'idle', hosts: [], msg: '' }; return draw(); }
        store.set('helper', { ...helperCfg(), token: r.code.trim() });
      }
      try { smb = { stage: 'ready', hosts: await helperGet('/discover'), msg: '' }; }
      catch (e) {
        if (e.status === 401) store.set('helper', { ...helperCfg(), token: '' });
        smb = { stage: 'error', hosts: [], msg: e.status === 401 ? 'The pairing code was not accepted. Search again and type the code the helper shows.' : 'Couldn’t search the network: ' + e.message };
      }
      draw();
    }
    async function signIn(host) {
      const users = store.get('helperUsers', {});
      const r = await askFields(`Sign in to ${host}`, [{ id: 'user', label: 'User name (blank = guest)', value: users[host] || '' }, { id: 'pass', label: 'Password', type: 'password' }], 'Sign in');
      if (!r) return;
      const prev = smb; smb = { ...smb, stage: 'signing', host }; draw();
      try {
        const res = await helperGet('/connect', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ host, user: r.user, pass: r.pass }) });
        store.set('helperUsers', { ...users, [host]: r.user.trim() });
        smb = { ...prev, stage: 'ready', msg: '' };
        openShares(host, res.session, res.shares);
      } catch (e) { smb = { ...prev, stage: 'ready', msg: `Couldn’t sign in to ${host}: ${e.message}` }; draw(); }
    }
    const homeCrumb = { label: 'Network', go: () => { browse = null; draw(); } };
    function openShares(host, session, shares) {
      browse = { kind: 'shares', title: host, crumbs: [homeCrumb, { label: host, go: () => openShares(host, session, shares) }], shares, host, session };
      q = ''; draw();
    }
    async function openSmb(host, session, shares, share, path) {
      const sess = { id: session, share };
      const crumbs = [homeCrumb, { label: host, go: () => openShares(host, session, shares) }, { label: share, go: () => openSmb(host, session, shares, share, []) },
        ...path.map((p, i) => ({ label: p, go: () => openSmb(host, session, shares, share, path.slice(0, i + 1)) }))];
      browse = { kind: 'smb', title: host, crumbs, entries: null, loading: true };
      q = ''; draw();
      const mine = browse;
      try {
        const list = await helperGet(`/ls?s=${session}&share=${encodeURIComponent(share)}&path=${encodeURIComponent(path.join('/'))}`);
        const url = n => helperFileUrl(sess, [...path, n].join('/'));
        const subs = list.filter(x => SUB_EXT.test(x.name)).map(x => ({ name: x.name, url: url(x.name) }));
        const items = list.filter(x => x.dir || NET_MEDIA.test(x.name)).map(x => x.dir ? { name: x.name, dir: true } : { name: x.name, dir: false, size: x.size, url: url(x.name) });
        attachSubs(items.filter(x => !x.dir), subs);
        mine.entries = items; mine.loading = false; mine.enter = e => openSmb(host, session, shares, share, [...path, e.name]);
      } catch (e) { mine.loading = false; mine.error = 'Couldn’t read this folder: ' + e.message; }
      if (browse === mine) draw();
    }

    // ---------------- drawing: the home view ----------------
    const locCard = (iconName, title, sub, fn, extra) => {
      const c = el('div', 'loc pressable'); const ic = el('div', 'locic'); ic.append(icon(iconName));
      const t = el('div', 'loct'); t.append(el('div', 'locn', title), el('div', 'locs', sub));
      c.append(ic, t); if (extra) c.append(extra); activate(c, fn); return c;
    };
    const ghostCard = (iconName, title, sub, fn) => { const c = locCard(iconName, title, sub, fn); c.classList.add('ghost'); return c; };
    const sectionHead = (title, ...actions) => { const h2 = el('h2', 'nh'); h2.append(document.createTextNode(title)); const a = el('span', 'nha'); a.append(...actions); h2.append(a); return h2; };

    function drawHome() {
      // folders
      const g1 = el('div', 'locs-grid');
      if (!FS_OK) g1.append(emptyBox('Needs Chrome or Edge', 'Picking a folder works in Chrome and Edge on a computer.'));
      else {
        for (const r of roots) g1.append(locCard('folder', r.name, 'Folder on this computer', () => openFs([{ name: r.name, handle: r.handle }]),
          iconBtn('close', `Remove ${r.name} from the list`, e => { e.stopPropagation(); netDb.del(r.id); roots = roots.filter(x => x !== r); draw(); toast('Removed from the list (your files are untouched)'); }, 'ib sm locx')));
        g1.append(ghostCard('add', roots.length ? 'Add another folder' : 'Add a folder', 'A folder, or a network drive you mapped (like Z:)', addRoot));
      }
      root.append(sectionHead('Folders'), g1);

      // devices
      const status = helperUp === null ? '' : helperUp ? 'Helper running' : 'Helper not running';
      const chip = status ? el('span', 'nchip ' + (helperUp ? 'ok' : 'off'), status) : null;
      root.append(sectionHead('Network devices', ...(chip ? [chip] : []), ...(smb.stage === 'ready' || smb.stage === 'error' ? [button('btn text sm has-icon', 'Search again', 'retry', findDevices)] : [])));
      const g2 = el('div', 'locs-grid');
      if (smb.stage === 'searching') for (let i = 0; i < 2; i++) { const d = el('div', 'loc sk'); d.innerHTML = '<div class="locic"></div><div class="loct"><div class="l" style="width:60%;height:16px"></div><div class="l" style="width:40%"></div></div>'; g2.append(d); }
      else if (smb.stage === 'signing') g2.append(el('p', 'note', `Signing in to ${smb.host}…`));
      else if (smb.stage === 'ready') {
        if (smb.msg) root.append(el('p', 'note nwarn', smb.msg));
        for (const h of smb.hosts) g2.append(locCard('network', h.ip, h.this_computer ? 'This computer · file sharing' : 'File sharing', () => signIn(h.ip)));
        if (!smb.hosts.length) g2.append(el('p', 'note', 'No computer with file sharing turned on was found on this network.'));
      } else if (smb.stage === 'error') g2.append(el('p', 'note nwarn', smb.msg));
      else if (helperUp === false) {
        const box = el('div', 'nsetup'), cmd = `kinora-helper.exe --origin ${location.origin}`;
        box.append(el('h3', '', 'Searching the network needs the Kinora helper'),
          el('p', 'note', 'A browser can’t talk to file shares by itself, so a small program on this computer does it. Start it with the command below, then check again.'));
        const code = el('code', 'ncode', cmd); box.append(code);
        const row = el('div', 'nrow2'); row.append(button('btn tonal sm has-icon', 'Check again', 'retry', async () => { await probeHelper(); draw(); }), button('btn text sm', 'Copy command', null, () => { navigator.clipboard?.writeText(cmd).then(() => toast('Command copied')); }));
        box.append(row); g2.append(box);
      } else g2.append(ghostCard('network', 'Find network', 'Search this network for shared computers and NAS boxes', findDevices));
      root.append(g2);
    }

    // ---------------- drawing: browsing a folder or share ----------------
    let listBox = null, playBtn = null;
    function playable() { return (browse.entries || []).filter(x => !x.dir); }
    function drawList() {
      if (!listBox) return;
      listBox.textContent = ''; posCache = store.get('netpos', {});
      listBox.className = 'nbody';
      if (browse.loading) return listBox.append(el('p', 'note', 'Reading the folder…'));
      if (browse.error) return listBox.append(emptyBox('Couldn’t open this', browse.error));
      const all = browse.entries || [], needle = q.trim().toLowerCase();
      if (playBtn) playBtn.hidden = !all.some(x => !x.dir);
      const files = all.filter(x => !x.dir), shown = sortEntries(all.filter(x => !needle || x.name.toLowerCase().includes(needle)));
      if (!all.length) return listBox.append(emptyBox('Nothing to play here', 'This folder has no video or music files (or sub-folders).'));
      if (!shown.length) return listBox.append(emptyBox('No matches', `Nothing here matches “${q}”.`));
      const play = e => openLocalPlayer(files, files.indexOf(e));
      const wrap = el('div', mode === 'grid' ? 'ngrid' : 'nlist');
      for (const e of shown) {
        const r = e.dir ? null : resumeOf(e);
        const pct = r?.d ? Math.min(100, r.t / r.d * 100) : 0;
        if (mode === 'grid') {
          const c = el('div', 'ncard pressable'), th = el('div', 'nthumb' + (e.dir ? ' dir' : ''));
          th.append(icon(e.dir ? 'folder' : kind(e.name) === 'audio' ? 'music' : 'video', 'nbig'));
          if (!e.dir && kind(e.name) === 'video') {
            const img = el('img'); img.alt = ''; th.append(img);
            const key = e.name + '|' + (e.size || '');
            if (thumbCache.has(key)) { img.src = thumbCache.get(key); img.classList.add('on'); }
            else if (thumbObs) { th._thumb = { img, key, open: e.url ? async () => ({ url: e.url }) : async () => { const f = await e.handle.getFile(); const u = URL.createObjectURL(f); return { url: u, done: () => URL.revokeObjectURL(u) }; } }; thumbObs.observe(th); }
          }
          if (pct) { const rb = el('div', 'resume'), ri = el('i'); ri.style.width = pct + '%'; rb.append(ri); th.append(rb); }
          const t = el('div', 'nct'); t.append(el('div', 'nn', e.name), el('div', 'ns', e.dir ? 'Folder' : [sizeText(e.size || 0), r ? `Resume ${fmt(r.t)}` : ''].filter(Boolean).join(' · ')));
          c.append(th, t); activate(c, e.dir ? () => browse.enter(e) : () => play(e)); wrap.append(c);
        } else {
          const row = el('div', 'nrow pressable'), ic = el('div', 'nic2' + (e.dir ? ' dir' : '')); ic.append(icon(e.dir ? 'folder' : kind(e.name) === 'audio' ? 'music' : 'video'));
          const t = el('div', 'nct'); t.append(el('div', 'nn', e.name));
          if (!e.dir && (r || e.size)) t.append(el('div', 'ns', [r ? `Resume ${fmt(r.t)}` : '', e.size ? sizeText(e.size) : ''].filter(Boolean).join(' · ')));
          row.append(ic, t); if (pct) { const rb = el('div', 'rrow'), ri = el('i'); ri.style.width = pct + '%'; rb.append(ri); row.append(rb); }
          activate(row, e.dir ? () => browse.enter(e) : () => play(e)); wrap.append(row);
        }
      }
      listBox.className = 'nbody'; listBox.append(wrap);
      const nd = all.filter(x => x.dir).length, nv = files.length;
      listBox.append(el('div', 'nfoot', [nv ? `${nv} file${nv > 1 ? 's' : ''}` : '', nd ? `${nd} folder${nd > 1 ? 's' : ''}` : '', files.some(f => f.size) ? sizeText(files.reduce((n, f) => n + (f.size || 0), 0)) : ''].filter(Boolean).join(' · ')));
    }
    function drawBrowse() {
      const crumbs = el('nav', 'ncrumbs'); crumbs.setAttribute('aria-label', 'Path');
      browse.crumbs.forEach((c, i) => {
        if (i) crumbs.append(el('span', 'nsep', '›'));
        const last = i === browse.crumbs.length - 1, b = el('button', 'ncrumb' + (last ? ' cur' : ''), c.label); b.type = 'button'; if (!last) b.onclick = c.go; else b.disabled = true; crumbs.append(b);
      });
      const back = iconBtn('back', 'Back', () => { const p = browse.crumbs[browse.crumbs.length - 2]; (p ? p.go : () => { browse = null; draw(); })(); }, 'ib');
      const top = el('div', 'nbar'); top.append(back, crumbs);
      root.append(top);
      if (browse.kind === 'shares') {
        const g = el('div', 'locs-grid');
        if (!browse.shares.length) g.append(emptyBox('No shared folders', 'This server has no shares you can see with that account.'));
        for (const n of browse.shares) g.append(locCard('folder', n, 'Shared folder', () => openSmb(browse.host, browse.session, browse.shares, n, [])));
        return root.append(g);
      }
      const tools = el('div', 'ntools');
      const f = el('div', 'nfind'); f.append(icon('search2')); const inp = el('input'); inp.type = 'search'; inp.placeholder = 'Filter this folder'; inp.value = q; inp.setAttribute('aria-label', 'Filter this folder'); inp.oninput = () => { q = inp.value; drawList(); }; f.append(inp);
      const sortSeg = seg([{ v: 'name', label: 'Name' }, { v: 'size', label: 'Size' }], sort, x => { sort = x; store.set('netsort', x); draw(); });
      const viewSeg = el('div', 'nview'); viewSeg.append(iconBtn('list', 'List view', () => { mode = 'list'; store.set('netview', mode); draw(); }, 'ib sm' + (mode === 'list' ? ' on' : '')), iconBtn('grid', 'Grid view', () => { mode = 'grid'; store.set('netview', mode); draw(); }, 'ib sm' + (mode === 'grid' ? ' on' : '')));
      playBtn = button('btn filled sm has-icon', 'Play all', 'play', () => openLocalPlayer(playable(), 0));
      playBtn.hidden = !playable().length;
      tools.append(f, sortSeg, viewSeg, playBtn);
      root.append(tools);
      listBox = el('div', 'nbody'); root.append(listBox); drawList();
    }

    function draw() {
      root.textContent = ''; listBox = null; playBtn = null;
      const head = el('div', 'ph'); head.append(el('h1', '', 'Network'));
      if (!browse) head.append(el('span', 'sub', 'Play videos and music from your own computers and drives. Files play right here and never go through the Kinora server.'));
      root.append(head);
      if (browse) drawBrowse(); else drawHome();
    }
    draw(); setRefresh(draw);
    if (FS_OK) netDb.all().then(r => { roots = r; if (!browse) draw(); }).catch(() => {});
    // is the helper there? if it is, and already paired, search straight away
    probeHelper().then(up => { if (!browse && smb.stage === 'idle') { if (up && helperCfg().token) findDevices(); else draw(); } });
  });
}

/* ----- search results ----- */
const searchCache = new Map(); // q -> { items, n }
function viewResults(q) {
  return page((root, setRefresh) => {
    if (!q) return root.append(emptyBox('Search for something', 'Type in the bar above and press Enter.'));
    document.title = `${q} · Kinora`;
    const list = el('div', 'rlist');
    const more = el('div', 'morebtn');
    const head = el('div', 'ph'); head.append(el('h1', '', `Results for “${q}”`));
    root.append(head, list, more);

    const draw = () => {
      const st = searchCache.get(q);
      list.textContent = ''; more.textContent = '';
      if (!st || st.loading) {
        for (let i = 0; i < 6; i++) { const d = el('div', 'rcard sk'); d.innerHTML = '<div class="thumb"></div><div class="info"><div class="l" style="width:80%;height:18px"></div><div class="l" style="width:30%"></div><div class="l" style="width:50%"></div></div><span></span>'; list.append(d); }
        return;
      }
      if (st.error) return list.append(emptyBox('Search failed', st.error, { label: 'Try again', fn: () => { searchCache.delete(q); run(20); } }));
      if (!st.items.length) return list.append(emptyBox('No results', 'Try different words.'));
      list.append(...st.items.map(it => rcard(it, { where: 'results' }, () => openVideo(it))));
      if (st.items.length >= st.n && st.n < 60) more.append(button('btn tonal', 'Show more results', null, () => run(Math.min(60, st.n + 20))));
    };
    async function run(n) {
      const prev = searchCache.get(q);
      searchCache.set(q, { ...(prev || { items: [] }), n, loading: !prev?.items?.length });
      draw();
      if (prev?.items?.length) more.append(button('btn tonal', 'Loading…', null, null)).disabled = true;
      try {
        const r = await fetch(`/api/search?q=${encodeURIComponent(q)}&n=${n}`);
        const data = await r.json();
        if (!r.ok) throw new Error(data.error || r.statusText);
        data.forEach(stash);
        searchCache.set(q, { items: data, n });
      } catch (e) { searchCache.set(q, { items: prev?.items || [], n: prev?.n || 20, error: prev?.items?.length ? null : e.message }); }
      if (parseHash().p.get('q') === q && curRoute() === 'results') draw();
    }
    setRefresh(draw);
    if (searchCache.get(q)?.items?.length) draw(); else run(20);
  });
}

/* ----- history ----- */
function removeHistory(id) {
  const old = history.slice();
  history = history.filter(h => h.id !== id); store.set('history', history); refreshView();
  toast('Removed from history', { label: 'Undo', fn: () => { history = old; store.set('history', history); refreshView(); } });
}
function viewHistory() {
  return page((root, setRefresh) => {
    let filter = '';
    const draw = () => {
      root.textContent = '';
      const head = el('div', 'ph'); head.append(el('h1', '', 'Watch history'));
      head.append(button('btn outlined sm has-icon', 'Clear all', 'delete', () => history.length && confirmDialog({
        title: 'Clear watch history?', text: 'This removes the list of videos you have played. Resume positions are kept.', confirm: 'Clear history',
        fn: () => { const old = history; history = []; store.set('history', history); refreshView(); toast('History cleared', { label: 'Undo', fn: () => { history = old; store.set('history', history); refreshView(); } }); },
      })));
      root.append(head);
      if (!settings.history) {
        const n = el('div', 'paused-note'); n.append(el('span', '', 'Watch history is paused. New videos are not being added.'), button('btn text sm', 'Turn on', null, () => { setGlobal('history', true); draw(); }));
        n.firstChild.style.flex = '1'; root.append(n);
      }
      const f = el('div', 'hfilter'), fld = el('div', 'field'), inp = el('input'); inp.type = 'text'; inp.id = 'hf'; inp.placeholder = ' '; inp.value = filter;
      const lb = el('label', '', 'Search watch history'); lb.htmlFor = 'hf'; fld.append(inp, lb); f.append(fld); root.append(f);
      const listEl = el('div'); root.append(listEl);
      const fill = () => {
        listEl.textContent = '';
        const q = filter.trim().toLowerCase();
        const rows = history.filter(h => !q || h.title.toLowerCase().includes(q) || (h.channel || '').toLowerCase().includes(q));
        if (!rows.length) return listEl.append(history.length ? emptyBox('No matches', 'Nothing in your history matches that.') : emptyBox('Nothing here yet', 'Videos you play will be listed here, newest first.'));
        let day = '';
        for (const h of rows) {
          const d = dayLabel(h.at);
          if (d !== day) { day = d; listEl.append(el('div', 'hday', d)); }
          listEl.append(rcard(h, { where: 'history' }, () => openVideo(h)));
        }
      };
      inp.oninput = () => { filter = inp.value; fill(); };
      fill();
    };
    draw(); setRefresh(draw);
  });
}

/* ----- playlists ----- */
async function newPlaylist() { const n = await promptName({ title: 'New playlist', confirm: 'Create' }); if (n) nav('/playlist/' + createPlaylist(n).id); }
function viewPlaylists() {
  return page((root, setRefresh) => {
    const draw = () => {
      root.textContent = '';
      const head = el('div', 'ph'); head.append(el('h1', '', 'Playlists'));
      root.append(head);
      if (!playlists.length) root.append(emptyBox('No playlists yet', 'Group videos you like. Use “Save” on any video, or save the playing queue.', { label: 'New playlist', fn: newPlaylist }));
      else { const g = el('div', 'ggrid'); g.append(...playlists.map(plCard)); root.append(g); }
      const fab = el('button', 'fab'); fab.type = 'button'; fab.append(icon('add'), document.createTextNode('New playlist')); fab.onclick = newPlaylist; root.append(fab);
    };
    draw(); setRefresh(draw);
  });
}
function viewPlaylist(id) {
  return page((root, setRefresh) => {
    const draw = () => {
      root.textContent = '';
      const pl = plById(id);
      if (!pl) return root.append(emptyBox('Playlist not found', 'It may have been deleted.', { label: 'All playlists', fn: () => nav('/playlists') }));
      document.title = `${pl.name} · Kinora`;
      const src = { type: 'playlist', id: pl.id, name: pl.name };
      const wrap = el('div', 'pdetail'), side = el('div', 'pside');
      const st = el('div', 'pstack');
      if (pl.items[0]) { const im = el('img'); im.alt = ''; hqImg(im, pl.items[0].id); st.append(im); } else st.append(icon('list'));
      const total = pl.items.reduce((s, x) => s + (x.duration || 0), 0);
      const btns = el('div', 'btns');
      btns.append(
        button('btn filled has-icon', 'Play all', 'play', () => pl.items.length && startList(pl.items, 0, src)),
        iconBtn('shuffle', 'Shuffle play', () => { if (pl.items.length) startList([...pl.items].sort(() => Math.random() - .5), 0, src); }, 'ib active'),
        iconBtn('more', 'Playlist actions', e => menu(e.currentTarget, [
          { label: 'Add all to queue', icon: 'queue', fn: () => { pl.items.forEach(x => enqueue(x)); } },
          { label: 'Rename', icon: 'edit', fn: async () => { const n = await promptName({ title: 'Rename playlist', value: pl.name }); if (n) { pl.name = n; savePl(); draw(); } } },
          '-',
          { label: 'Delete playlist', icon: 'delete', danger: true, fn: () => {
            const idx = playlists.indexOf(pl); playlists.splice(idx, 1); savePl(); nav('/playlists');
            toast(`Deleted “${pl.name}”`, { label: 'Undo', fn: () => { playlists.splice(idx, 0, pl); savePl(); nav('/playlist/' + pl.id); } });
          } },
        ])));
      side.append(st, el('h1', '', pl.name), el('div', 'sub', `${pl.items.length} video${pl.items.length === 1 ? '' : 's'}${total ? ' · ' + fmt(total) : ''}`), btns);
      const list = el('div', 'rlist');
      if (!pl.items.length) list.append(emptyBox('This playlist is empty', 'Use “Save” on any video to add it here.'));
      else list.append(...pl.items.map((x, i) => rcard(x, { where: 'playlist', index: i, plId: pl.id }, () => startList(pl.items, i, src))));
      wrap.append(side, list); root.append(wrap);
    };
    draw(); setRefresh(draw);
  });
}

/* ================= search box + boot ================= */
$('#searchForm').addEventListener('submit', e => {
  e.preventDefault();
  const text = $('#q').value.trim();
  if (!text) return;
  const id = videoIdFrom(text);
  $('#q').blur();
  if (id) return openVideo({ id });
  nav('/results?q=' + encodeURIComponent(text));
});

/* voice search (Web Speech API; Chrome/Edge/Safari) */
(() => {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const btn = $('#micBtn');
  if (!SR || !btn) return;
  btn.hidden = false;
  let rec = null;
  btn.addEventListener('click', () => {
    if (rec) { rec.stop(); return; }
    rec = new SR();
    rec.lang = navigator.language || 'en-US';
    rec.interimResults = true;
    rec.maxAlternatives = 1;
    let finalText = '';
    const q = $('#q');
    rec.onstart = () => { btn.classList.add('listening'); q.placeholder = 'Listening…'; q.value = ''; };
    rec.onresult = ev => {
      let t = '';
      for (const r of ev.results) { t += r[0].transcript; if (r.isFinal) finalText = t; }
      q.value = t;
    };
    rec.onerror = () => { finalText = ''; };
    rec.onend = () => {
      btn.classList.remove('listening'); q.placeholder = 'Search YouTube, or paste a link'; rec = null;
      if (finalText.trim()) { q.value = finalText.trim(); $('#searchForm').requestSubmit(); }
    };
    try { rec.start(); } catch { rec = null; }
  });
})();

applyTheme();
setState('idle');
route();


$('#railNetwork').hidden = false;

// PWA: installable, and the app shell opens offline. Browsers only allow this on https or localhost.
if ('serviceWorker' in navigator && (location.protocol === 'https:' || ['localhost', '127.0.0.1'].includes(location.hostname))) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

})();
