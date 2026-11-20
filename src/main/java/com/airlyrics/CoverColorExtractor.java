package com.airlyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a vivid, high-readability two-stop text color gradient from the active track's
 * album thumbnail / cover art with smooth crossfading between tracks.
 */
public final class CoverColorExtractor {
    private static final long TRANSITION_MS = 450L;
    private static final int HUE_BUCKETS = 36;
    private static final Pattern NOISE_TAGS = Pattern.compile(
            "(?i)\\s*[\\(\\[]\\s*(?:official\\s*(?:music\\s*)?(?:video|audio|lyric\\s*video|visualizer)|hd|4k|lyrics?|remaster(?:ed)?(?:\\s+\\d{4})?|explicit|clean|feat\\.?[^\\)\\]]*|ft\\.?[^\\)\\]]*)[\\)\\]]"
    );
    private static final Pattern TOPIC_SUFFIX = Pattern.compile("(?i)(?:\\s*-\\s*topic|vevo)$");
    private static final Pattern DASHED_TITLE = Pattern.compile("^\\s*(.+?)\\s+-\\s+(.+?)\\s*$");

    private static volatile boolean hasGradient = false;
    private static volatile int prevStartRgb = 0x66F3FF;
    private static volatile int prevEndRgb = 0xFF7BE5;
    private static volatile int targetStartRgb = 0x66F3FF;
    private static volatile int targetEndRgb = 0xFF7BE5;
    private static volatile long transitionStartMs = 0L;
    private static volatile String activeTrackKey = "";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AirLyrics-CoverArt");
        t.setDaemon(true);
        return t;
    });

    private CoverColorExtractor() {
    }

    public static boolean hasCoverGradient() {
        return hasGradient;
    }

    public static int getStartRgb() {
        return interpolateStop(prevStartRgb, targetStartRgb);
    }

    public static int getEndRgb() {
        return interpolateStop(prevEndRgb, targetEndRgb);
    }

    public static void clear() {
        activeTrackKey = "";
        hasGradient = false;
    }

    /**
     * Triggered when track changes (or fresh inline thumbnail bytes arrive).
     * Decodes/extracts cover colors asynchronously without stalling media polling or rendering.
     */
    public static void onTrackChanged(String title, String artist, String album, byte[] inlineBytes) {
        if (title == null || title.trim().isEmpty()) {
            clear();
            return;
        }
        final String key = (artist != null ? artist : "") + "\u0000" + title;
        activeTrackKey = key;

        // Keep previous target as blend base while extracting new song's cover
        EXECUTOR.submit(() -> resolveCoverForTrack(key, title, artist, album, inlineBytes));
    }

    private static void resolveCoverForTrack(String trackKey, String title, String artist,
                                             String album, byte[] inlineBytes) {
        if (!trackKey.equals(activeTrackKey)) {
            return;
        }

        BufferedImage img = decodeImage(inlineBytes);

        if (img == null) {
            byte[] osBytes = fetchOsThumbnailBytes();
            if (!trackKey.equals(activeTrackKey)) {
                return;
            }
            img = decodeImage(osBytes);
        }

        if (img == null) {
            byte[] itunesBytes = fetchItunesArtBytes(title, artist, album);
            if (!trackKey.equals(activeTrackKey)) {
                return;
            }
            img = decodeImage(itunesBytes);
        }

        if (!trackKey.equals(activeTrackKey)) {
            return;
        }

        if (img != null) {
            int[] pair = extractGradient(img);
            if (pair != null && pair.length == 2 && trackKey.equals(activeTrackKey)) {
                applyExtractedGradient(pair[0], pair[1]);
                return;
            }
        }

        if (trackKey.equals(activeTrackKey)) {
            hasGradient = false;
        }
    }

    private static synchronized void applyExtractedGradient(int startRgb, int endRgb) {
        if (hasGradient) {
            prevStartRgb = getStartRgb();
            prevEndRgb = getEndRgb();
        } else {
            prevStartRgb = startRgb;
            prevEndRgb = endRgb;
        }
        targetStartRgb = startRgb & 0x00FFFFFF;
        targetEndRgb = endRgb & 0x00FFFFFF;
        transitionStartMs = System.currentTimeMillis();
        hasGradient = true;
    }

    private static int interpolateStop(int prevRgb, int targetRgb) {
        if (!hasGradient) {
            return targetRgb & 0x00FFFFFF;
        }
        long elapsed = System.currentTimeMillis() - transitionStartMs;
        if (elapsed >= TRANSITION_MS || elapsed < 0L) {
            return targetRgb & 0x00FFFFFF;
        }
        float raw = elapsed / (float) TRANSITION_MS;
        float eased = raw * raw * (3.0f - 2.0f * raw);
        return lerpRgb(prevRgb, targetRgb, eased);
    }

    private static BufferedImage decodeImage(byte[] bytes) {
        if (bytes == null || bytes.length <= 32) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] fetchOsThumbnailBytes() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return fetchWindowsPowerShellThumbnail();
        }
        if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            return fetchLinuxMprisThumbnail();
        }
        return null;
    }

    private static byte[] fetchWindowsPowerShellThumbnail() {
        try {
            String script = "$ErrorActionPreference='SilentlyContinue';"
                    + "Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                    + "$asTask=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask'-and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
                    + "$asStream=([System.IO.WindowsRuntimeStreamExtensions].GetMethods()|?{$_.Name -eq 'AsStreamForRead'-and $_.GetParameters().Count -eq 1})[0];"
                    + "function Aw($op,$t){$net=$asTask.MakeGenericMethod($t);$tsk=$net.Invoke($null,@($op));$tsk.Wait(1500)|Out-Null;$tsk.Result};"
                    + "$mgr=Aw ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]);"
                    + "$sessions=$mgr.GetSessions();"
                    + "$s=$sessions|?{$_.GetPlaybackInfo().PlaybackStatus -eq 'Playing'}|Select -First 1;"
                    + "if(-not $s){$s=$mgr.GetCurrentSession()};"
                    + "if($s){"
                    + "$p=Aw ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]);"
                    + "if($p -and $p.Thumbnail){"
                    + "$str=Aw ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]);"
                    + "if($str){$netStr=$asStream.Invoke($null,@($str));$ms=New-Object System.IO.MemoryStream;$netStr.CopyTo($ms);Write-Output ('B64=' + [Convert]::ToBase64String($ms.ToArray()))}"
                    + "}"
                    + "}";

            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("B64=")) {
                        out.append(line.substring(4).trim());
                    }
                }
            }
            process.waitFor(3000L, TimeUnit.MILLISECONDS);
            String b64 = out.toString();
            if (b64.length() > 32) {
                return Base64.getDecoder().decode(b64);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] fetchLinuxMprisThumbnail() {
        String artUrl = null;
        try {
            Process p = new ProcessBuilder("playerctl", "metadata", "mpris:artUrl").start();
            String text = readProcessText(p);
            if (p.waitFor(1500L, TimeUnit.MILLISECONDS) && p.exitValue() == 0 && !text.isEmpty()) {
                artUrl = text.trim();
            }
        } catch (Throwable ignored) {
        }

        if (artUrl == null || artUrl.isEmpty()) {
            try {
                Process p = new ProcessBuilder("dbus-send", "--session", "--dest=org.freedesktop.DBus",
                        "--type=method_call", "--print-reply", "/org/freedesktop/DBus",
                        "org.freedesktop.DBus.ListNames").start();
                String names = readProcessText(p);
                Matcher m = Pattern.compile("\"(org\\.mpris\\.MediaPlayer2\\.[^\"]+)\"").matcher(names);
                if (m.find()) {
                    String dest = m.group(1);
                    Process g = new ProcessBuilder("gdbus", "call", "--session", "--dest", dest,
                            "--object-path", "/org/mpris/MediaPlayer2", "--method",
                            "org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Metadata").start();
                    String meta = readProcessText(g);
                    Matcher um = Pattern.compile("'mpris:artUrl':\\s*<'([^']+)'>").matcher(meta);
                    if (um.find()) {
                        artUrl = um.group(1).trim();
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (artUrl == null || artUrl.isEmpty()) {
            return null;
        }
        try {
            if (artUrl.startsWith("file://")) {
                Path path = Paths.get(URI.create(artUrl));
                if (Files.isRegularFile(path)) {
                    return Files.readAllBytes(path);
                }
            } else if (artUrl.startsWith("http://") || artUrl.startsWith("https://")) {
                return downloadUrlBytes(artUrl, 3000);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] fetchItunesArtBytes(String title, String artist, String album) {
        try {
            String cleanTitle = cleanQueryPart(title);
            String cleanArtist = cleanQueryPart(artist);
            cleanArtist = TOPIC_SUFFIX.matcher(cleanArtist).replaceAll("").trim();

            Matcher dash = DASHED_TITLE.matcher(cleanTitle);
            if (dash.matches()) {
                String maybeArtist = dash.group(1).trim();
                String maybeTitle = dash.group(2).trim();
                if (!maybeArtist.isEmpty() && !maybeTitle.isEmpty()) {
                    if (cleanArtist.isEmpty() || cleanArtist.equalsIgnoreCase(cleanTitle)
                            || maybeArtist.toLowerCase(Locale.ROOT).contains(cleanArtist.toLowerCase(Locale.ROOT))) {
                        cleanArtist = maybeArtist;
                        cleanTitle = maybeTitle;
                    }
                }
            }

            StringBuilder term = new StringBuilder();
            if (!cleanArtist.isEmpty()) {
                term.append(cleanArtist).append(' ');
            }
            term.append(cleanTitle);
            if (term.length() == 0 && album != null && !album.isEmpty()) {
                term.append(album);
            }
            String q = term.toString().trim();
            if (q.isEmpty()) {
                return null;
            }

            String api = "https://itunes.apple.com/search?term="
                    + URLEncoder.encode(q, "UTF-8")
                    + "&media=music&limit=1";
            byte[] jsonBytes = downloadUrlBytes(api, 3500);
            if (jsonBytes == null) {
                return null;
            }

            String body = new String(jsonBytes, StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(body);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonArray results = parsed.getAsJsonObject().getAsJsonArray("results");
            if (results == null || results.size() == 0) {
                return null;
            }
            JsonObject first = results.get(0).getAsJsonObject();
            if (!first.has("artworkUrl100")) {
                return null;
            }
            String artUrl = first.get("artworkUrl100").getAsString().replace("100x100", "256x256");
            return downloadUrlBytes(artUrl, 3500);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String cleanQueryPart(String raw) {
        if (raw == null) return "";
        String cleaned = NOISE_TAGS.matcher(raw).replaceAll(" ");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private static byte[] downloadUrlBytes(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "AirLyrics/1.1.0");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 8 * 1024 * 1024) {
                        return null;
                    }
                    baos.write(buf, 0, n);
                }
                return baos.toByteArray();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readProcessText(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Perceptual cover gradient extraction tuned for legible, glowing lyric text in Minecraft.
     */
    static int[] extractGradient(BufferedImage img) {
        if (img == null || img.getWidth() < 2 || img.getHeight() < 2) {
            return null;
        }

        int w = img.getWidth();
        int h = img.getHeight();
        int minX = Math.max(0, Math.round(w * 0.06f));
        int maxX = Math.min(w - 1, Math.round(w * 0.94f));
        int minY = Math.max(0, Math.round(h * 0.06f));
        int maxY = Math.min(h - 1, Math.round(h * 0.94f));
        if (maxX <= minX || maxY <= minY) {
            minX = 0;
            maxX = w - 1;
            minY = 0;
            maxY = h - 1;
        }

        int spanX = maxX - minX + 1;
        int spanY = maxY - minY + 1;
        int stepX = Math.max(1, spanX / 48);
        int stepY = Math.max(1, spanY / 48);

        double[] bucketWeight = new double[HUE_BUCKETS];
        double[] sumR = new double[HUE_BUCKETS];
        double[] sumG = new double[HUE_BUCKETS];
        double[] sumB = new double[HUE_BUCKETS];

        float[] hsl = new float[3];
        double totalWeight = 0.0;
        double grayWeight = 0.0;
        double grayLumaSum = 0.0;

        for (int y = minY; y <= maxY; y += stepY) {
            for (int x = minX; x <= maxX; x += stepX) {
                int argb = img.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha < 128) continue;

                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                rgbToHsl(r, g, b, hsl);
                float hue = hsl[0];
                float sat = hsl[1];
                float lig = hsl[2];

                grayWeight += 1.0;
                grayLumaSum += lig;

                // Filter out near-black, near-white glare, and washed-out gray
                if (lig < 0.11f || (lig > 0.92f && sat < 0.18f) || sat < 0.10f) {
                    continue;
                }

                double weight = Math.pow(sat, 1.25) * Math.max(0.08, 1.0 - Math.abs(lig - 0.56) * 1.1);
                int bucket = ((int) (hue / 10.0f)) % HUE_BUCKETS;
                if (bucket < 0) bucket += HUE_BUCKETS;

                bucketWeight[bucket] += weight;
                sumR[bucket] += r * weight;
                sumG[bucket] += g * weight;
                sumB[bucket] += b * weight;
                totalWeight += weight;
            }
        }

        // Completely achromatic grayscale / black-and-white artwork -> crisp frosted sky-ice gradient
        if (totalWeight <= 0.01) {
            float meanL = grayWeight > 0 ? (float) (grayLumaSum / grayWeight) : 0.5f;
            if (meanL > 0.55f) {
                return new int[]{0x9EC5E8, 0xFFF2E2};
            }
            return new int[]{0xB2D8FF, 0xEAE6FF};
        }

        // Smooth circularly across neighboring hue bins to pick coherent peaks
        double[] smoothW = new double[HUE_BUCKETS];
        int bestA = 0;
        for (int i = 0; i < HUE_BUCKETS; i++) {
            int prev = (i - 1 + HUE_BUCKETS) % HUE_BUCKETS;
            int next = (i + 1) % HUE_BUCKETS;
            smoothW[i] = bucketWeight[prev] * 0.25 + bucketWeight[i] * 0.50 + bucketWeight[next] * 0.25;
            if (smoothW[i] > smoothW[bestA]) {
                bestA = i;
            }
        }

        int rawColorA = averageBucketCluster(bestA, bucketWeight, sumR, sumG, sumB);

        int bestB = -1;
        double bestScoreB = -1.0;
        double minThreshold = smoothW[bestA] * 0.10;
        for (int i = 0; i < HUE_BUCKETS; i++) {
            if (smoothW[i] < minThreshold) continue;
            float hueDiff = circularBucketDegDiff(bestA, i);
            if (hueDiff < 32.0f) continue;

            double score = smoothW[i] * (0.75 + 0.55 * Math.sin(Math.toRadians(hueDiff * 0.5)));
            if (score > bestScoreB) {
                bestScoreB = score;
                bestB = i;
            }
        }

        int colorA = normalizeChromaticStop(rawColorA);
        int colorB;
        if (bestB >= 0) {
            int rawColorB = averageBucketCluster(bestB, bucketWeight, sumR, sumG, sumB);
            colorB = normalizeChromaticStop(rawColorB);
        } else {
            // Monochromatic cover -> harmonious analog shift
            rgbToHsl((colorA >> 16) & 0xFF, (colorA >> 8) & 0xFF, colorA & 0xFF, hsl);
            float h2 = (hsl[0] + 38.0f) % 360.0f;
            float s2 = Math.max(0.56f, Math.min(0.92f, hsl[1]));
            float l2 = Math.max(0.60f, Math.min(0.84f, hsl[2] + 0.12f));
            colorB = hslToRgb(h2, s2, l2);
        }

        // Guarantee visible separation between the two gradient stops
        if (rgbDistance(colorA, colorB) < 42.0) {
            rgbToHsl((colorB >> 16) & 0xFF, (colorB >> 8) & 0xFF, colorB & 0xFF, hsl);
            float h2 = (hsl[0] + 34.0f) % 360.0f;
            float l2 = hsl[2] > 0.72f ? (hsl[2] - 0.12f) : (hsl[2] + 0.12f);
            colorB = hslToRgb(h2, Math.max(0.56f, Math.min(0.92f, hsl[1])), Math.max(0.58f, Math.min(0.84f, l2)));
        }

        return new int[]{colorA & 0x00FFFFFF, colorB & 0x00FFFFFF};
    }

    private static int averageBucketCluster(int center, double[] w, double[] sr, double[] sg, double[] sb) {
        double tw = 0.0, tr = 0.0, tg = 0.0, tb = 0.0;
        for (int d = -1; d <= 1; d++) {
            int idx = (center + d + HUE_BUCKETS) % HUE_BUCKETS;
            double weight = w[idx];
            tw += weight;
            tr += sr[idx];
            tg += sg[idx];
            tb += sb[idx];
        }
        if (tw <= 1e-6) {
            return hslToRgb(center * 10.0f + 5.0f, 0.75f, 0.70f);
        }
        int r = Math.max(0, Math.min(255, (int) Math.round(tr / tw)));
        int g = Math.max(0, Math.min(255, (int) Math.round(tg / tw)));
        int b = Math.max(0, Math.min(255, (int) Math.round(tb / tw)));
        return (r << 16) | (g << 8) | b;
    }

    private static float circularBucketDegDiff(int a, int b) {
        int d = Math.abs(a - b);
        int wrap = Math.min(d, HUE_BUCKETS - d);
        return wrap * (360.0f / HUE_BUCKETS);
    }

    private static int normalizeChromaticStop(int rgb) {
        float[] hsl = new float[3];
        rgbToHsl((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsl);
        float s = Math.max(0.56f, Math.min(0.92f, hsl[1]));
        float l = Math.max(0.60f, Math.min(0.82f, hsl[2]));
        return hslToRgb(hsl[0], s, l);
    }

    private static double rgbDistance(int rgbA, int rgbB) {
        int dr = ((rgbA >> 16) & 0xFF) - ((rgbB >> 16) & 0xFF);
        int dg = ((rgbA >> 8) & 0xFF) - ((rgbB >> 8) & 0xFF);
        int db = (rgbA & 0xFF) - (rgbB & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int lerpRgb(int rgbA, int rgbB, float t) {
        float clamped = Math.max(0.0f, Math.min(1.0f, t));
        int ar = (rgbA >> 16) & 0xFF, ag = (rgbA >> 8) & 0xFF, ab = rgbA & 0xFF;
        int br = (rgbB >> 16) & 0xFF, bg = (rgbB >> 8) & 0xFF, bb = rgbB & 0xFF;
        int r = Math.round(ar + (br - ar) * clamped);
        int g = Math.round(ag + (bg - ag) * clamped);
        int b = Math.round(ab + (bb - ab) * clamped);
        return (r << 16) | (g << 8) | b;
    }

    private static void rgbToHsl(int rInt, int gInt, int bInt, float[] out) {
        float r = rInt / 255.0f;
        float g = gInt / 255.0f;
        float b = bInt / 255.0f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float l = (max + min) * 0.5f;
        float h = 0.0f;
        float s = 0.0f;
        if (d > 1e-5f) {
            s = l > 0.5f ? d / (2.0f - max - min) : d / (max + min);
            if (max == r) {
                h = (g - b) / d + (g < b ? 6.0f : 0.0f);
            } else if (max == g) {
                h = (b - r) / d + 2.0f;
            } else {
                h = (r - g) / d + 4.0f;
            }
            h *= 60.0f;
        }
        out[0] = h;
        out[1] = s;
        out[2] = l;
    }

    private static int hslToRgb(float hDeg, float s, float l) {
        float h = ((hDeg % 360.0f) + 360.0f) % 360.0f / 360.0f;
        float r, g, b;
        if (s <= 1e-5f) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1.0f + s) : l + s - l * s;
            float p = 2.0f * l - q;
            r = hue2rgb(p, q, h + 1.0f / 3.0f);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1.0f / 3.0f);
        }
        int ri = Math.max(0, Math.min(255, Math.round(r * 255.0f)));
        int gi = Math.max(0, Math.min(255, Math.round(g * 255.0f)));
        int bi = Math.max(0, Math.min(255, Math.round(b * 255.0f)));
        return (ri << 16) | (gi << 8) | bi;
    }

    private static float hue2rgb(float p, float q, float tIn) {
        float t = tIn;
        if (t < 0.0f) t += 1.0f;
        if (t > 1.0f) t -= 1.0f;
        if (t < 1.0f / 6.0f) return p + (q - p) * 6.0f * t;
        if (t < 0.5f) return q;
        if (t < 2.0f / 3.0f) return p + (q - p) * (2.0f / 3.0f - t) * 6.0f;
        return p;
    }
}
