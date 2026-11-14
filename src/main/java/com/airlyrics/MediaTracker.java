package com.airlyrics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaTracker {
    public static volatile String currentTitle = "";
    public static volatile String currentArtist = "";
    public static volatile boolean isPlaying = false;

    private static volatile long lastReportedPositionMs = 0;
    private static volatile long lastUpdateTimestampMs = 0;
    private static volatile String lastLog = "";

    private static volatile boolean playerctlUnavailable = false;
    private static volatile boolean gdbusUnavailable = false;

    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean IS_LINUX =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    private static final Pattern MPRIS_BUS_PATTERN =
        Pattern.compile("org\\.mpris\\.MediaPlayer2\\.[A-Za-z0-9_.-]+");
    private static final Pattern GVARIANT_INT_PATTERN =
        Pattern.compile("(-?\\d+)(?=\\s*[>),])");

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AirLyrics-Media");
        t.setDaemon(true);
        return t;
    });

    private static final String PS_SCRIPT = String.join("\n",
        "$ErrorActionPreference = 'Stop'",
        "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false",
        "try {",
        "  Add-Type -AssemblyName System.Runtime.WindowsRuntime",
        "  $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
        "  function Await($op, $type) { $t = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }",
        "  $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]",
        "  $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
        "  $session = $null",
        "  foreach ($s in $mgr.GetSessions()) { if ([string]$s.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { $session = $s; break } }",
        "  if ($session -eq $null) { $session = $mgr.GetCurrentSession() }",
        "  if ($session -eq $null) { 'NONE' } else {",
        "    $info = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
        "    $tl = $session.GetTimelineProperties()",
        "    $status = [string]$session.GetPlaybackInfo().PlaybackStatus",
        "    $pos = [math]::Round($tl.Position.TotalMilliseconds)",
        "    $age = [math]::Round(([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds)",
        "    if ($status -eq 'Playing' -and $age -gt 0) { $pos += $age }",
        "    $tab = [string][char]9",
        "    'OK' + $tab + $status + $tab + $pos + $tab + $info.Title + $tab + $info.Artist",
        "  }",
        "} catch { 'ERR' + [string][char]9 + $_.Exception.Message }"
    );

    private static final String PS_ENCODED = IS_WINDOWS
        ? Base64.getEncoder().encodeToString(PS_SCRIPT.getBytes(StandardCharsets.UTF_16LE))
        : "";

    public static void start() {
        EXECUTOR.scheduleWithFixedDelay(MediaTracker::pollMedia, 1000, 1500, TimeUnit.MILLISECONDS);
    }

    public static long getCurrentPositionMs() {
        if (!isPlaying) return lastReportedPositionMs + LyricsConfig.syncOffsetMs;
        long elapsed = System.currentTimeMillis() - lastUpdateTimestampMs;
        return lastReportedPositionMs + elapsed + LyricsConfig.syncOffsetMs;
    }

    private static void log(String msg) {
        if (!msg.equals(lastLog)) {
            lastLog = msg;
            System.out.println("[AirLyrics] " + msg);
        }
    }

    private static void clearSession(String reason) {
        isPlaying = false;
        log(reason);
    }

    private static void updateTrack(boolean playing, long posMs, String title, String artist) {
        boolean sameTrack = title.equals(currentTitle) && artist.equals(currentArtist);
        // Some Linux MPRIS players (e.g. older Spotify builds) keep reporting 0 during playback
        boolean stuckAtZero = playing && posMs <= 0 && lastReportedPositionMs == 0 && isPlaying && sameTrack;

        isPlaying = playing;
        if (!stuckAtZero) {
            lastReportedPositionMs = Math.max(0L, posMs);
            lastUpdateTimestampMs = System.currentTimeMillis();
        }

        if (!sameTrack) {
            currentTitle = title;
            currentArtist = artist;
            log("now playing: " + artist + " - " + title);
            LyricsRenderer.clearActiveWords();
            LyricsFetcher.fetchAsync(title, artist);
        }
    }

    private static void pollMedia() {
        if (!LyricsConfig.enabled) return;
        if (IS_WINDOWS) {
            pollWindows();
        } else if (IS_LINUX) {
            pollLinux();
        } else {
            clearSession("unsupported OS for media tracking: " + System.getProperty("os.name", "unknown"));
        }
    }

    private static void pollWindows() {
        try {
            CmdResult res = runCommand(8,
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", PS_ENCODED);
            if (res == null) {
                log("powershell timed out");
                return;
            }

            String result = null;
            for (String line : res.lines) {
                if (line.startsWith("OK\t") || line.equals("NONE") || line.startsWith("ERR\t")) {
                    result = line;
                } else if (!line.isEmpty()) {
                    log("powershell: " + line);
                }
            }

            if (result == null) {
                clearSession("no usable output from powershell");
                return;
            }
            if (result.equals("NONE")) {
                clearSession("no active media session");
                return;
            }
            if (result.startsWith("ERR\t")) {
                clearSession("powershell error: " + result.substring(4));
                return;
            }

            String[] parts = result.split("\t", 5);
            if (parts.length < 5) {
                clearSession("unexpected output: " + result);
                return;
            }

            boolean playing = "Playing".equalsIgnoreCase(parts[1].trim());
            long pos = (long) Double.parseDouble(parts[2].trim());
            updateTrack(playing, pos, parts[3].trim(), parts[4].trim());
        } catch (Exception e) {
            log("poll failed: " + e);
        }
    }

    private static void pollLinux() {
        if (!playerctlUnavailable && pollPlayerctl()) {
            return;
        }
        if (!gdbusUnavailable) {
            pollGdbus();
        }
    }

    private static boolean pollPlayerctl() {
        try {
            CmdResult res = runCommand(5,
                "playerctl", "-a", "metadata", "--format",
                "{{status}}\t{{position}}\t{{title}}\t{{artist}}");
            if (res == null) {
                log("playerctl timed out");
                return true;
            }
            if (res.exitCode != 0) {
                String out = res.joined().toLowerCase(Locale.ROOT);
                if (out.contains("no player")) {
                    clearSession("no active media session");
                    return true;
                }
                return false;
            }

            String[] chosen = null;
            for (String line : res.lines) {
                String[] parts = line.split("\t", 4);
                if (parts.length < 4) continue;
                String status = parts[0].trim();
                boolean playing = "Playing".equalsIgnoreCase(status);
                if (!playing && !"Paused".equalsIgnoreCase(status) && !"Stopped".equalsIgnoreCase(status)) {
                    continue;
                }

                boolean hasTitle = !parts[2].trim().isEmpty();
                if (chosen == null || (chosen[2].trim().isEmpty() && hasTitle)) {
                    chosen = parts;
                }
                if (playing && hasTitle) {
                    chosen = parts;
                    break;
                }
                if (playing && !"Playing".equalsIgnoreCase(chosen[0].trim())) {
                    chosen = parts;
                }
            }

            if (chosen == null) {
                clearSession("no active media session");
                return true;
            }

            boolean playing = "Playing".equalsIgnoreCase(chosen[0].trim());
            long posMs = parsePositionUsToMs(chosen[1]);
            updateTrack(playing, posMs, chosen[2].trim(), chosen[3].trim());
            return true;
        } catch (IOException e) {
            playerctlUnavailable = true;
            return false;
        } catch (Exception e) {
            log("playerctl poll failed: " + e);
            return false;
        }
    }

    private static void pollGdbus() {
        try {
            CmdResult listRes = runCommand(5,
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.DBus",
                "--object-path", "/org/freedesktop/DBus",
                "--method", "org.freedesktop.DBus.ListNames");
            if (listRes == null || listRes.exitCode != 0) {
                clearSession("gdbus session query failed");
                return;
            }

            List<String> players = new ArrayList<>();
            Matcher m = MPRIS_BUS_PATTERN.matcher(listRes.joined());
            while (m.find()) {
                players.add(m.group());
            }
            if (players.isEmpty()) {
                clearSession("no active media session");
                return;
            }

            List<String> playingBuses = new ArrayList<>();
            List<String> otherBuses = new ArrayList<>();
            for (String bus : players) {
                String statusRaw = gdbusGet(bus, "PlaybackStatus");
                String status = extractFirstGVariantString(statusRaw);
                if ("Playing".equalsIgnoreCase(status)) {
                    playingBuses.add(bus);
                } else {
                    otherBuses.add(bus);
                }
            }

            String[] match = pickBestBus(playingBuses);
            boolean chosenPlaying = match != null;
            if (match == null) {
                match = pickBestBus(otherBuses);
            }
            if (match == null) {
                clearSession("no active media session");
                return;
            }

            String chosenBus = match[0];
            String chosenTitle = match[1];
            String chosenArtist = match[2];

            long posMs = 0L;
            String posRaw = gdbusGet(chosenBus, "Position");
            if (posRaw != null) {
                Matcher pm = GVARIANT_INT_PATTERN.matcher(posRaw);
                if (pm.find()) {
                    posMs = parsePositionUsToMs(pm.group(1));
                }
            }

            updateTrack(chosenPlaying, posMs, chosenTitle.trim(), chosenArtist.trim());
        } catch (IOException e) {
            gdbusUnavailable = true;
            clearSession("neither playerctl nor gdbus is available on PATH");
        } catch (Exception e) {
            log("gdbus poll failed: " + e);
        }
    }

    private static String[] pickBestBus(List<String> buses) throws IOException, InterruptedException {
        String[] best = null;
        for (String bus : buses) {
            String[] meta = parseMprisMetadata(gdbusGet(bus, "Metadata"));
            if (best == null || (best[1].isEmpty() && !meta[0].isEmpty())) {
                best = new String[]{bus, meta[0], meta[1]};
            }
            if (!best[1].isEmpty()) break;
        }
        return best;
    }

    private static String gdbusGet(String bus, String property) throws IOException, InterruptedException {
        CmdResult res = runCommand(4,
            "gdbus", "call", "--session",
            "--dest", bus,
            "--object-path", "/org/mpris/MediaPlayer2",
            "--method", "org.freedesktop.DBus.Properties.Get",
            "org.mpris.MediaPlayer2.Player", property);
        if (res == null || res.exitCode != 0) return null;
        return res.joined();
    }

    private static CmdResult runCommand(long timeoutSec, String... cmd) throws IOException, InterruptedException {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            p.getOutputStream().close();

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("\uFEFF")) line = line.substring(1);
                    lines.add(line);
                }
            }
            return new CmdResult(p.exitValue(), lines);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static long parsePositionUsToMs(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return 0L;
        try {
            return Math.max(0L, (long) (Double.parseDouble(s) / 1000.0));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String extractFirstGVariantString(String raw) {
        if (raw == null) return "";
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                readGVariantString(raw, i, sb);
                return sb.toString();
            }
        }
        return "";
    }

    private static String[] parseMprisMetadata(String raw) {
        String title = "";
        String artist = "";
        if (raw == null || raw.isEmpty()) return new String[]{title, artist};

        int n = raw.length();
        int i = 0;
        while (i < n) {
            char ch = raw.charAt(i);
            if (ch != '\'' && ch != '"') {
                i++;
                continue;
            }

            StringBuilder keyBuf = new StringBuilder();
            i = readGVariantString(raw, i, keyBuf);
            String key = keyBuf.toString();

            int j = skipSpaces(raw, i);
            if (j >= n || raw.charAt(j) != ':') continue;
            j = skipSpaces(raw, j + 1);
            if (j < n && raw.charAt(j) == '<') {
                j = skipSpaces(raw, j + 1);
            }

            if ("xesam:title".equals(key)) {
                if (j < n && (raw.charAt(j) == '\'' || raw.charAt(j) == '"')) {
                    StringBuilder val = new StringBuilder();
                    i = readGVariantString(raw, j, val);
                    title = val.toString();
                }
            } else if ("xesam:artist".equals(key)) {
                if (j < n && raw.charAt(j) == '[') {
                    List<String> artists = new ArrayList<>();
                    j++;
                    while (j < n) {
                        j = skipSpaces(raw, j);
                        if (j >= n) break;
                        char cur = raw.charAt(j);
                        if (cur == ']') {
                            j++;
                            break;
                        }
                        if (cur == '\'' || cur == '"') {
                            StringBuilder item = new StringBuilder();
                            j = readGVariantString(raw, j, item);
                            String s = item.toString().trim();
                            if (!s.isEmpty()) artists.add(s);
                        } else {
                            j++;
                        }
                    }
                    artist = String.join(", ", artists);
                    i = j;
                } else if (j < n && (raw.charAt(j) == '\'' || raw.charAt(j) == '"')) {
                    StringBuilder val = new StringBuilder();
                    i = readGVariantString(raw, j, val);
                    artist = val.toString();
                }
            }
        }
        return new String[]{title, artist};
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int readGVariantString(String s, int start, StringBuilder out) {
        char quote = s.charAt(start);
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i++);
            if (c == quote) return i;
            if (c == '\\' && i < n) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 <= n) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                                break;
                            } catch (NumberFormatException ignored) {}
                        }
                        out.append('u');
                        break;
                    default: out.append(esc); break;
                }
            } else {
                out.append(c);
            }
        }
        return n;
    }

    private static class CmdResult {
        final int exitCode;
        final List<String> lines;

        CmdResult(int exitCode, List<String> lines) {
            this.exitCode = exitCode;
            this.lines = lines;
        }

        String joined() {
            return String.join("\n", lines);
        }
    }
}
