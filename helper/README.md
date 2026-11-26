# Kinora helper

A small program that lets the Kinora **web page** browse and play files from SMB shares (Windows shares, Samba, most NAS boxes)
on the network **the computer running the browser is on**. A browser can't speak SMB, and the Kinora server (for example a
Raspberry Pi) may be on a different network, so this runs on the client's side.

It listens only on `127.0.0.1`, finds SMB hosts on the local network (port 445, at most a /24), signs in with the user name and
password you type into the web page (kept in memory only), and serves folder listings and file bytes with HTTP Range, so
seeking works. Files never go through the Kinora server.

## Use

1. Run it on the computer where you open the web page, telling it the portal's address:

       kinora-helper.exe --origin http://192.168.1.20:3000

   (`http://localhost:3000` and `http://127.0.0.1:3000` are always allowed. If the portal is served over HTTPS, give that address.)
2. It prints a **pairing code**. In the web page open **Network → Find on the network**, and type the code once.
3. Pick a computer, sign in (blank user name = guest), pick a share, and play.

Options: `--origin <url>` (repeat for more portals), `--port <n>` (default 47821), `--demo <folder>` (serve a local folder as a
pretend server, to try the page without a real share).

The pairing code is kept in `%APPDATA%\KinoraHelper\pairing-code.txt` (`~/.config/KinoraHelper/` elsewhere). Delete that
file to get a new one.

## Safety

* Loopback only; requests need the pairing code; other `Host` headers are refused (DNS rebinding).
* Cross-origin calls are allowed only from the portals you list with `--origin`.
* Paths are confined to the share (no `..`).

## Build

Needs Rust. Project-local cargo home, so nothing is written to `~/.cargo`:

    set CARGO_HOME=d:\Work\Kinora\helper-tools\cargo-home
    cargo build --release

The result is `target\release\kinora-helper.exe`; a copy is in `dist\`. Uses the pure-Rust `smb` crate (SMB 2/3, NTLM/Kerberos).
