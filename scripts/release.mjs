// Builds everything deployable into release/web (+ archives), and on Windows also release/mobile and release/tv.
// Works on Windows, Linux and macOS (Node 20+ only; the Android build needs the Windows toolchain in android-tools/).
//
//   npm run release                 web bundle, plus the Android APK when run on Windows
//   npm run release:web             web bundle only (what a Linux machine can do)
//   npm run release:debugkey        also build the APK, signed with the debug key (updates a debug install in place)
//
// Options: --version 1.1   --skip-android   --debug-key   --no-linux-tools   --rebuild-helper (Windows, needs Rust)
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const rel = path.join(root, 'release');
const args = process.argv.slice(2);
const flag = n => args.includes(`--${n}`);
const opt = (n, d) => { const i = args.indexOf(`--${n}`); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const version = opt('version', '1.0');
const pad = n => String(n).padStart(2, '0');
const now = new Date();
const stamp = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`;
const versionCode = Number(`${String(now.getFullYear()).slice(2)}${pad(now.getMonth() + 1)}${pad(now.getDate())}${pad(now.getHours())}`);
const win = process.platform === 'win32';

const step = t => console.log(`\n== ${t}`);
const warn = t => console.log(`! ${t}`);
const fill = s => s.replaceAll('@@VERSION@@', version).replaceAll('@@STAMP@@', stamp);
const rm = p => fs.rmSync(p, { recursive: true, force: true });
const read = f => fs.readFileSync(path.join(root, f), 'utf8');
function put(p, text, mode) { fs.writeFileSync(p, text.replace(/\r\n/g, '\n')); if (mode && !win) fs.chmodSync(p, mode); }   // LF only: a CR on the end of start.sh breaks it on Linux
function isArmElf(p) {   // a real 64-bit ARM Linux program (ELF, machine 0xB7), so a wrong download is not shipped by mistake
  try {
    const fd = fs.openSync(p, 'r'); const h = Buffer.alloc(20); fs.readSync(fd, h, 0, 20, 0); fs.closeSync(fd);
    return h[0] === 0x7f && h[1] === 0x45 && h[2] === 0x4c && h[3] === 0x46 && h[18] === 0xb7 && h[19] === 0;
  } catch { return false; }
}
function run(cmd, cmdArgs, opts = {}) { const r = spawnSync(cmd, cmdArgs, { stdio: 'inherit', ...opts }); return r.status === 0; }

// ---------- what to build ----------
const androidTools = path.join(root, 'android-tools');
const wantAndroid = !flag('skip-android') && win && fs.existsSync(androidTools);
if (!flag('skip-android') && !wantAndroid) warn(`Android APK skipped: it needs Windows and the android-tools folder${win ? '' : ' (build it on the Windows PC)'}. Use "npm run release:web" to silence this.`);

// ---------- fresh output folders ----------
step('Preparing release/');
fs.mkdirSync(rel, { recursive: true });
const web = path.join(rel, 'web');
rm(web); fs.mkdirSync(web);
if (wantAndroid) for (const d of ['mobile', 'tv']) { rm(path.join(rel, d)); fs.mkdirSync(path.join(rel, d)); }
for (const f of fs.readdirSync(rel, { withFileTypes: true })) if (f.isFile()) fs.rmSync(path.join(rel, f.name));

// ---------- web ----------
step('Web portal -> release/web');
for (const f of ['server.js', 'package.json', 'package-lock.json']) if (fs.existsSync(path.join(root, f))) fs.copyFileSync(path.join(root, f), path.join(web, f));
fs.cpSync(path.join(root, 'public'), path.join(web, 'public'), { recursive: true });
fs.mkdirSync(path.join(web, 'bin'));
put(path.join(web, 'bin', 'README.txt'), fill(read('deploy/bin-README.txt')));
put(path.join(web, 'start.sh'), fill(read('deploy/start.sh')), 0o755);
put(path.join(web, 'install.sh'), fill(read('deploy/install.sh')), 0o755);
put(path.join(web, 'localtube.service'), fill(read('deploy/localtube.service')));
put(path.join(web, 'DEPLOY.md'), fill(read('deploy/DEPLOY.md')));

// ARM64 Linux yt-dlp and deno, if they are in bin/ (bin/yt-dlp_linux_aarch64 and bin/deno): shipped as bin/yt-dlp and bin/deno
let linuxTools = 'none';
if (!flag('no-linux-tools')) {
  const got = [];
  for (const [from, to] of [['yt-dlp_linux_aarch64', 'yt-dlp'], ['deno', 'deno']]) {
    const src = path.join(root, 'bin', from);
    if (isArmElf(src)) { fs.copyFileSync(src, path.join(web, 'bin', to)); if (!win) fs.chmodSync(path.join(web, 'bin', to), 0o755); got.push(to); }
  }
  if (got.length) { linuxTools = `${got.join(' + ')} (arm64)`; console.log(`Bundled Linux arm64 tools: ${linuxTools}`); }
}

// the helper (the small program that lets the Network page reach SMB shares on the client's network)
const helperExe = path.join(root, 'helper', 'dist', 'localtube-helper.exe');
if (flag('rebuild-helper')) {
  step('Rebuilding the helper');
  const env = { ...process.env, CARGO_HOME: path.join(root, 'helper-tools', 'cargo-home') };
  env.PATH = `${path.join(process.env.USERPROFILE || '', '.cargo', 'bin')}${path.delimiter}${env.PATH}`;
  if (!run('cargo', ['build', '--release'], { cwd: path.join(root, 'helper'), env, shell: win })) throw new Error('cargo build failed');
  fs.mkdirSync(path.dirname(helperExe), { recursive: true });
  fs.copyFileSync(path.join(root, 'helper', 'target', 'release', 'localtube-helper.exe'), helperExe);
}
if (fs.existsSync(helperExe)) {
  fs.mkdirSync(path.join(web, 'helper'));
  fs.copyFileSync(helperExe, path.join(web, 'helper', 'localtube-helper.exe'));
  fs.copyFileSync(path.join(root, 'helper', 'README.md'), path.join(web, 'helper', 'README.md'));
} else warn('helper/dist/localtube-helper.exe not found: the helper is left out (use --rebuild-helper on Windows).');

// ---------- Android (Windows only) ----------
let apkInfo = 'skipped';
if (wantAndroid) {
  step('Android release APK');
  const psArgs = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(root, 'android', 'release-apk.ps1'), '-Version', version, '-Code', String(versionCode), '-Stamp', stamp];
  if (flag('debug-key')) psArgs.push('-DebugKey');
  if (!run('powershell', psArgs)) { console.error('The Android build failed.'); process.exit(1); }
  apkInfo = `${flag('debug-key') ? 'debug key' : 'release key (android/release.keystore)'}, versionCode ${versionCode}`;
}

// ---------- archives + checksums ----------
step('Packaging');
const tgz = path.join(rel, 'LocalTube-web.tar.gz');
// relative paths from release/: GNU tar (Git Bash on Windows) reads "C:\..." as a remote host name
if (!run('tar', ['-czf', 'LocalTube-web.tar.gz', '-C', 'web', '.'], { cwd: rel, stdio: 'pipe' })) { fs.rmSync(tgz, { force: true }); warn('tar not available: no .tar.gz.'); }
const zip = path.join(rel, 'LocalTube-web.zip');
const zipped = win
  ? run('powershell', ['-NoProfile', '-Command', `Compress-Archive -Path '${web}\\*' -DestinationPath '${zip}' -Force`], { stdio: 'pipe' })
  : run('zip', ['-qr', zip, '.'], { cwd: web, stdio: 'pipe' });
if (!zipped) { fs.rmSync(zip, { force: true }); warn(win ? 'Compress-Archive failed: no .zip.' : '"zip" not installed: no .zip (the .tar.gz is enough on Linux).'); }

const sums = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { if (!['public', 'node_modules'].includes(e.name)) walk(p); continue; }
    if (!/\.(apk|zip|exe|gz)$/i.test(e.name)) continue;
    const hash = crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
    sums.push(`${hash}  ${path.relative(rel, p).split(path.sep).join('/')}`);
  }
})(rel);
fs.writeFileSync(path.join(rel, 'SHA256SUMS.txt'), sums.join('\n') + '\n');

console.log(`\nDone. Version ${version} (${stamp}). Android: ${apkInfo}. Linux tools in web bundle: ${linuxTools}`);
(function list(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { if (e.name !== 'public') list(p); continue; }
    console.log(`  ${path.relative(rel, p).split(path.sep).join('/')}   ${(fs.statSync(p).size / 1048576).toFixed(1)} MB`);
  }
})(rel);
console.log('  (web/public/ and the rest of the web files are in release/web, archived as release/LocalTube-web.tar.gz and .zip)');
