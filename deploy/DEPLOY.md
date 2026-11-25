# Deploying the LocalTube web portal

Version @@VERSION@@, built @@STAMP@@. This folder is the whole portal: copy it to the machine that will host it.
It runs on Windows, and on Linux (Ubuntu / Debian, x86_64 or arm64 such as a Raspberry Pi).
(Written on a Windows PC and not yet run on a Linux host: if a step fails there, the message from `install.sh` says which.)

## Ubuntu / Debian server (quick way)
    mkdir localtube && tar xzf LocalTube-web.tar.gz -C localtube     # or: unzip LocalTube-web.zip -d localtube
    cd localtube
    sh install.sh --service       # installs ffmpeg + Node if missing, downloads yt-dlp and deno into bin/ if they are not
                                  # already there, runs npm ci, and starts LocalTube at boot as the current user
Leave out `--service` to run it by hand with `sh start.sh`. `install.sh` uses sudo only for apt and the service, and
installs only what is missing: ffmpeg and Node already on the machine are left alone. If `bin/` already holds `yt-dlp`
and `deno` for this CPU (a release built with them bundled), nothing is downloaded for them.

### What it sets up (to do it by hand)
* **Node.js 20 or newer** (`sudo apt install nodejs npm`, or NodeSource / nvm if apt's version is older).
* **ffmpeg**: `sudo apt install ffmpeg`.
* **yt-dlp** for the CPU: `yt-dlp_linux` (x86_64) or `yt-dlp_linux_aarch64` (arm64) from
  https://github.com/yt-dlp/yt-dlp/releases, saved as `bin/yt-dlp`, then `chmod +x bin/yt-dlp`.
* **deno** (yt-dlp needs a JavaScript runtime): the `x86_64-unknown-linux-gnu` or `aarch64-unknown-linux-gnu` zip from
  https://github.com/denoland/deno/releases, unzipped to `bin/deno`, `chmod +x bin/deno`.
  The server uses `bin/<tool>` when it exists, otherwise the one on PATH.
* `npm ci --omit=dev` (installs express).

## Windows host
Put `yt-dlp.exe`, `ffmpeg.exe` and `deno.exe` in `bin\`, run `npm ci --omit=dev`, then `set LAN=1&& node server.js`.

## Running
`sh start.sh` is `LAN=1 node server.js`. Open `http://<host-address>:3000` from a device on the same network. The first
start prints a **PIN** (kept in `.cache/pin.txt`, or set `LT_PIN`); the web page asks for it once. Only private network
addresses are accepted. Without `LAN=1` it listens on 127.0.0.1 only. With the service: `systemctl status localtube`,
`journalctl -u localtube -f`. If a firewall is on: `sudo ufw allow 3000/tcp`.
Settings through environment variables: `PORT`, `MAX_HEIGHT` (default 720), `READ_AHEAD`, `OUT_BUFFER_MB`, `LT_PIN`.

## Updating
YouTube changes often; when playback starts failing run `bin/yt-dlp -U` (or download the new binary again), then
`sudo systemctl restart localtube`. To update LocalTube itself, unpack the new release over the folder (`.cache/` keeps
the PIN) and run `sh install.sh` again.

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
