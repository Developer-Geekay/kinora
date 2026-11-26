import express from 'express';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { existsSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import crypto from 'node:crypto';
import os from 'node:os';
import { mkdtemp, readFile, rm, mkdir } from 'node:fs/promises';
import { PassThrough } from 'node:stream';
import dns from 'node:dns';
import net from 'node:net';

// Stream URLs are bound to the IP that requested them, so yt-dlp, ffmpeg and our own
// proxy must all use the same address family.
dns.setDefaultResultOrder('ipv4first');
net.setDefaultAutoSelectFamily(false);

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const BIN = path.join(ROOT, 'bin');
// Windows keeps its tools in bin/ as .exe files. Elsewhere (a Raspberry Pi, say) use bin/<name> if it is there, else the one on PATH.
const tool = name => {
  if (process.platform === 'win32') return path.join(BIN, name + '.exe');
  const local = path.join(BIN, name);
  return existsSync(local) ? local : name;
};
const YTDLP = tool('yt-dlp');
const FFMPEG = tool('ffmpeg');
const DENO = tool('deno');
// Local only by default. `npm run lan` (LAN=1) also listens on the home network for the Android app,
// but only answers private addresses, and only with the PIN (see the access guard below).
const LAN = process.env.LAN === '1';
const HOST = LAN ? '0.0.0.0' : '127.0.0.1';
const LOOP = '127.0.0.1'; // our own requests to ourselves (ffmpeg reading /raw)
const PORT = Number(process.env.PORT) || 3000;
const COOKIES = path.join(ROOT, 'cookies.txt'); // optional, Netscape format
const MAX_HEIGHT = Number(process.env.MAX_HEIGHT) || 720;
const OUT_BUFFER = (Number(process.env.OUT_BUFFER_MB) || 32) * 1024 * 1024; // per playing video, in each of 2 queues

const ID_RE = /^[\w-]{11}$/;
const URL_TTL_MS = 20 * 60 * 1000;

// YouTube no longer offers muxed files for most videos, so video mode takes separate
// H.264 video + AAC audio streams (https only, no HLS/DASH manifests) and joins them in ffmpeg.
// YouTube only ships H.264 up to 1080p; 1440p and 4K come as VP9 (muxed into MP4, which Chrome plays).
const HEIGHTS = [360, 480, 720, 1080, 1440, 2160];
const heightOf = req => { const q = Number(req.query.q); return HEIGHTS.includes(q) ? q : MAX_HEIGHT; };
const formatFor = (mode, h) => mode === 'audio'
  ? 'ba[protocol=https]/ba[protocol^=m3u8]' // the last one is for live streams (HLS)
  : `bv*[height<=${h}][vcodec^=${h > 1080 ? 'vp09' : 'avc1'}][protocol=https]+ba[ext=m4a][protocol=https]` +
    `/bv*[height<=${h}][protocol=https]+ba[protocol=https]` +
    `/bv*[height<=${h}][protocol^=m3u8]+ba[protocol^=m3u8]`; // live streams (HLS)
const keyOf = (id, mode, h) => `${id}:${mode}:${mode === 'audio' ? 0 : h}`; // audio has no quality tiers

const env = { ...process.env, PATH: `${BIN}${path.delimiter}${process.env.PATH}` };

function ytdlp(args) {
  // visionos is pinned because yt-dlp's default client choice varies per run, and the TV
  // client's URLs 403 without a PO token.
  const full = ['--no-warnings', '--no-playlist', '--force-ipv4', '--js-runtimes', `deno:${DENO}`,
    '--extractor-args', 'youtube:player_client=visionos'];
  if (existsSync(COOKIES)) full.push('--cookies', COOKIES);
  return new Promise((resolve, reject) => {
    // No shell: user input is passed as argv entries, never interpolated.
    const p = spawn(YTDLP, [...full, ...args], { env, windowsHide: true });
    let out = '', err = '';
    p.stdout.on('data', d => (out += d));
    p.stderr.on('data', d => (err += d));
    p.on('error', reject);
    p.on('close', code =>
      code === 0 ? resolve(out) : reject(new Error(err.trim().split('\n').pop() || `yt-dlp exited ${code}`)));
  });
}

const cache = new Map(); // keyOf(id, mode, height) -> { formats, title, duration, expires }

// Subtitle tracks worth offering: every uploaded one, plus the auto-generated track in the
// video's own language (YouTube lists ~150 machine translations of it, which we skip).
function subTracks(info) {
  const tracks = [];
  for (const [lang, list] of Object.entries(info.subtitles || {})) {
    tracks.push({ lang, name: list?.[0]?.name || lang, auto: false });
  }
  const autos = info.automatic_captions || {};
  let originals = Object.keys(autos).filter(k => /-orig$/.test(k));
  if (!originals.length && !tracks.length && autos.en) originals = ['en'];
  for (const lang of originals) {
    tracks.push({ lang, name: (autos[lang]?.[0]?.name || lang).replace(/\s*\(Original\)/i, ''), auto: true });
  }
  return tracks;
}

// Several requests for the same video arrive together (info, both /raw streams, subtitles, the
// next-video pre-resolve). Share one yt-dlp run between them instead of starting one each.
const inflight = new Map();
function resolveStream(id, mode, h = MAX_HEIGHT) {
  const key = keyOf(id, mode, h);
  const hit = cache.get(key);
  if (hit && hit.expires > Date.now()) return Promise.resolve(hit);
  if (!inflight.has(key)) inflight.set(key, fetchStream(id, mode, h, key).finally(() => inflight.delete(key)));
  return inflight.get(key);
}

async function fetchStream(id, mode, h, key) {
  const info = JSON.parse(await ytdlp(['-f', formatFor(mode, h), '-J', `https://www.youtube.com/watch?v=${id}`]));
  if (info.is_live) { // a live stream: HLS playlists that ffmpeg reads (see streamLive)
    const parts = (info.requested_formats ?? [info]).map(f => ({ url: f.url, protocol: f.protocol }));
    if (!parts.length || parts.some(f => !f.url)) throw new Error('No playable live format found');
    const entry = {
      live: true, formats: parts, title: info.title, channel: info.channel || info.uploader, duration: 0,
      views: info.view_count, uploaded: info.upload_date, description: (info.description || '').slice(0, 5000), subs: [],
      expires: Date.now() + 20 * 60_000,
    };
    cache.set(key, entry);
    return entry;
  }
  const formats = (info.requested_formats ?? [info]).map(f => ({
    url: f.url,
    headers: f.http_headers ?? {},
    protocol: f.protocol,
    size: Number(new URL(f.url).searchParams.get('clen')) || f.filesize || 0,
    mime: `${f.vcodec && f.vcodec !== 'none' ? 'video' : 'audio'}/${f.ext === 'm4a' ? 'mp4' : f.ext}`,
  }));
  if (formats.some(f => !f.url || !/^https?$/.test(f.protocol))) throw new Error('No directly playable format found');
  const entry = {
    formats, title: info.title, channel: info.channel || info.uploader, duration: info.duration,
    views: info.view_count, uploaded: info.upload_date, description: (info.description || '').slice(0, 5000),
    subs: subTracks(info),
    expires: Date.now() + URL_TTL_MS,
  };
  cache.set(key, entry);
  return entry;
}

const app = express();
app.disable('x-powered-by');

/* ---------- access guard ---------- */
// 1. Only this PC and private networks may connect at all (never the internet, even if a port is forwarded).
// 2. The Host header must be this PC or a private address (DNS-rebinding guard).
// 3. This PC needs nothing more. Other devices (LAN mode) must present the PIN: header X-LT-Key, ?key=,
//    or the cookie the web login page sets. Wrong PINs are rate-limited per address.
const isLoopback = ip => ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(ip);
const isPrivateIp = ip => {
  ip = String(ip).replace(/^::ffff:/, '');
  return isLoopback(ip) || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.)/.test(ip) || /^f[cd][0-9a-f]{2}:/i.test(ip) || /^fe80:/i.test(ip);
};
const sha = s => crypto.createHash('sha256').update(String(s)).digest();
const safeEq = (a, b) => !!a && !!b && crypto.timingSafeEqual(sha(a), sha(b));

