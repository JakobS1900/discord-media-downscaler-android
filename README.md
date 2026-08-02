# Discord Media Downscaler — Android

Android port of the [desktop Discord Media Downscaler](https://github.com/JakobS1900/discord-media-downscaler). Compresses images, video, and audio to fit Discord's tier limits (10 / 25 / 50 / 500 MB).

Built with Kotlin + Jetpack Compose + ffmpeg-kit. Targets Android 14 (Pixel 7 Pro).

## Download the APK

The CI builds a debug-signed APK on every push to `main`. Grab the latest from:

**GitHub → Actions → "Build APK" workflow → latest run → Artifacts → `dmd-debug-apk`**

The APK is debug-signed (the standard Android debug keystore), which means it sideloads cleanly but is not Play-Store-compliant. For personal use this is fine.

## Install on your Pixel 7 Pro

### Option A — adb (PC required)
```bash
adb install -r app-debug.apk
```

### Option B — Direct install (no PC)
1. Download `app-debug.apk` to your phone (or transfer via USB / Drive).
2. Open the Files app, tap the APK.
3. If prompted, enable **Install unknown apps** for the Files app and try again.
4. Tap **Install**.

## How it works

| Media | Method |
|---|---|
| JPEG | Binary-search Bitmap.compress quality (1–95); rescale on overflow |
| PNG | Lossless WebP first; falls back to lossy WebP (alpha) or JPEG (no alpha) |
| WebP | Binary-search quality |
| Animated GIF | FFmpeg palettegen + paletteuse with width fallback |
| Video | Two-pass libx264 with 9-step resolution ladder + 5-step bitrate backoff |
| MP3/AAC/M4A | libmp3lame, binary-search bitrate, mono fallback |
| WAV/FLAC | libopus → .ogg, binary-search bitrate, mono fallback |
| OGG/other | libvorbis, binary-search bitrate, mono fallback |

Outputs land in `Downloads/DiscordDownscaler/` and are visible to other apps (Discord, Photos, etc.).

## Permissions

- **No storage permission needed** — input files are picked via the system file picker (SAF), output is written via MediaStore.
- **Notifications** — required to keep long video encodes alive in the background.

## Build it yourself

You need JDK 17 + Android Studio (Hedgehog or newer) + Android SDK 34.

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## License

GPL-3.0, because this links ffmpeg-kit's `full-gpl` flavour, which bundles
libx264, libmp3lame and libvorbis. The full licence text is in
[LICENSE](LICENSE).

Copyright (C) 2026 Jakob Stanfield

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. It is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR
A PARTICULAR PURPOSE. See the GNU General Public License for more details.

Note that the desktop version this was ported from is MIT, because it shells
out to a separate FFmpeg binary rather than linking a GPL build into the same
process.
