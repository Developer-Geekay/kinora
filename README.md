# Kinora

Local-only YouTube player. Node serves a small web app on `127.0.0.1:3000` and uses
tools kept in `bin/` (nothing installed globally).

    npm start                 # then open http://127.0.0.1:3000
    npm run update-ytdlp      # run this when playback starts failing

`bin/` (git-ignored) holds `yt-dlp.exe`, `ffmpeg.exe`, `deno.exe` (yt-dlp needs a JS runtime
to solve YouTube's challenges). To rebuild it, download the latest of each from their GitHub releases.

## Android app

`npm run lan` also serves your home network (private addresses only, with a PIN shown at startup and kept in
`.cache/pin.txt`) so the Android app can connect. `npm start` stays PC-only. The app is in `android/`, and the
built `LocalTube.apk` is in this folder. See `android/README.md`. Other devices that open the address in a browser
get a PIN page, then the same web player.

## Pages (hash routes, so Back/Forward work)

| Route | What |
|---|---|
| `#/` | Home: continue watching, your playlists, recently watched |
| `#/results?q=…` | Search results, YouTube-style rows |
| `#/watch?v=ID[&list=PLAYLIST]` | Player. The right-hand queue shows only when you play a playlist or have more than one video queued |
| `#/history` | Watch history grouped by day, with search, remove, clear, pause |
| `#/playlists`, `#/playlist/ID` | Playlists |

Leaving the watch page keeps the video playing in a mini player (bottom-right) with play, expand and close.

## Using it

- **Settings** (gear): global defaults for theme, stream type, quality, player view (normal / theater),
  subtitles, autoplay, and whether watch history is saved.
- **Playback options** (tune button on the player): override stream, quality, view, speed, subtitles and
  autoplay for what you are watching now. Overrides last until Reset or a page reload and never change your defaults.
- **View**: normal, theater (`T`) or full screen (`F`).
- **Subtitles** (`C`): uploaded and auto-generated tracks. Drawn by the page, so they follow seeking and also show in audio-only mode.
- **Queue**: add or play-next from any item's menu, drag to reorder (or Move up/down), save as a playlist.
- **History**: recorded when a video starts playing. Resume positions are separate and survive Clear history.
- Shortcuts: `/` search, `Space` play, `J`/`L` ±10s, arrows ±5s / volume, `M` mute, `F` fullscreen,
  `T` theater, `C` subtitles, `A` audio only, `N`/`P` next/previous.

## How it works

- Video: yt-dlp resolves separate H.264 video + AAC audio (up to the chosen height), ffmpeg
  joins them into fragmented MP4. Seeking restarts ffmpeg at `?t=`.
- Seeking and A/V sync: video is copied, not re-encoded, so it can only start on a keyframe (often a few seconds
  before the time you asked for). `/api/start/:id?t=` finds that keyframe and ffmpeg starts audio and video on it
  together; the page uses the returned time as the stream's start so the clock, seek bar and subtitles match the picture.
  A seek therefore lands up to one keyframe interval (typically 2–6 s) earlier than the exact spot you clicked.
- Audio only: best audio stream, served with byte ranges so native seeking works.
- Both read from a loopback `/raw` endpoint with a parallel read-ahead loader: the stream is cut into windows
  (small first, then 4 MB), up to `READ_AHEAD` (default 4) download at once with the `range=` URL parameter
  (a plain download is throttled to about playback speed), failed windows retry, and expired URLs are re-resolved.
- ffmpeg's output goes through a `OUT_BUFFER_MB` (default 32) buffer, so the server stays tens of MB ahead of what
  the browser has asked for. Chrome only keeps a few seconds of a piped stream itself.
- Quality up to 1080p is H.264. 1440p and 4K are VP9 (muxed to MP4), which needs a machine that can decode it.
- The next queue item is resolved shortly before the current one ends, so it starts at once.
- Subtitles: `/api/subs/:id` has yt-dlp download the WebVTT track, then cleans it (auto-generated captions
  repeat lines as they roll) and returns cues as JSON.
- Files: `server.js`, `public/index.html`, `public/app.css`, `public/app.js`.
- Optional `cookies.txt` (Netscape format) in this folder is passed to yt-dlp for age-restricted videos.
- Not supported yet: livestreams, YouTube playlists.
