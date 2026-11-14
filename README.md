# AirLyrics

A client-side Minecraft 1.8.9 mod (Ornithe / OneClient) that renders real-time synced lyrics floating in the world and on a karaoke HUD bar, pulled directly from your active Windows media session without needing any API keys.

## Features

- **Floating 3D World Lyrics:** Words or full lines pop into the air in front of your camera, gently drift upward, and fade out. Works across first-person and third-person perspectives.
- **Karaoke HUD Bar:** Bottom-left overlay showing live line progress and the upcoming line.
- **Zero-Auth Media Sync:** Reads the active Windows media session (Spotify, browsers, desktop players) via Windows Media Transport Controls.
- **Automatic Lyrics Lookup:** Fetches timed `.lrc` lyrics from [lrclib.net](https://lrclib.net) with NetEase fallback.
- **In-Game Settings GUI (`L`):** Toggle world/HUD display, switch between word-by-word or line mode, scatter or centered layout, uppercase, pastel or solid color, scale (`50%`–`250%`), and sync offset (`±50ms` steps).

## Requirements

- **Minecraft:** `1.8.9`
- **Mod Loader:** Fabric Loader `>=0.15.0` with Ornithe (compatible with OneClient)
- **OS:** Windows 10 / 11 (media session detection uses `Windows.Media.Control` via PowerShell)

## Usage

1. Drop the built `airlyrics-<version>.jar` into your `mods` folder.
2. Start playing music in Spotify or any Windows media player that integrates with Windows media controls.
3. Press **`L`** in-game (rebindable in Controls under **AirLyrics**) to open the settings screen.

Config is stored at `config/airlyrics.json`.

## Building from Source

Requires JDK 17 or 21 on `PATH` (targets Java 8 bytecode).

On Windows:
```powershell
.\build.ps1
```

Or with Gradle 8.12.1 installed:
```bash
gradle build
```

The compiled mod jar will be output to `build/libs/`.

## Contact

Join the [Discord](https://discord.gg/aqSEzgWnYy) for support or feedback.

## License

Licensed under the [MIT License](LICENSE) by Reforced.
