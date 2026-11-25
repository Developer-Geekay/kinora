# Builds everything deployable into release\web, release\mobile and release\tv.
#
#   npm run release                        (or: powershell -NoProfile -ExecutionPolicy Bypass -File build-release.ps1)
#   npm run release:debugkey               sign the APK with the debug key (installs over an existing debug build, keeps its data)
#
# Options:  -Version 1.1      version name shown in the app          -SkipAndroid   web only (no Gradle build)
#           -DebugKey         see above                               -RebuildHelper rebuild helper\dist\localtube-helper.exe with cargo
#
# Everything uses the project-local toolchain in android-tools\ (nothing is installed globally).
param(
    [switch]$DebugKey,
    [switch]$SkipAndroid,
    [switch]$RebuildHelper,
    [string]$Version = '1.0'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$rel = Join-Path $root 'release'
$stamp = Get-Date -Format 'yyyy-MM-dd HH:mm'
$code = [int](Get-Date -Format 'yyMMddHH')          # versionCode: grows with every build, so updates always install

function Step($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Fill($text) { $text.Replace('@@VERSION@@', $Version).Replace('@@STAMP@@', $stamp) }

# ---------- fresh output folders ----------
Step 'Preparing release\'
foreach ($d in 'web', 'mobile', 'tv') {
    $p = Join-Path $rel $d
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
    New-Item -ItemType Directory $p | Out-Null
}
Get-ChildItem $rel -File -ErrorAction SilentlyContinue | Remove-Item -Force

# ---------- web ----------
Step 'Web portal -> release\web'
$web = Join-Path $rel 'web'
foreach ($f in 'server.js', 'package.json', 'package-lock.json') { Copy-Item (Join-Path $root $f) $web }
Copy-Item (Join-Path $root 'public') $web -Recurse
New-Item -ItemType Directory (Join-Path $web 'bin') | Out-Null
Set-Content (Join-Path $web 'bin\README.txt') -Encoding ascii -Value (Fill @'
Put the tools the server uses here (or have them on PATH):
  yt-dlp   ffmpeg   deno
On Windows they are yt-dlp.exe, ffmpeg.exe and deno.exe. On Linux (a Raspberry Pi) they are plain "yt-dlp", "ffmpeg", "deno"
(make them executable: chmod +x bin/yt-dlp bin/deno). See DEPLOY.md.
'@)

# the helper (the small program that lets the Network page reach SMB shares on the client's network)
$helperExe = Join-Path $root 'helper\dist\localtube-helper.exe'
if ($RebuildHelper) {
    Step 'Rebuilding the helper'
    $env:CARGO_HOME = Join-Path $root 'helper-tools\cargo-home'
    $env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
    Push-Location (Join-Path $root 'helper')
    try { cargo build --release; if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' } } finally { Pop-Location }
    New-Item -ItemType Directory (Join-Path $root 'helper\dist') -Force | Out-Null
    Copy-Item (Join-Path $root 'helper\target\release\localtube-helper.exe') $helperExe -Force
}
if (Test-Path $helperExe) {
    New-Item -ItemType Directory (Join-Path $web 'helper') | Out-Null
    Copy-Item $helperExe (Join-Path $web 'helper')
    Copy-Item (Join-Path $root 'helper\README.md') (Join-Path $web 'helper\README.md')
} else { Write-Host 'helper\dist\localtube-helper.exe not found: the helper is left out (use -RebuildHelper).' -ForegroundColor Yellow }

Set-Content (Join-Path $web 'start.sh') -Encoding ascii -Value (Fill @'
#!/bin/sh
# Start LocalTube so other devices on the network can use it (they need the PIN printed below).
cd "$(dirname "$0")"
LAN=1 exec node server.js
'@).Replace("`r`n", "`n")
Set-Content (Join-Path $web 'localtube.service') -Encoding ascii -Value (Fill @'
# systemd unit: copy to /etc/systemd/system/localtube.service, set User and the two paths, then
#   sudo systemctl daemon-reload && sudo systemctl enable --now localtube
[Unit]
Description=LocalTube
After=network-online.target
Wants=network-online.target

[Service]
User=pi
WorkingDirectory=/home/pi/localtube
Environment=LAN=1
ExecStart=/usr/bin/node server.js
Restart=on-failure

[Install]
WantedBy=multi-user.target
'@).Replace("`r`n", "`n")
Set-Content (Join-Path $web 'DEPLOY.md') -Encoding ascii -Value (Fill @'
# Deploying the LocalTube web portal

Version @@VERSION@@, built @@STAMP@@. This folder is the whole portal: copy it to the machine that will host it.
(Not yet tried on a Raspberry Pi from the development PC; the steps below are what the server needs.)

## What the host needs
* **Node.js 20 or newer** (`sudo apt install nodejs npm`, or NodeSource / nvm if apt's version is older).
* **ffmpeg**: `sudo apt install ffmpeg` (Windows: ffmpeg.exe in `bin\`).
* **yt-dlp** for the host's CPU: for a 64-bit Raspberry Pi / ARM Ubuntu take `yt-dlp_linux_aarch64` from
  https://github.com/yt-dlp/yt-dlp/releases, save it as `bin/yt-dlp`, then `chmod +x bin/yt-dlp`.
* **deno** (yt-dlp needs a JavaScript runtime): the `aarch64-unknown-linux-gnu` zip from
  https://github.com/denoland/deno/releases, unzip to `bin/deno`, `chmod +x bin/deno`.
  The server uses `bin/<tool>` when it exists, otherwise the one on PATH.

## Install and run
    cd localtube
    npm ci --omit=dev          # installs express
    ./start.sh                 # = LAN=1 node server.js
Open `http://<host-address>:3000` from a device on the same network. The first start prints a **PIN**
(kept in `.cache/pin.txt`, or set `LT_PIN`); the web page asks for it once. Only private network addresses are accepted.
Without `LAN=1` it listens on 127.0.0.1 only.

To keep it running after a reboot use `localtube.service` (instructions inside).
Settings through environment variables: `PORT`, `MAX_HEIGHT` (default 720), `READ_AHEAD`, `OUT_BUFFER_MB`, `LT_PIN`.

## Updating yt-dlp
YouTube changes often; when playback starts failing run `bin/yt-dlp -U` (or download the new binary again).

## Phones and TVs
Install the APKs from `release/mobile` or `release/tv`. In the app: Settings -> Videos come from -> My PC, then enter
`<host-address>:3000` and the PIN. (By default the app looks videos up on the phone itself and needs no server.)

## Install as an app (PWA)
Browsers only offer to install a web app from HTTPS or from `localhost`. Over plain `http://<host>:3000` the page works
but cannot be installed; put the host behind HTTPS (for example a reverse proxy with a certificate) to install it.

## Network page (files on the client's own network)
The "Network" page plays files from a folder or mapped drive on the computer you browse from, and, with the small helper
program in `helper/` running on that computer, from SMB shares on its network. See `helper/README.md`.
Start it with the address you open the portal on: `localtube-helper.exe --origin http://<host-address>:3000`.
'@)

# ---------- Android ----------
if (-not $SkipAndroid) {
    Step 'Android release APK'
    $android = Join-Path $root 'android'
    $tools = Join-Path $root 'android-tools'
    $env:JAVA_HOME = Join-Path $tools 'jdk'
    $env:ANDROID_SDK_ROOT = Join-Path $tools 'sdk'
    $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
    $env:GRADLE_USER_HOME = Join-Path $tools 'gradle-home'
    $gradle = Join-Path $tools 'gradle\bin\gradle.bat'

    $gradleArgs = @('--no-daemon', '--console=plain', 'assembleRelease', "-PversionName=$Version", "-PversionCode=$code")
    if ($DebugKey) {
        $gradleArgs += '-PsignWithDebug=true'
        $signer = 'debug key'
    } else {
        $ks = Join-Path $android 'release.keystore'
        $props = Join-Path $android 'keystore.properties'
        if (-not (Test-Path $props)) {
            Write-Host 'Creating the release signing key (android\release.keystore). Keep it and keystore.properties safe:' -ForegroundColor Yellow
            Write-Host 'updates to an installed app only work when signed with the same key.' -ForegroundColor Yellow
            $bytes = New-Object byte[] 24
            $rng = New-Object Security.Cryptography.RNGCryptoServiceProvider
            $rng.GetBytes($bytes)
            $pw = ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', 'x')
            Remove-Item $ks -ErrorAction SilentlyContinue      # a leftover from an interrupted run
            $oldPref = $ErrorActionPreference; $ErrorActionPreference = 'Continue'   # keytool reports progress on stderr
            & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $ks -alias localtube -keyalg RSA -keysize 2048 -validity 10000 `
                -storepass $pw -keypass $pw -dname 'CN=LocalTube, O=LocalTube' 2>&1 | Out-Null
            $ErrorActionPreference = $oldPref
            if (-not (Test-Path $ks)) { throw 'keytool did not create the keystore' }
            Set-Content $props -Encoding ascii -Value "storeFile=release.keystore`nstorePassword=$pw`nkeyAlias=localtube`nkeyPassword=$pw"
        }
        $signer = 'release key (android\release.keystore)'
    }

    Push-Location $android
    try {
        & $gradle @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
    } finally { Pop-Location }

    $apk = Join-Path $android 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $apk)) { throw "No signed APK was produced (looked for $apk)" }
    if ((Get-Item $apk).LastWriteTime -lt (Get-Date).AddMinutes(-30)) { throw 'The APK is old: the build did not run.' }

    # check the signature
    $apksigner = Get-ChildItem (Join-Path $env:ANDROID_SDK_ROOT 'build-tools') -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($apksigner) { & $apksigner verify --print-certs $apk | Select-Object -First 3 | ForEach-Object { "  $_" } }

    Copy-Item $apk (Join-Path $rel 'mobile\LocalTube-mobile.apk')
    Copy-Item $apk (Join-Path $rel 'tv\LocalTube-tv.apk')

    Set-Content (Join-Path $rel 'mobile\INSTALL.txt') -Encoding ascii -Value (Fill @'
LocalTube for Android phones and tablets - version @@VERSION@@, built @@STAMP@@

Install
  1. Copy LocalTube-mobile.apk to the phone (USB, cloud drive, or messaging) and open it. Allow "install unknown apps"
     for the app you opened it from when Android asks.
  2. Or from a computer with adb:   adb install -r LocalTube-mobile.apk      (wireless: adb pair, then adb connect)

Updating
  An APK only installs over an existing LocalTube if it is signed with the same key. A build made with the release key
  does NOT install over one made with the debug key: uninstall the old one first (this clears its history and settings),
  or build with  npm run release:debugkey  to keep updating in place.

First start
  The app looks videos up on the phone itself: nothing else to set up. To use your own server instead:
  Settings -> Videos come from -> My PC, then the server address (host:3000) and its PIN.
'@)
    Set-Content (Join-Path $rel 'tv\INSTALL-TV.txt') -Encoding ascii -Value (Fill @'
LocalTube for Android TV / Google TV / Fire TV - version @@VERSION@@, built @@STAMP@@

This is the same app as the phone one (one APK): it shows up in the TV launcher and switches to its TV layout, with
D-pad navigation, on its own. Settings -> Appearance -> Layout can force TV or Phone.

Install (pick one)
  * adb:  enable developer options and network debugging on the TV, then
          adb connect <tv-address>:5555
          adb install -r LocalTube-tv.apk
  * Files: put LocalTube-tv.apk on a USB stick or cloud drive and open it with a file manager app on the TV
          (allow installs from unknown sources when asked).
  * Fire TV: the "Downloader" app can fetch the APK from a link you host.

Updating: same rule as the phone - only an APK signed with the same key installs over the old one (see ..\mobile\INSTALL.txt).

On a TV the playback stops when you leave the app (there is no notification to control it from).
'@)

    $apkInfo = "$signer, versionCode $code"
} else { $apkInfo = 'skipped' }

# ---------- zip + checksums ----------
Step 'Packaging'
$zip = Join-Path $rel 'LocalTube-web.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $web '*') -DestinationPath $zip
$sums = Get-ChildItem $rel -Recurse -File -Include '*.apk', '*.zip', '*.exe' | ForEach-Object {
    '{0}  {1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.FullName.Substring($rel.Length + 1)
}
Set-Content (Join-Path $rel 'SHA256SUMS.txt') -Encoding ascii -Value $sums

Write-Host "`nDone. Version $Version ($stamp). Android: $apkInfo" -ForegroundColor Green
Get-ChildItem $rel -Recurse -File | Where-Object { $_.FullName -notmatch '\\public\\' } | ForEach-Object { '  ' + $_.FullName.Substring($rel.Length + 1) + '   ' + ('{0:N1} MB' -f ($_.Length / 1MB)) }
Write-Host '  (web\public\ and the rest of the web files are in release\web, zipped as release\LocalTube-web.zip)'
