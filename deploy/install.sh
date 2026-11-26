#!/bin/sh
# Sets up Kinora on Ubuntu / Debian (x86_64 or arm64, e.g. a Raspberry Pi). Run it from this folder:
#   sh install.sh              install ffmpeg, yt-dlp, deno and the Node packages (only what is missing)
#   sh install.sh --service    ...and also start it at boot (systemd), as the current user
# Downloads yt-dlp and deno from their official GitHub releases into ./bin unless they are already there. Safe to run again.
set -e
cd "$(dirname "$0")"
say() { printf '\n== %s\n' "$1"; }

case "$(uname -m)" in
  x86_64|amd64)  YTDLP=yt-dlp_linux;         DENO=x86_64-unknown-linux-gnu ;;
  aarch64|arm64) YTDLP=yt-dlp_linux_aarch64; DENO=aarch64-unknown-linux-gnu ;;
  *) echo "Unsupported CPU: $(uname -m) (need x86_64 or arm64)"; exit 1 ;;
esac
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"

say "System packages"
need=""
for c in curl unzip ffmpeg node; do command -v "$c" >/dev/null 2>&1 || need="$need $c"; done
need=$(echo "$need" | sed 's/\bnode\b/nodejs npm/')
if [ -n "$need" ]; then $SUDO apt-get update && $SUDO apt-get install -y $need; fi
NODE_MAJOR=$(node -p 'process.versions.node.split(".")[0]')
if [ "$NODE_MAJOR" -lt 20 ]; then
  echo "Node.js $NODE_MAJOR is too old: Kinora needs 20 or newer (see https://nodejs.org or use nvm)."; exit 1
fi

mkdir -p bin
# tools shipped in bin/ (a tar/zip made on Windows has lost the executable bit): just make them executable
for t in yt-dlp deno; do [ -f "bin/$t" ] && chmod +x "bin/$t"; done
if [ ! -f bin/yt-dlp ]; then
  say "yt-dlp ($YTDLP)"
  curl -fL --progress-bar -o bin/yt-dlp "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$YTDLP"
  chmod +x bin/yt-dlp
fi
if [ ! -f bin/deno ]; then
  say "deno ($DENO)"
  curl -fL --progress-bar -o bin/deno.zip "https://github.com/denoland/deno/releases/latest/download/deno-$DENO.zip"
  unzip -o -q bin/deno.zip -d bin && rm -f bin/deno.zip
  chmod +x bin/deno
fi
# a bundled arm64 binary on an x86_64 machine would not run: say so instead of failing later
if ! bin/yt-dlp --version >/dev/null 2>&1; then
  echo "bin/yt-dlp does not run on this machine ($(uname -m)). Delete bin/yt-dlp and bin/deno and run install.sh again."; exit 1
fi

say "Node packages"
if [ -f package-lock.json ]; then npm ci --omit=dev; else npm install --omit=dev; fi
chmod +x start.sh install.sh 2>/dev/null || true

if [ "$1" = "--service" ]; then
  say "systemd service"
  me=$(id -un); here=$(pwd); node_bin=$(command -v node)
  # the service was called "localtube" before the rename: remove it, or it keeps port 3000
  if [ -f /etc/systemd/system/localtube.service ]; then
    $SUDO systemctl disable --now localtube || true
    $SUDO rm -f /etc/systemd/system/localtube.service
  fi
  sed -e "s|__USER__|$me|g" -e "s|__DIR__|$here|g" -e "s|__NODE__|$node_bin|g" kinora.service | $SUDO tee /etc/systemd/system/kinora.service >/dev/null
  $SUDO systemctl daemon-reload
  $SUDO systemctl enable --now kinora
  echo "Running as a service. Logs:  journalctl -u kinora -f"
fi

say "Done"
echo "Start it:  sh start.sh      (or use the service: sh install.sh --service)"
echo "Open http://<this-machine's-address>:3000 from a device on the same network. The PIN is printed on first start and kept in .cache/pin.txt."