function readOrCreatePin() {
  if (process.env.LT_PIN) return process.env.LT_PIN;
  const file = path.join(ROOT, '.cache', 'pin.txt');
  try { return readFileSync(file, 'utf8').trim(); } catch { /* first run */ }
  const pin = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, pin);
  return pin;
}
const PIN = LAN ? readOrCreatePin() : null;
const COOKIE_TOKEN = PIN && crypto.createHmac('sha256', PIN).update('kinora-web-login').digest('hex');
const failures = new Map(); // ip -> { n, until }
const cookies = req => Object.fromEntries((req.headers.cookie || '').split(';').map(c => c.trim().split(/=(.*)/s)).filter(p => p[0]));

const LOGIN_PAGE = (msg = '') => `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Kinora</title>
<style>body{margin:0;min-height:100dvh;display:grid;place-items:center;background:#17130e;color:#ebe1d7;font:16px system-ui}form{display:grid;gap:14px;width:min(320px,86vw)}
h1{font:500 26px system-ui;margin:0}b{color:#ffb951}input{font:inherit;padding:14px;border-radius:12px;border:1px solid #9b8f80;background:#231f19;color:inherit;letter-spacing:.3em;text-align:center}
button{font:inherit;font-weight:500;padding:14px;border:0;border-radius:999px;background:#ffb951;color:#452b00}p{margin:0;color:#ffb4ab;min-height:1.2em}</style>
<form method="post" action="/login"><h1>Local<b>Tube</b></h1><span>Enter the PIN shown on the PC</span>
<input name="pin" inputmode="numeric" autocomplete="one-time-code" autofocus><p>${msg}</p><button>Unlock</button></form>`;

