# ffmpeg sidecar (LGPL v3)

Lyrico Desktop shells out to `ffmpeg.exe` to decode audio for ReplayGain analysis. This file records
exactly which binary that is, why it is a separate process rather than a linked library, and what
licence obligations follow. The binary itself is **not** committed (`.gitignore` excludes `/build`);
`scripts/fetch-ffmpeg.ps1` downloads it and verifies the hash below.

## What is used

| | |
|---|---|
| Upstream project | [BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds) (rolling `autobuild-*` release) |
| Release | `autobuild-2026-10-10-13-04` — ffmpeg `N-127271-gba987fe24d` (2026-10-10) |
| Artifact | `ffmpeg-N-127271-gba987fe24d-win64-lgpl.zip` (177,748,212 bytes) |
| URL | `https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-10-10-13-04/ffmpeg-N-127271-gba987fe24d-win64-lgpl.zip` |
| SHA256 | `32b374c9831ae5640e33977baa4cf04a59704f616a0c3743654697fb3c7f26de` |
| Extracted | `bin/ffmpeg.exe` (138,263,040 bytes) → `build/ffmpeg/windows-x64/ffmpeg.exe` |
| Also extracted | `LICENSE.txt` (LGPL v3 text, 7,651 bytes) → `build/ffmpeg/windows-x64/LICENSE.txt` |
| **Deliberately not used** | `ffprobe.exe`, `ffplay.exe` (each another ~138 MB), `doc/`, `presets/` |

Only the static LGPL build is accepted: `scripts/fetch-ffmpeg.ps1` fails if the downloaded archive's
hash does not match, and fails again if the extracted binary's `-version` configuration line contains
`--enable-gpl`. The build's configuration does contain `--enable-version3` (LGPL v3 rather than v2.1)
and does **not** contain `--enable-gpl`, `--enable-libx264`, `--enable-libx265` or `--enable-libxvid`.

Verified configuration (abridged, from `ffmpeg.exe -version`):

```
--enable-version3 --disable-debug --enable-pthreads ... --disable-libx264 --disable-libx265
--disable-libxvid --disable-frei0r ...
```

Decoders Lyrico relies on are present as built-ins: `flac`, `mp3`, `alac`, `ape`, `vorbis`, `opus`,
`aac`, plus the `pcm_*` family.

## Why an external process

Android decoded with `MediaExtractor` + `MediaCodec` (platform codecs). Windows has no equivalent
platform decoder that is usable from the JVM without shipping a decoders stack anyway, so C6d chose
"bundle ffmpeg and pipe PCM out of it" over linking libav* through JNI:

- The JNI surface stays tiny: the existing `ebur128.dll` bridge already accepts raw float PCM
  (`LibEbuR128.processDirect`), so ffmpeg only has to produce `pcm_f32le`.
- Lyrico never links against ffmpeg libraries; it launches an unmodified upstream executable and
  parses its stdout/stderr. Process separation keeps the LGPL boundary unambiguous.

Command shape (see `platform/FfmpegAudioDecoder.kt` for the authoritative list and the reasons each
argument is load-bearing):

```
ffmpeg.exe -nostdin -hide_banner -i <song> -map 0:a:0 -c:a pcm_f32le -f wav -
```

## Licence obligations

ffmpeg is LGPL v3 (`--enable-version3`), unmodified. Therefore:

1. **Licence text ships next to the binary.** `build/ffmpeg/windows-x64/LICENSE.txt` is extracted
   from the archive by the fetch script and is packaged beside `ffmpeg.exe`.
2. **Corresponding source is offered, not withheld.** The binary is upstream's, unmodified; source is
   ffmpeg upstream (`https://git.ffmpeg.org/ffmpeg.git`, tag/commit `N-127271-gba987fe24d`) built by
   BtbN's build scripts (`https://github.com/BtbN/FFmpeg-Builds`, tag
   `autobuild-2026-10-10-13-04`), which are the scripts that produced this artifact.
3. **No relicensing of Lyrico.** An unmodified LGPL executable launched as a separate process does not
   impose LGPL terms on Lyrico's own sources.

Open item for P6 packaging: `OpenSourceLicenceScreen` must list this entry (name, version, licence,
source offer) alongside taglib/QuickJS. PLAN.md tracks it as a P6 deliverable — the packaged installer
is what makes the obligation user-visible, and packaging has not happened yet.

## Operational cost

Measured during C6d: spawning `ffmpeg.exe` costs roughly 50–100 ms per song on this machine
(the static 138 MB image is loaded once and shared between processes by the Windows image loader, so
`concurrency: 3` does not multiply that memory cost). A large batch therefore pays process start-up
per song — a few minutes across thousands of songs — in exchange for not bundling libavcodec into the
JVM process. PLAN.md records this trade-off under the ReplayGain decision (方案 A).
