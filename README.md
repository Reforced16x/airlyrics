# AirLyrics

A client-side Minecraft 1.8.9 mod (Ornithe / OneClient) that renders real-time synced lyrics floating in the 3D world and on a karaoke HUD overlay, pulled directly from your active desktop media session without needing any API keys.

## Features

- **EvergreenHUD-Grade Media & Lyrics Sync:**
  - Direct WinRT COM (`GlobalSystemMediaTransportControlsSessionManager`) polling via JNA (`200ms` interval) on Windows with exact post-COM `LastUpdatedTime` age compensation, stalled-position anchor synthesis, PowerShell fallback, and duration-aware MPRIS (`playerctl` / `gdbus`) on Linux.
  - Multi-provider lookup with channel-suffix (`VEVO` / `- Topic`) normalization & title splitting across [`lrc.red`](https://lrc.red) (YAML word-level sync), [`lrclib.net`](https://lrclib.net) (duration-scored & enhanced LRC word tags), KuGou (`KRC` decrypted word-sync), and NetEase fallback.
  - Beat-paced syllable and punctuation word-timing estimator (`estimateWordTimings`) for plain line-synced tracks, plus sub-frame bidirectional exponential clock smoothing and seek recovery.
- **Full Ornithe 1.8.9 OneConfig Support:**
  - Annotated OneConfig v1 configuration screen (`Display`, `Typography & Glow`, `Effects & Sync`) with switches, sliders, font dropdown, live RGB/Chroma color pickers, and quick `Reset HUD` / `Reset All` actions.
  - Interactive HUD Editor placement, proportional scaling, and live sample preview via `OneConfigHudWrapper` (falls back automatically to the built-in two-tab settings GUI if OneConfig is absent).
- **Apple Lyrics Style & World Apple Effect (`appleStyle`, `worldAppleStyle`):** Toggle a frosted rounded Apple Music-style rolling lyrics card on the HUD, and apply the progressive karaoke sub-glyph wipe, spring swell, and bounce directly to 3D floating in-world lyrics.
- **Sky Line-by-Line Lyrics (`skyLyrics`):** Display a large, yaw-following karaoke marquee high in the sky dome (`15°`–`72°` elevation, `60%`–`300%` scale) with progressive wipe fill and context lines.
- **Font Selector (`fontIndex`):** Switch seamlessly across HUD, 3D World, and Sky between vanilla **Minecraft**, **Clean Sans**, **Sans Bold**, **Rounded UI**, **Editorial Serif**, and **JetBrains / Mono** anti-aliased glyph atlases.
- **Song Thumbnail Gradient (`thumbnailGradient`, ON by default):** Automatically reads the playing song's cover art thumbnail (via Windows GSMTC, Linux MPRIS, or album artwork lookup) and extracts a two-stop vivid gradient with smooth crossfading across tracks.
- **Smooth Per-Glyph Glow, Gradients & Moving Gradients:** Hardware linear-filtered per-character radial glow coherently matched to static, moving, or cover-art per-character gradients (no shadow punch-through, compatible with shaders).
- **Opacity Setting:** Configurable opacity (`10%`–`100%`) applied cleanly across world words, sky marquee, HUD cards, and glow halos.

## Requirements

- **Minecraft:** `1.8.9`
- **Mod Loader:** Fabric Loader `>=0.15.0` with Ornithe (compatible with OneClient)
- **Optional:** OneConfig v1 for Ornithe `1.8.9` (`oneconfigv1 >=1.6.0`)
- **OS:** Windows 10 / 11 or Linux (MPRIS-compatible players; uses `playerctl` if installed, otherwise built-in `gdbus`)

## Usage

1. Drop `airlyrics-1.1.0+mc1.8.9.jar` into your `mods` folder.
2. Start playing music in Spotify, your browser, or any desktop player exposing Windows GSMTC or Linux MPRIS.
3. Press **`L`** in-game (rebindable under Controls -> **AirLyrics**) to open OneConfig's AirLyrics settings (or the built-in fallback GUI).

Core config persists at `config/airlyrics.json` alongside OneConfig's profile storage.

## Building from Source

Requires JDK 17 or 21 on `PATH` (targets Java 8 bytecode).

On Windows:
```powershell
.\build.ps1
```

On Linux / macOS (with Gradle 8.12.1):
```bash
gradle build
```

The compiled mod jar is output to `build/libs/`.

## Contact

Join the [Discord](https://discord.gg/aqSEzgWnYy) for support or feedback.

## License

Licensed under the [MIT License](LICENSE) by Reforced.