app.use((req, res, next) => {
  const ip = req.socket.remoteAddress || '';
  if (!isPrivateIp(ip)) return res.status(403).end();
  const host = (req.headers.host || '').replace(/:\d+$/, '').replace(/^\[|\]$/g, '');
  if (!(host === 'localhost' || host === '127.0.0.1' || (LAN && (isPrivateIp(host) || host.endsWith('.local'))))) return res.status(403).end();
  if (isLoopback(ip)) return next();
  if (!LAN) return res.status(403).end();
  if (req.path === '/login' && req.method === 'POST') return next();
  if ((req.method === 'GET' || req.method === 'HEAD') && hlsSession(req.path)) return next(); // an HLS playlist/segment: the random id in the path is the key

  const lock = failures.get(ip);
  if (lock && lock.until > Date.now()) return res.status(429).send('Too many wrong PINs. Try again in a few minutes.');
  const given = req.get('x-lt-key') || req.query.key;
  if (safeEq(given, PIN) || safeEq(cookies(req).lt, COOKIE_TOKEN)) return next();
  if (given) { // a wrong PIN was actually tried
    const f = failures.get(ip) || { n: 0, until: 0 };
    f.n++; if (f.n >= 5) { f.until = Date.now() + 5 * 60 * 1000; f.n = 0; }
    failures.set(ip, f);
  }
  if (req.method === 'GET' && (req.headers.accept || '').includes('text/html')) return res.status(401).type('html').send(LOGIN_PAGE());
  res.status(401).json({ error: 'PIN required' });
});

app.post('/login', express.urlencoded({ extended: false }), (req, res) => {
  const ip = req.socket.remoteAddress || '';
  const lock = failures.get(ip);
  if (lock && lock.until > Date.now()) return res.status(429).send('Too many wrong PINs. Try again in a few minutes.');
  if (safeEq(req.body?.pin, PIN)) {
    failures.delete(ip);
    res.setHeader('Set-Cookie', `lt=${COOKIE_TOKEN}; HttpOnly; SameSite=Strict; Path=/; Max-Age=31536000`);
    return res.redirect('/');
  }
  const f = failures.get(ip) || { n: 0, until: 0 };
  f.n++; if (f.n >= 5) { f.until = Date.now() + 5 * 60 * 1000; f.n = 0; }
  failures.set(ip, f);
  res.status(401).type('html').send(LOGIN_PAGE('Wrong PIN'));
});

// Lets the Android app check the address and PIN in one call.
app.get('/api/ping', (req, res) => res.json({ ok: true, name: 'Kinora', lan: LAN }));

app.use(express.static(path.join(ROOT, 'public')));

