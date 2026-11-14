package com.airlyrics;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MediaTracker {
    public static volatile String currentTitle = "";
    public static volatile String currentArtist = "";
    public static volatile boolean isPlaying = false;

    private static volatile long lastReportedPositionMs = 0;
    private static volatile long lastUpdateTimestampMs = 0;
    private static volatile String lastLog = "";

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

    private static final String PS_ENCODED =
        Base64.getEncoder().encodeToString(PS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));

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

    private static void pollMedia() {
        if (!LyricsConfig.enabled) return;
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", PS_ENCODED);
            pb.redirectErrorStream(true);
            p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log("powershell timed out");
                return;
            }

            String result = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("\uFEFF")) line = line.substring(1);
                    if (line.startsWith("OK\t") || line.equals("NONE") || line.startsWith("ERR\t")) {
                        result = line;
                    } else if (!line.isEmpty()) {
                        log("powershell: " + line);
                    }
                }
            }

            if (result == null) {
                isPlaying = false;
                log("no usable output from powershell");
                return;
            }
            if (result.equals("NONE")) {
                isPlaying = false;
                log("no active media session");
                return;
            }
            if (result.startsWith("ERR\t")) {
                isPlaying = false;
                log("powershell error: " + result.substring(4));
                return;
            }

            String[] parts = result.split("\t", 5);
            if (parts.length < 5) {
                isPlaying = false;
                log("unexpected output: " + result);
                return;
            }

            boolean playing = "Playing".equalsIgnoreCase(parts[1].trim());
            long pos = (long) Double.parseDouble(parts[2].trim());
            String title = parts[3].trim();
            String artist = parts[4].trim();

            isPlaying = playing;
            lastReportedPositionMs = pos;
            lastUpdateTimestampMs = System.currentTimeMillis();

            if (!title.equals(currentTitle) || !artist.equals(currentArtist)) {
                currentTitle = title;
                currentArtist = artist;
                log("now playing: " + artist + " - " + title);
                LyricsRenderer.clearActiveWords();
                LyricsFetcher.fetchAsync(title, artist);
            }
        } catch (Exception e) {
            log("poll failed: " + e);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }
}
