# Kinora for Android

A native Android app (Kotlin, Jetpack Compose, Media3). **By default it needs nothing but the phone:** it looks videos up
on YouTube itself (NewPipeExtractor) and plays them, no PC and no PIN. It has native playback, background audio, a
notification with lock-screen and Bluetooth controls, and Material You colors.

## Use it

Copy `LocalTube.apk` to the phone and install it (allow "install unknown apps" for your file manager or browser). Open it.
That's all: search and play.

### Optional: use your PC instead

Settings > Videos come from > **My PC**. The PC server (yt-dlp + ffmpeg) can do 1440p/4K, and is the fallback if YouTube
changes something and the phone's lookup stops working until the app is updated.

1. On the PC, in the LocalTube folder: `npm run lan`
   It prints the PC's addresses and a 6-digit PIN (also kept in `.cache/pin.txt`).
   The first time, Windows asks whether Node may use the network: choose **Private networks**.
2. In the app, enter the address (for example `192.168.100.178:3000`) and the PIN, tap **Connect**.

The phone and PC must be on the same Wi-Fi. `npm start` (no `lan`) keeps the server PC-only, and the app can't connect.

## TV and car

- **Android TV:** it appears in the TV's Apps row. Use the remote: D-pad to move (a blue outline shows the focus), OK to select,
  and in the player Left/Right seek 10 s and OK plays/pauses (with the controls hidden).
- **Android Auto / Automotive:** the app declares itself as a media app with a browsable library (Recently watched, Playlists) and
  plays audio only in the car. Not yet tested on a real head unit or the Desktop Head Unit. Sideloaded media apps need
  "Unknown sources" enabled in Android Auto's developer settings.

## How the phone plays without the PC

`DeviceSource` (NewPipeExtractor) returns separate video-only and audio-only URLs. `TubeMediaSourceFactory` merges them
(`MergingMediaSource`); because they are real HTTP ranges, ExoPlayer seeks natively (no keyframe pipe, no `/api/start`).
`YtChunkedDataSource` reads them like the PC server does, because a plain download from YouTube is throttled
(see CLAUDE.md, facts 3 and 9). The PC's PIN header is never sent to YouTube.

## What it does

- Home (continue watching, playlists, recent), Search (or paste a link), Library (playlists and history), Settings.
- Player: video or audio-only, seek bar, previous/next, queue (reorder, remove, save as playlist), full screen in landscape.
- Defaults in Settings (stream type, quality up to 4K, autoplay, theme). The tune button in the player overrides stream,
  quality, speed and autoplay for what is playing now, without changing your defaults.
- Resume where you stopped, watch history, playlists. All stored on the phone.
- Keeps playing with the screen off. Notification and headset buttons control it.

Not in the app yet: subtitles (the PC server supports them, and the web player shows them). 1440p/4K on the phone source
is untested (YouTube serves those as VP9, which needs a capable decoder; the lookup prefers H.264 where it exists).

## Build it yourself

    powershell -NoProfile -ExecutionPolicy Bypass -File android\build-apk.ps1

Uses the toolchain in `android-tools\` (JDK 17, Gradle 8.7, Android SDK 34, and Gradle's cache), all inside the project.
The APK is copied to `LocalTube.apk`. You can also open the `android` folder in Android Studio.

## Safety

The server only answers this PC and private (home network) addresses, never the internet, even if a router port were forwarded.
Other devices need the PIN; five wrong tries lock that device out for five minutes. Traffic is plain http on your own network.