app.get('/api/search', async (req, res) => {
  const q = String(req.query.q || '').trim().slice(0, 200);
  if (!q) return res.json([]);
  try {
    // "--" ends option parsing so a query can't be read as a flag.
    const n = Math.min(60, Math.max(1, Number(req.query.n) || 20));
    const out = await ytdlp(['--flat-playlist', '-J', '--', `ytsearch${n}:${q}`]);
    const items = (JSON.parse(out).entries || [])
      .filter(e => ID_RE.test(e.id))
      .map(e => ({
        id: e.id,
        title: e.title,
        channel: e.channel || e.uploader,
        verified: !!e.channel_is_verified,
        duration: e.duration,
        views: e.view_count,
        desc: (e.description || '').slice(0, 220),
        live: e.live_status === 'is_live',
      }));
    res.json(items);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

/* ---------- real Shorts ---------- */
// A search for "shorts" returns ordinary videos with "shorts" in the title. The real ones come from hashtag pages and
// channels' Shorts tabs: yt-dlp lists those as /shorts/ID, so keep only entries whose url says so. Cached for a while.
const SHORT_SOURCES = [
  'https://www.youtube.com/hashtag/shorts', 'https://www.youtube.com/hashtag/funny', 'https://www.youtube.com/hashtag/music',
  'https://www.youtube.com/hashtag/trending', 'https://www.youtube.com/hashtag/gaming', 'https://www.youtube.com/hashtag/food',
  'https://www.youtube.com/@MrBeast/shorts', 'https://www.youtube.com/@Zhong/shorts', 'https://www.youtube.com/@SSSniperWolf/shorts',
];
let shortsCache = { at: 0, items: [], busy: null };
// Looking a stream up takes yt-dlp ~3.5 s, which is most of the wait before a Short starts. So the first few are looked up
// as soon as the list exists (two at a time), and by the time someone taps one it is already in the cache.
async function warmShorts(items, n = 4) {
  const ids = items.slice(0, n).map(x => x.id);
  await Promise.all([0, 1].map(async () => { for (let id; (id = ids.shift());) await resolveStream(id, 'video', 1080).catch(() => {}); }));
}
async function loadShorts() {
  const lists = await Promise.all(SHORT_SOURCES.map(u => ytdlp(['--flat-playlist', '--playlist-end', '40', '-J', '--', u]).then(o => JSON.parse(o).entries || []).catch(() => [])));
  const seen = new Set(), out = [];
  for (let i = 0; lists.some(l => i < l.length); i++) for (const l of lists) {
    const e = l[i];
    if (!e || !ID_RE.test(e.id) || seen.has(e.id) || !/\/shorts\//.test(e.url || '')) continue;
    seen.add(e.id);
    out.push({ id: e.id, title: e.title, channel: e.channel || e.uploader || '', duration: e.duration || 0, views: e.view_count, short: true });
  }
  for (let i = out.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [out[i], out[j]] = [out[j], out[i]]; } // shuffled once per refresh
  return out;
}
app.get('/api/shorts', async (req, res) => {
  try {
    const force = req.query.force === '1';
    if (force || !shortsCache.items.length || Date.now() - shortsCache.at > 20 * 60_000) {
      shortsCache.busy ||= loadShorts().then(items => { if (items.length) { shortsCache = { at: Date.now(), items, busy: null }; warmShorts(items); } else shortsCache.busy = null; return items; }).catch(e => { shortsCache.busy = null; throw e; });
      await shortsCache.busy;
    }
    res.json(shortsCache.items);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

/* ---------- related videos ---------- */
// YouTube's watch page asks this endpoint for the list beside the player; no login is needed.
const relatedCache = new Map(); // id -> { at, items }
const countOf = t => {
  const m = /([\d.,]+)\s*([KMB])?/i.exec(String(t || '').replace(/\s/g, ' '));
  if (!m) return null;
  return Math.round(parseFloat(m[1].replace(/,/g, '')) * ({ K: 1e3, M: 1e6, B: 1e9 }[(m[2] || '').toUpperCase()] || 1));
};
const clockOf = t => { const p = String(t || '').split(':').map(Number); return p.length > 1 && p.every(Number.isFinite) ? p.reduce((a, b) => a * 60 + b, 0) : null; };
function parseRelated(j, selfId) {
  const results = j?.contents?.twoColumnWatchNextResults?.secondaryResults?.secondaryResults?.results || [];
  const out = [];
  for (const r of results) {
    const v = r.lockupViewModel;
    if (!v || (v.contentType && v.contentType !== 'LOCKUP_CONTENT_TYPE_VIDEO') || !ID_RE.test(v.contentId || '')) continue;
    if (v.contentId === selfId) continue;
    const meta = v.metadata?.lockupMetadataViewModel;
    const rows = meta?.metadata?.contentMetadataViewModel?.metadataRows || [];
    const text = row => (rows[row]?.metadataParts || []).map(x => x.text?.content).filter(Boolean);
    const badges = (v.contentImage?.thumbnailViewModel?.overlays || []).flatMap(o => (o.thumbnailBottomOverlayViewModel?.badges || []).map(b => b.thumbnailBadgeViewModel?.text)).filter(Boolean);
    const stats = text(1).join(' ');
    const live = badges.some(b => /^live$/i.test(b)) || /watching/i.test(stats);
    out.push({
      id: v.contentId, title: meta?.title?.content || v.contentId, channel: text(0)[0] || '',
      views: countOf(stats), duration: live ? null : clockOf(badges.find(b => /^[\d:]+$/.test(b))), live,
    });
  }
  return out;
}
app.get('/api/related/:id', async (req, res) => {
  const id = req.params.id;
  if (!ID_RE.test(id)) return res.status(400).json({ error: 'bad id' });
  const hit = relatedCache.get(id);
  if (hit && Date.now() - hit.at < 15 * 60_000) return res.json(hit.items);
  try {
    const r = await fetch('https://www.youtube.com/youtubei/v1/next?prettyPrint=false', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'user-agent': 'Mozilla/5.0', 'accept-language': 'en-US,en;q=0.9' },
      body: JSON.stringify({ context: { client: { clientName: 'WEB', clientVersion: '2.20250101.00.00', hl: 'en', gl: 'US' } }, videoId: id }),
      signal: AbortSignal.timeout(15000),
    });
    if (!r.ok) throw new Error(`YouTube answered ${r.status}`);
    const items = parseRelated(await r.json(), id).slice(0, 40);
    relatedCache.set(id, { at: Date.now(), items });
    if (relatedCache.size > 200) relatedCache.delete(relatedCache.keys().next().value);
    res.json(items);
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

function modeOf(req) {
  return req.query.mode === 'audio' ? 'audio' : 'video';
}

app.get('/api/info/:id', async (req, res) => {
  if (!ID_RE.test(req.params.id)) return res.status(400).json({ error: 'bad id' });
  try {
    const { title, channel, duration, views, uploaded, description, subs, live } =
      await resolveStream(req.params.id, modeOf(req), heightOf(req));
    res.json({ title, channel, duration, views, uploaded, description, subs, live: !!live });
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

/* ---------- parallel read-ahead loader ---------- */
// YouTube throttles a plain GET/Range download to roughly playback speed (~0.3 MB/s); asking for
// windows through the `range=` URL parameter (what yt-dlp does) is not throttled. We keep several
// windows downloading at once so one slow connection, or the gap while a new one is set up, never
// starves the player. Window sizes ramp up from small so playback starts quickly.
const RAMP = [256 * 1024, 768 * 1024, 2 * 1024 * 1024];
const WINDOW = 4 * 1024 * 1024;
const AHEAD = Number(process.env.READ_AHEAD) || 4;   // windows in flight per stream (memory ≈ AHEAD × 4 MB)
const sleep = ms => new Promise(r => setTimeout(r, ms));

function planWindows(start, end) {
  const wins = [];
  for (let pos = start, i = 0; pos <= end; i++) {
    const size = i < RAMP.length ? RAMP[i] : WINDOW;
    wins.push({ s: pos, e: Math.min(pos + size - 1, end), chunks: [], got: 0, done: false, err: null, wake: null });
    pos += size;
  }
  return wins;
}

// Download one window into memory, retrying (from where it got to) on network errors and
// re-resolving the URL if YouTube says it expired.
async function fillWindow(w, cur, refresh, signal) {
  for (let attempt = 0; attempt < 5; attempt++) {
    if (signal.aborted) return;
    const f = cur.fmt;
    try {
      const u = new URL(f.url);
      u.searchParams.set('range', `${w.s + w.got}-${w.e}`);
      const r = await fetch(u, { headers: f.headers, signal });
      if (r.status === 403 || r.status === 410) { await refresh(f); continue; }
      if (!r.ok) throw new Error(`upstream ${r.status}`);
      for await (const c of r.body) { w.chunks.push(c); w.got += c.length; w.wake?.(); }
      if (w.s + w.got > w.e) { w.done = true; w.wake?.(); return; }
    } catch {
      if (signal.aborted) return;
      await sleep(200 * (attempt + 1));
    }
  }
  if (!signal.aborted) { w.err = new Error(`upstream failed for bytes ${w.s}-${w.e}`); w.wake?.(); }
}

const drained = res => new Promise(resolve => {
  const done = () => { res.off('drain', done); res.off('close', done); resolve(); };
  res.once('drain', done);
  res.once('close', done);
});

// Serve one upstream format to a Range-capable client (browser <audio>, or ffmpeg via /raw).
async function pipeFormat(req, res, id, mode, idx, h) {
  const entry = await resolveStream(id, mode, h);
  const fmt = entry.formats[idx];
  if (!fmt) return res.status(404).end();
  const total = fmt.size;
  if (!total) throw new Error('Unknown stream size');

  const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
  let start = 0, end = total - 1;
  if (m && (m[1] || m[2])) {
    if (m[1]) { start = Number(m[1]); if (m[2]) end = Math.min(Number(m[2]), total - 1); }
    else { start = Math.max(0, total - Number(m[2])); } // suffix range: last N bytes
  }
  if (start >= total || start > end) return res.status(416).set('Content-Range', `bytes */${total}`).end();

  const ctrl = new AbortController();
  res.on('close', () => ctrl.abort());
  const cur = { fmt };
  let refreshing = null;
  const refresh = stale => { // the URL expired: re-resolve once, shared by every window
    if (cur.fmt !== stale) return undefined;
    return (refreshing ??= (async () => {
      cache.delete(keyOf(id, mode, h));
      cur.fmt = (await resolveStream(id, mode, h)).formats[idx];
    })().finally(() => { refreshing = null; }));
  };

  const type = fmt.mime || 'application/octet-stream';
  res.status(m ? 206 : 200).set({
    'Content-Type': type,
    'Accept-Ranges': 'bytes',
    'Content-Length': String(end - start + 1),
    ...(m ? { 'Content-Range': `bytes ${start}-${end}/${total}` } : {}),
  });
  if (req.method === 'HEAD') return res.end();

  // Windows are written in order; up to AHEAD of them download in parallel ahead of the one being sent.
  const wins = planWindows(start, end);
  let started = 0;
  const kick = consumed => {
    while (started < wins.length && started - consumed < AHEAD) fillWindow(wins[started++], cur, refresh, ctrl.signal);
  };
  for (let i = 0; i < wins.length; i++) {
    kick(i);
    const w = wins[i];
    for (;;) {
      while (w.chunks.length) {
        if (!res.write(w.chunks.shift())) await drained(res);
        if (ctrl.signal.aborted) return;
      }
      if (w.err) throw w.err;
      if (w.done) break;
      await new Promise(r => { w.wake = r; }); // set in the same tick as the checks above, so no wake-up is lost
    }
    w.chunks = null;
  }
  res.end();
}

const guard = fn => async (req, res) => {
  if (!ID_RE.test(req.params.id)) return res.status(400).send('bad id');
  try { await fn(req, res); }
  catch (e) {
    if (e.name === 'AbortError') return;
    console.error(`${req.path}: ${e.message}`);
    if (!res.headersSent) res.status(502).send(e.message); else res.destroy();
  }
};

// Loopback source for ffmpeg: /raw/:id/:idx (0 = video, 1 = audio).
app.all('/raw/:id/:idx', guard((req, res) => pipeFormat(req, res, req.params.id, 'video', Number(req.params.idx), heightOf(req))));

/* ---------- keyframe-accurate seeking ---------- */
// The video is copied, not re-encoded, so it can only start on a keyframe, which is usually a few
// seconds before the time asked for. The audio can start anywhere. If each input just seeks to the
// requested time on its own, the video starts early and the audio late: you get seconds of silent
// video, and the page's clock and subtitles are off by the same amount. So we find the keyframe first
// and start BOTH streams there, and tell the page the real start time.
const snapCache = new Map(); // `${id}:${h}:${t}` -> keyframe time (seconds)
// Asking ffmpeg for a keyframe's own time lands on the keyframe BEFORE it (the demuxer compares strictly),
// so every video seek asks for the time plus this nudge. Keyframes are seconds apart, so it never skips one.
const SEEK_NUDGE = 0.1;

function ffmpegText(args, timeoutMs = 20000) {
  return new Promise((resolve, reject) => {
    const p = spawn(FFMPEG, args, { env, windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
    let out = '';
    const timer = setTimeout(() => { p.kill(); reject(new Error('ffmpeg probe timed out')); }, timeoutMs);
    p.stdout.on('data', d => (out += d));
    p.on('error', e => { clearTimeout(timer); reject(e); });
    p.on('close', () => { clearTimeout(timer); resolve(out); });
  });
}

async function keyframeAt(id, h, t) {
  if (!(t > 0)) return 0;
  const key = `${id}:${h}:${t}`;
  if (snapCache.has(key)) return snapCache.get(key);
  await resolveStream(id, 'video', h);
  const out = await ffmpegText(['-hide_banner', '-loglevel', 'error', '-ss', String(t + SEEK_NUDGE), '-probesize', '500000', '-analyzeduration', '500000',
    '-i', `http://${LOOP}:${PORT}/raw/${id}/0?q=${h}`,
    '-map', '0:v:0', '-c', 'copy', '-frames:v', '1', '-copyts', '-f', 'framecrc', '-']);
  const tb = /#tb 0:\s*(\d+)\/(\d+)/.exec(out), pk = /^0,\s*-?\d+,\s*(-?\d+),/m.exec(out);
  let k = t;
  if (tb && pk) {
    const cand = Number(pk[1]) * Number(tb[1]) / Number(tb[2]);
    if (cand >= 0 && cand <= t + SEEK_NUDGE && t - cand < 30) k = Math.round(cand * 1000) / 1000; // sanity: a keyframe at or just before t
  }
  snapCache.set(key, k);
  snapCache.set(`${id}:${h}:${k}`, k); // asking for the keyframe itself stays put
  return k;
}

app.get('/api/start/:id', guard(async (req, res) => {
  const t = Math.max(0, Number(req.query.t) || 0);
  res.json({ start: await keyframeAt(req.params.id, heightOf(req), t) });
}));

// Video: ffmpeg joins video+audio into fragmented MP4 on stdout. No byte ranges;
// the client seeks by requesting a new stream with ?t=<seconds>.
// ffmpeg's stdout goes to the response through a big buffer (the browser only keeps a few seconds of a piped stream).
function pipeFfmpeg(res, id, args, contentType) {
  const ff = spawn(FFMPEG, args, { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let err = '';
  ff.stderr.on('data', d => { err += d; });
  ff.on('error', e => { if (!res.headersSent) res.status(500).send(e.message); });
  ff.on('close', code => {
    if (code && code !== 255) console.error(`ffmpeg ${id}: exit ${code} ${err.trim().split('\n').pop()}`);
  });
  res.status(200).set({ 'Content-Type': contentType, 'Accept-Ranges': 'none', 'Cache-Control': 'no-store' });
  // Tens of MB ready here: ffmpeg runs ahead of playback and a network hiccup upstream is absorbed before it can show up as a stall.
  const buffer = new PassThrough({ highWaterMark: OUT_BUFFER });
  ff.stdout.pipe(buffer).pipe(res);
  res.on('close', () => { ff.kill(); buffer.destroy(); });
}

async function streamVideo(req, res, id) {
  const h = heightOf(req);
  const t = await keyframeAt(id, h, Math.max(0, Number(req.query.t) || 0));
  const entry = await resolveStream(id, 'video', h);
  const args = ['-hide_banner', '-loglevel', 'error'];
  // ffmpeg reads from our own /raw endpoint, which does the un-throttled chunked fetching.
  entry.formats.forEach((_, i) => {
    // t is the keyframe's time. Video needs the nudge to land ON that keyframe; audio starts exactly at it.
    if (t) args.push('-ss', String(i === 0 ? t + SEEK_NUDGE : t));
    args.push('-probesize', '500000', '-analyzeduration', '500000', '-i', `http://${LOOP}:${PORT}/raw/${id}/${i}?q=${h}`);
  });
  args.push('-c', 'copy', '-map', '0:v:0', '-map', `${entry.formats.length - 1}:a:0`,
    '-movflags', 'frag_keyframe+empty_moov+default_base_moof', '-f', 'mp4', 'pipe:1');
  pipeFfmpeg(res, id, args, 'video/mp4');
}

// A live stream is an HLS playlist (short chunks, endlessly). ffmpeg reads it and joins video + audio into the
// same fragmented MP4 pipe as an ordinary video. There is nothing to seek in: it plays at the live edge.
function streamLive(res, id, mode, entry) {
  const args = ['-hide_banner', '-loglevel', 'error', '-fflags', '+discardcorrupt'];
  entry.formats.forEach(f => args.push('-i', f.url));
  if (mode === 'audio') args.push('-map', `${entry.formats.length - 1}:a:0`, '-c:a', 'copy');
  else args.push('-c', 'copy', '-map', '0:v:0', '-map', `${entry.formats.length - 1}:a:0`);
  // HLS audio is ADTS AAC; MP4 needs the plain form. A fragment every second keeps audio-only streams flowing.
  args.push('-bsf:a', 'aac_adtstoasc', '-frag_duration', '1000000',
    '-movflags', 'frag_keyframe+empty_moov+default_base_moof', '-f', 'mp4', 'pipe:1');
  pipeFfmpeg(res, id, args, mode === 'audio' ? 'audio/mp4' : 'video/mp4');
}

app.get('/stream/:id', guard(async (req, res) => {
  const id = req.params.id, mode = modeOf(req);
  const entry = await resolveStream(id, mode, mode === 'audio' ? 0 : heightOf(req));
  if (entry.live) return streamLive(res, id, mode, entry);
  return mode === 'audio' ? pipeFormat(req, res, id, 'audio', 0, 0) : streamVideo(req, res, id);
}));

/* ---------- HLS for iPhone / iPad ---------- */
// Safari on iOS only plays a <video> from a source that answers byte ranges or from HLS: the fragmented-MP4 pipe above
// (no length, no ranges) just fails there. So for iOS the page asks /api/hls/:id for a session: ffmpeg copies the same
// video + audio into fMP4 HLS segments in .cache/hls/<id>/, and the page plays /hls/<id>/index.m3u8. Seeking works as
// before (the page asks for a new session with ?t=). The random id in the path is the credential for the segment requests
// (the native player may not send the PIN cookie); sessions and their files are removed when idle.
const HLS_DIR = path.join(ROOT, '.cache', 'hls');
const hlsSessions = new Map(); // id -> { dir, ff, last }
rmSync(HLS_DIR, { recursive: true, force: true });
const HLS_FILE = /^[\w.-]+\.(m3u8|m4s|mp4)$/;
function hlsSession(reqPath) {
  const m = /^\/hls\/([a-f0-9]{32})\//.exec(reqPath);
  return m ? hlsSessions.get(m[1]) : undefined;
}
function hlsEnd(token) {
  const sess = hlsSessions.get(token); if (!sess) return;
  hlsSessions.delete(token);
  try { sess.ff.kill(); } catch { /* already gone */ }
  setTimeout(() => rmSync(sess.dir, { recursive: true, force: true }), 1000);
}
setInterval(() => { for (const [t, s] of hlsSessions) if (Date.now() - s.last > 120_000) hlsEnd(t); }, 30_000).unref();

app.get('/api/hls/:id', guard(async (req, res) => {
  const id = req.params.id, h = heightOf(req);
  console.log(`hls: ${req.socket.remoteAddress} ${id} ${(req.get('user-agent') || '').replace(/^Mozilla\/5.0 /, '').slice(0, 90)}`);
  const entry = await resolveStream(id, 'video', h);
  if (entry.live) return res.json({ url: null }); // live streams already come as HLS from the source; not handled for iOS yet
  const t = await keyframeAt(id, h, Math.max(0, Number(req.query.t) || 0));
  while (hlsSessions.size >= 6) hlsEnd(hlsSessions.keys().next().value); // oldest first
  const token = crypto.randomBytes(16).toString('hex');
  const dir = path.join(HLS_DIR, token);
  mkdirSync(dir, { recursive: true });
  const args = ['-hide_banner', '-loglevel', 'error'];
  entry.formats.forEach((_, i) => {
    if (t) args.push('-ss', String(i === 0 ? t + SEEK_NUDGE : t));
    args.push('-probesize', '500000', '-analyzeduration', '500000', '-i', `http://${LOOP}:${PORT}/raw/${id}/${i}?q=${h}`);
  });
  args.push('-c', 'copy', '-map', '0:v:0', '-map', `${entry.formats.length - 1}:a:0`,
    '-f', 'hls', '-hls_time', '4', '-hls_list_size', '0', '-hls_playlist_type', 'event', '-hls_segment_type', 'fmp4',
    '-hls_fmp4_init_filename', 'init.mp4', '-hls_flags', 'independent_segments', '-hls_segment_filename', 'seg%d.m4s', 'index.m3u8');
  const ff = spawn(FFMPEG, args, { env, cwd: dir, windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
  let err = '';
  ff.stderr.on('data', d => { err += d; });
  ff.on('close', code => { if (code && code !== 255 && hlsSessions.has(token)) console.error(`ffmpeg hls ${id}: exit ${code} ${err.trim().split('\n').pop()}`); });
  const sess = { dir, ff, last: Date.now() };
  hlsSessions.set(token, sess);
  res.on('close', () => { sess.last = Date.now(); });
  // answer once the first segment is listed, so the player never sees an empty playlist
  const playlist = path.join(dir, 'index.m3u8');
  for (let i = 0; i < 100; i++) {
    if (ff.exitCode !== null && !existsSync(playlist)) break;
    try { if (readFileSync(playlist, 'utf8').includes('.m4s')) return res.json({ url: `/hls/${token}/index.m3u8`, start: t }); } catch { /* not written yet */ }
    await new Promise(r => setTimeout(r, 200));
  }
  hlsEnd(token);
  throw new Error(err.trim().split('\n').pop() || 'ffmpeg produced no HLS output');
}));

app.get('/hls/:token/:file', (req, res) => {
  const sess = hlsSessions.get(req.params.token);
  if (!sess || !HLS_FILE.test(req.params.file)) { console.log(`hls: 404 ${req.params.file} from ${req.socket.remoteAddress}`); return res.status(404).end(); }
  sess.last = Date.now();
  if (/^(index\.m3u8|init\.mp4|seg0\.m4s)$/.test(req.params.file) && !sess.seen?.has(req.params.file)) { (sess.seen ||= new Set()).add(req.params.file); console.log(`hls: ${req.socket.remoteAddress} fetched ${req.params.file}`); }
  const f = req.params.file;
  res.set({ 'Cache-Control': 'no-store', 'Content-Type': f.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp4' });
  res.sendFile(path.join(sess.dir, f), { headers: {}, cacheControl: false }, err => { if (err && !res.headersSent) res.status(404).end(); });
});

/* ---------- subtitles ---------- */
// yt-dlp downloads the track as WebVTT; we turn it into clean cues the page renders itself.
// (Rendering in the page keeps captions in sync with our offset-based video seeking, and
// works in audio-only mode too.)
const subCache = new Map(); // `${id}:${lang}:${auto}` -> cues

const vttTime = s => {
  const m = /(?:(\d+):)?(\d+):(\d+)[.,](\d+)/.exec(s);
  return m ? (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + (+m[3]) + (+m[4].padEnd(3, '0').slice(0, 3)) / 1000 : NaN;
};
const decode = s => s.replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

function parseVtt(text, auto) {
  const cues = [];
  for (const block of text.replace(/\r/g, '').split(/\n\n+/)) {
    const lines = block.split('\n');
    const at = lines.findIndex(l => l.includes('-->'));
    if (at < 0) continue;
    const [a, rest] = lines[at].split('-->');
    const s = vttTime(a), e = vttTime(rest.trim().split(/\s+/)[0]);
    const body = lines.slice(at + 1).map(l => decode(l.replace(/<[^>]+>/g, '')).trim()).filter(Boolean);
    if (body.length && e > s) cues.push({ s, e, l: body });
  }
  if (!auto) return cues.map(c => ({ s: c.s, e: c.e, t: c.l.join('\n') }));

  // Auto-generated captions are "rolling": each cue repeats the previous line, and tiny
  // 10 ms cues re-announce the old text. Keep only the lines that are new.
  const out = [];
  let prev = [];
  for (const c of cues) {
    if (c.e - c.s < 0.06) { prev = c.l; continue; }
    const fresh = c.l.filter(l => !prev.includes(l));
    prev = c.l;
    if (fresh.length) out.push({ s: c.s, e: c.e, t: fresh.join('\n') });
  }
  return out;
}

app.get('/api/subs/:id', guard(async (req, res) => {
  const { id } = req.params;
  const lang = String(req.query.lang || ''), auto = req.query.auto === '1';
  if (!/^[\w-]{2,20}$/.test(lang)) return res.status(400).json({ error: 'bad language' });
  const entry = await resolveStream(id, 'audio', 0);
  if (!entry.subs.some(s => s.lang === lang && s.auto === auto)) return res.status(404).json({ error: 'No such subtitle track' });

  const key = `${id}:${lang}:${auto}`;
  if (!subCache.has(key)) {
    const base = path.join(ROOT, '.cache', 'subs');
    await mkdir(base, { recursive: true });
    const dir = await mkdtemp(path.join(base, 'dl-'));
    try {
      await ytdlp(['--skip-download', auto ? '--write-auto-subs' : '--write-subs', '--sub-langs', lang,
        '--sub-format', 'vtt', '-o', path.join(dir, '%(id)s.%(ext)s'), `https://www.youtube.com/watch?v=${id}`]);
      const vtt = await readFile(path.join(dir, `${id}.${lang}.vtt`), 'utf8');
      subCache.set(key, parseVtt(vtt, auto));
    } finally {
      rm(dir, { recursive: true, force: true }).catch(() => {});
    }
  }
  res.json(subCache.get(key));
}));

app.listen(PORT, HOST, () => {
  console.log(`Kinora on http://127.0.0.1:${PORT}`);
  if (!LAN) return;
  const ips = Object.entries(os.networkInterfaces()).flatMap(([name, list]) => (list || [])
    .filter(a => a.family === 'IPv4' && !a.internal && isPrivateIp(a.address)).map(a => `${a.address}  (${name})`));
  console.log('\nLAN mode: other devices on this network can connect.');
  ips.forEach(a => console.log(`  http://${a.replace(/\s.*/, '')}:${PORT}   ${a.replace(/^\S+\s+/, '')}`));
  console.log(`  PIN: ${PIN}    (kept in .cache/pin.txt, or set LT_PIN)\n`);
});
