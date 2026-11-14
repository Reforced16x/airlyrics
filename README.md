# AirLyrics

A client-side Minecraft 1.8.9 mod (Ornithe / OneClient) that renders real-time synced lyrics floating in the 3D world and on a karaoke HUD overlay, pulled directly from your active desktop media session without needing any API keys.

## Features

- **EvergreenHUD-Grade Media & Lyrics Sync:**
  - Direct WinRT COM (`GlobalSystemMediaTransportControlsSessionManager`) polling via JNA on Windows with timeline `LastUpdatedTime` age compensation, PowerShell fallback, and duration-aware MPRIS (`playerctl` / `gdbus`) on Linux.
  - Multi-provider lookup across [`lrc.red`](https://lrc.red) (YAML word-level sync), [`lrclib.net`](https://lrclib.net) (duration-scored & enhanced LRC word tags), KuGou (`KRC` decrypted word-sync), and NetEase fallback.
  - Beat-paced syllable and punctuation word-timing estimator (`estimateWordTimings`) for plain line-synced tracks, plus exponential playback clock smoothing and seek recovery.
- **Full Ornithe 1.8.9 OneConfig Support:**
  - Annotated OneConfig v1 configuration screen (`Config` + `Category.HUD`) with switches, sliders, live RGB/Chroma color picker, `@DependsOn` visibility, and quick `Refetch` / `Reset` buttons.
  - Interactive HUD Editor placement, scaling, and live sample preview via `OneConfigHudWrapper` (falls back automatically to the built-in settings GUI if OneConfig is absent).
- **Floating 3D World & HUD Display:** Word-by-word or full-line floating lyrics projected in 3D space (scatter or centered arc) plus a smooth HUD bar.
- **Opacity Setting:** Configurable opacity (`10%`–`100%`) applied cleanly across world words, HUD lines, shadows, and bloom halos.
- **Shine & Glow (Shader & Dynamic Lighting Safe):** Toggleable emissive bloom and shimmer highlight using destination-alpha-preserving additive blending, full-bright lightmap preservation/restoration, `0.003F` alpha test threshold, and Cera shader text-batch / shadow-pass cooperation.
- **Bigger Important Lyrics (ON by default):** Automatically detects repeated chorus/hook lines and emphasized words and scales them larger (toggleable).

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
