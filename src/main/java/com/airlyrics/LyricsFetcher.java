package com.airlyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/**
 * Multi-provider lyrics fetcher & timing analyzer inspired by EvergreenHUD.
 * Supports lrc.red (YAML word-sync), lrclib.net (duration-scored LRC / enhanced LRC),
 * KuGou (KRC decrypted word-sync), NetEase fallback, EvergreenHUD Beat syllable estimator,
 * and chorus/emphasis importance analysis.
 */
public class LyricsFetcher {
    public static class LyricWord {
        public long timeMs;
        public long endTimeMs;
        public String text;
        public boolean important;
        public float boost = 1.0f;
        public volatile boolean spawned = false;

        public LyricWord(long timeMs, String text) {
            this.timeMs = timeMs;
            this.endTimeMs = timeMs + 300L;
            this.text = text;
        }

        public LyricWord(long timeMs, long endTimeMs, String text) {
            this.timeMs = timeMs;
            this.endTimeMs = Math.max(timeMs + 40L, endTimeMs);
            this.text = text;
        }
    }

    public static class LyricLine {
        public long startTimeMs;
        public long endTimeMs;
        public String fullLine;
        public final List<LyricWord> words;
        public boolean hasWordTimings;
        public boolean estimatedWords;
        public boolean important;
        public float importanceBoost = 1.0f;

        public LyricLine(long startTimeMs, long endTimeMs, String fullLine, List<LyricWord> words) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.fullLine = fullLine;
            this.words = words != null ? words : new ArrayList<LyricWord>();
        }
    }

    public static volatile List<LyricLine> currentLyrics = Collections.emptyList();
    public static volatile String currentTrackKey = "";
    public static volatile String currentLyricsProvider = "";
    public static volatile String statusMessage = "Idle";
    public static volatile boolean isFetching = false;
    public static volatile boolean isSynced = false;
    public static volatile boolean hasWordSync = false;

    private static volatile String lastRequestedTitle = "";
    private static volatile String lastRequestedArtist = "";
    private static volatile long lastRequestedDurationMs = 0L;

    private static final ExecutorService FETCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AirLyrics-Fetcher");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger FETCH_GEN = new AtomicInteger(0);
    private static final JsonParser JSON_PARSER = new JsonParser();

    private static final byte[] KRC_KEY = {
            64, 71, 97, 119, 94, 50, 116, 71,
            81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105
    };

    private static final Pattern LINE_TAG_PATTERN = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
    private static final Pattern WORD_TAG_PATTERN = Pattern.compile("<(\\d+):(\\d+(?:\\.\\d+)?)>");
    private static final Pattern HAS_ENHANCED_WORD = Pattern.compile("<\\d+:\\d+(?:\\.\\d+)?>");
    private static final Pattern KRC_LINE_PATTERN = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern KRC_WORD_PATTERN = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)");
    private static final Pattern PARENS_PATTERN = Pattern.compile("\\s*[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]");
    private static final Pattern FEAT_PATTERN = Pattern.compile("(?i)\\s+(feat\\.|ft\\.|featuring|with)\\b.*$");
    private static final Pattern DASH_EDITION_PATTERN = Pattern.compile("(?i)\\s+-\\s*(?:remaster(?:ed)?|live|radio\\s*edit|single|album|deluxe|acoustic|instrumental|explicit|clean|bonus|version|mix|mono|stereo).*$");

    public static void onTrackChanged(String title, String artist) {
        onTrackChanged(title, artist, MediaTracker.currentDurationMs);
    }

    public static void onTrackChanged(final String title, final String artist, final long durationMs) {
        fetchAsync(title, artist, durationMs, false);
    }

    public static void fetchAsync(final String title, final String artist, final long durationMs, boolean forceRefresh) {
        final String safeTitle = title == null ? "" : title.trim();
        final String safeArtist = artist == null ? "" : artist.trim();
        if (safeTitle.isEmpty()) {
            clearLyrics();
            return;
        }

        String newTrackKey = safeArtist + " - " + safeTitle;
        if (!forceRefresh && newTrackKey.equals(currentTrackKey) && (!currentLyrics.isEmpty() || isFetching)) {
            return;
        }

        lastRequestedTitle = safeTitle;
        lastRequestedArtist = safeArtist;
        lastRequestedDurationMs = Math.max(0L, durationMs);
        currentTrackKey = newTrackKey;
        LyricsRenderer.clearActiveWords();

        final int gen = FETCH_GEN.incrementAndGet();
        isFetching = true;
        statusMessage = "Searching lyrics...";

        FETCH_EXECUTOR.submit(() -> runFetch(gen, safeTitle, safeArtist, Math.max(0L, durationMs)));
    }

    public static void refetchCurrentTrack() {
        String title = !lastRequestedTitle.isEmpty() ? lastRequestedTitle : MediaTracker.currentTitle;
        String artist = !lastRequestedArtist.isEmpty() ? lastRequestedArtist : MediaTracker.currentArtist;
        long dur = lastRequestedDurationMs > 0L ? lastRequestedDurationMs : MediaTracker.currentDurationMs;
        if (title != null && !title.trim().isEmpty()) {
            fetchAsync(title, artist, dur, true);
        }
    }

    public static void refetch() {
        refetchCurrentTrack();
    }

    public static void clearLyrics() {
        FETCH_GEN.incrementAndGet();
        LyricsRenderer.clearActiveWords();
        currentLyrics = Collections.emptyList();
        currentTrackKey = "";
        currentLyricsProvider = "";
        statusMessage = "No media";
        isFetching = false;
        isSynced = false;
        hasWordSync = false;
    }

    public static int getActiveLineIndex(long timeMs) {
        List<LyricLine> lines = currentLyrics;
        if (lines == null || lines.isEmpty()) {
            return -1;
        }
        int active = -1;
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            if (timeMs >= line.startTimeMs) {
                active = i;
            } else {
                break;
            }
        }
        return active;
    }

    private static void runFetch(int gen, String rawTitle, String rawArtist, long durationMs) {
        try {
            String cleanTitle = cleanTitle(rawTitle);
            String softTitle = cleanTitleSoft(rawTitle);
            String cleanArtist = cleanArtist(rawArtist);
            double durationSec = durationMs > 0L ? durationMs / 1000.0 : -1.0;

            FetchResult best = null;

            // 1. lrc.red (studio word-level YAML)
            FetchResult lrcRed = fetchFromLrcRed(cleanTitle, cleanArtist, durationSec);
            if (lrcRed != null && !lrcRed.lines.isEmpty()) {
                best = lrcRed;
            }

            // 2. lrclib.net (duration-scored LRC or enhanced LRC)
            if (best == null && gen == FETCH_GEN.get()) {
                FetchResult lrclib = fetchFromLrcLib(rawTitle, cleanTitle, softTitle, rawArtist, cleanArtist, durationSec);
                if (lrclib != null && !lrclib.lines.isEmpty()) {
                    best = lrclib;
                }
            }

            // 3. KuGou (KRC word-by-word encrypted format)
            if (best == null && gen == FETCH_GEN.get()) {
                FetchResult kugou = fetchFromKuGou(cleanTitle, cleanArtist, durationMs);
                if (kugou != null && !kugou.lines.isEmpty()) {
                    best = kugou;
                }
            }

            // 4. NetEase fallback
            if (best == null && gen == FETCH_GEN.get()) {
                FetchResult netease = fetchFromNetEase(cleanTitle, cleanArtist, durationMs);
                if (netease != null && !netease.lines.isEmpty()) {
                    best = netease;
                }
            }

            if (gen != FETCH_GEN.get()) {
                return;
            }

            if (best == null || best.lines.isEmpty()) {
                currentLyrics = Collections.emptyList();
                currentLyricsProvider = "";
                isSynced = false;
                hasWordSync = false;
                statusMessage = "Lyrics not found";
                isFetching = false;
                return;
            }

            List<LyricLine> processed = best.lines;
            if (best.synced) {
                processed = postProcessSyncedLines(processed);
                estimateMissingWordTimings(processed);
            }
            analyzeImportantLyrics(processed);

            boolean anyWordSync = false;
            for (LyricLine line : processed) {
                if (line.hasWordTimings) {
                    anyWordSync = true;
                    break;
                }
            }

            currentLyrics = Collections.unmodifiableList(processed);
            currentLyricsProvider = best.provider;
            isSynced = best.synced;
            hasWordSync = anyWordSync;
            statusMessage = best.provider + (best.synced ? (anyWordSync ? " (Word-Sync)" : " (Synced)") : " (Plain)");
            isFetching = false;
        } catch (Throwable t) {
            if (gen == FETCH_GEN.get()) {
                currentLyrics = Collections.emptyList();
                currentLyricsProvider = "";
                isSynced = false;
                hasWordSync = false;
                statusMessage = "Fetch error";
                isFetching = false;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Provider 1: lrc.red (.lyricsfile.yaml word-level sync)
    // -------------------------------------------------------------------------

    private static FetchResult fetchFromLrcRed(String cleanTitle, String cleanArtist, double durationSec) {
        try {
            String query = (cleanArtist + " " + cleanTitle).trim();
            if (query.isEmpty()) {
                return null;
            }
            String searchJson = httpGet("https://lrc.red/search.json?q=" + encode(query), 4500);
            if (searchJson == null) {
                return null;
            }
            JsonObject root = JSON_PARSER.parse(searchJson).getAsJsonObject();
            if (!root.has("hits") || !root.get("hits").isJsonArray()) {
                return null;
            }
            JsonArray hits = root.getAsJsonArray("hits");
            String normTarget = normalizeAlphaNum(cleanTitle);
            String normArtist = normalizeAlphaNum(cleanArtist);

            String bestIsrc = null;
            int bestScore = Integer.MIN_VALUE;

            for (JsonElement el : hits) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject hit = el.getAsJsonObject();
                String isrc = getString(hit, "isrc");
                if (!isrc.matches("[A-Za-z0-9]{6,20}")) {
                    continue;
                }
                String hitTitle = getString(hit, "title");
                String normHit = normalizeAlphaNum(hitTitle);
                if (normHit.isEmpty() || normTarget.isEmpty()) {
                    continue;
                }
                if (!normHit.contains(normTarget) && !normTarget.contains(normHit)) {
                    continue;
                }

                double hitDur = getDouble(hit, "length", getDouble(hit, "duration", -1.0));
                if (hitDur > 1000.0) {
                    hitDur /= 1000.0;
                }
                double durDiff = 0.0;
                if (durationSec > 0.0 && hitDur > 0.0) {
                    durDiff = Math.abs(durationSec - hitDur);
                    if (durDiff > 8.0) {
                        continue;
                    }
                }

                String hitArtist = normalizeAlphaNum(getString(hit, "artist"));
                int score = normHit.equals(normTarget) ? 200 : 100;
                if (!normArtist.isEmpty() && !hitArtist.isEmpty()
                        && (hitArtist.contains(normArtist) || normArtist.contains(hitArtist))) {
                    score += 300;
                }
                score -= (int) (durDiff * 20.0);

                if (score > bestScore) {
                    bestScore = score;
                    bestIsrc = isrc;
                }
            }

            if (bestIsrc == null) {
                return null;
            }

            String yaml = httpGet("https://lrc.red/s/" + bestIsrc + ".lyricsfile.yaml", 4500);
            if (yaml == null || yaml.isEmpty()) {
                return null;
            }
            List<LyricLine> lines = parseLrcRedYaml(yaml);
            return lines.isEmpty() ? null : new FetchResult("lrc.red", lines, true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<LyricLine> parseLrcRedYaml(String yaml) {
        List<LyricLine> lines = new ArrayList<LyricLine>();
        boolean inLines = false;
        boolean inWords = false;

        String lineText = null;
        long lineStart = -1L;
        long lineEnd = -1L;
        List<LyricWord> curWords = new ArrayList<LyricWord>();

        String wordText = null;
        long wordStart = -1L;
        long wordEnd = -1L;

        for (String raw : yaml.split("\\r?\\n")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') {
                indent++;
            }

            if (!inLines) {
                if (trimmed.equals("lines:")) {
                    inLines = true;
                }
                continue;
            }
            if (indent == 0 && !trimmed.startsWith("-")) {
                break;
            }

            if (inWords && indent <= 2) {
                flushYamlWord(curWords, wordText, wordStart, wordEnd);
                wordText = null;
                wordStart = -1L;
                wordEnd = -1L;
                inWords = false;
            }

            if (indent <= 2 && trimmed.startsWith("- ")) {
                flushYamlLine(lines, lineText, lineStart, lineEnd, curWords);
                lineText = null;
                lineStart = -1L;
                lineEnd = -1L;
                curWords = new ArrayList<LyricWord>();
                inWords = false;
                String afterDash = trimmed.substring(2).trim();
                if (afterDash.startsWith("text:")) {
                    lineText = unquoteYaml(afterDash.substring(5).trim());
                } else if (afterDash.startsWith("start_ms:")) {
                    lineStart = parseLongSafe(afterDash.substring(9).trim(), -1L);
                } else if (afterDash.startsWith("end_ms:")) {
                    lineEnd = parseLongSafe(afterDash.substring(7).trim(), -1L);
                }
                continue;
            }

            if (trimmed.equals("words:")) {
                inWords = true;
                continue;
            }

            if (inWords) {
                if (trimmed.startsWith("- ")) {
                    flushYamlWord(curWords, wordText, wordStart, wordEnd);
                    wordText = null;
                    wordStart = -1L;
                    wordEnd = -1L;
                    trimmed = trimmed.substring(2).trim();
                }
                if (trimmed.startsWith("text:")) {
                    wordText = unquoteYaml(trimmed.substring(5).trim());
                } else if (trimmed.startsWith("start_ms:")) {
                    wordStart = parseLongSafe(trimmed.substring(9).trim(), -1L);
                } else if (trimmed.startsWith("end_ms:")) {
                    wordEnd = parseLongSafe(trimmed.substring(7).trim(), -1L);
                }
            } else {
                if (trimmed.startsWith("text:")) {
                    lineText = unquoteYaml(trimmed.substring(5).trim());
                } else if (trimmed.startsWith("start_ms:")) {
                    lineStart = parseLongSafe(trimmed.substring(9).trim(), -1L);
                } else if (trimmed.startsWith("end_ms:")) {
                    lineEnd = parseLongSafe(trimmed.substring(7).trim(), -1L);
                }
            }
        }

        if (inWords) {
            flushYamlWord(curWords, wordText, wordStart, wordEnd);
        }
        flushYamlLine(lines, lineText, lineStart, lineEnd, curWords);
        return lines;
    }

    private static void flushYamlWord(List<LyricWord> words, String text, long start, long end) {
        if (text != null && !text.trim().isEmpty() && start >= 0L) {
            long effectiveEnd = end > start ? end : start + 250L;
            words.add(new LyricWord(start, effectiveEnd, text.trim()));
        }
    }

    private static void flushYamlLine(List<LyricLine> lines, String text, long start, long end, List<LyricWord> words) {
        String clean = text != null ? text.trim() : "";
        if (clean.isEmpty() && !words.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (LyricWord w : words) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(w.text);
            }
            clean = sb.toString().trim();
        }
        if (!clean.isEmpty() && start >= 0L) {
            long effectiveEnd = end > start ? end : start + 3000L;
            LyricLine line = new LyricLine(start, effectiveEnd, clean, words);
            line.hasWordTimings = words != null && !words.isEmpty();
            lines.add(line);
        }
    }

    private static String unquoteYaml(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
            s = s.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
        }
        return s;
    }

    // -------------------------------------------------------------------------
    // Provider 2: lrclib.net (duration-scored + enhanced word-tag support)
    // -------------------------------------------------------------------------

    private static FetchResult fetchFromLrcLib(String rawTitle, String cleanTitle, String softTitle,
                                               String rawArtist, String cleanArtist, double durationSec) {
        Set<String> urls = new LinkedHashSet<String>();
        if (!cleanTitle.isEmpty() && !cleanArtist.isEmpty()) {
            urls.add("https://lrclib.net/api/search?track_name=" + encode(cleanTitle) + "&artist_name=" + encode(cleanArtist));
        }
        if (!softTitle.isEmpty() && !rawArtist.isEmpty()) {
            urls.add("https://lrclib.net/api/search?track_name=" + encode(softTitle) + "&artist_name=" + encode(rawArtist));
        }
        if (!cleanArtist.isEmpty() && !cleanTitle.isEmpty()) {
            urls.add("https://lrclib.net/api/search?q=" + encode(cleanArtist + " " + cleanTitle));
        }
        if (!cleanTitle.isEmpty()) {
            urls.add("https://lrclib.net/api/search?q=" + encode(cleanTitle));
        }
        if (!rawTitle.isEmpty() && !rawTitle.equals(cleanTitle)) {
            urls.add("https://lrclib.net/api/search?q=" + encode((rawArtist + " " + rawTitle).trim()));
        }

        String normTargetTitle = normalizeAlphaNum(cleanTitle.isEmpty() ? rawTitle : cleanTitle);
        String normTargetArtist = normalizeAlphaNum(cleanArtist);

        String bestSynced = null;
        int bestSyncedScore = Integer.MIN_VALUE;
        String bestPlain = null;
        int bestPlainScore = Integer.MIN_VALUE;

        for (String url : urls) {
            try {
                String body = httpGet(url, 4500);
                if (body == null || body.trim().isEmpty()) {
                    continue;
                }
                JsonElement parsed = JSON_PARSER.parse(body);
                if (!parsed.isJsonArray()) {
                    continue;
                }
                for (JsonElement el : parsed.getAsJsonArray()) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("instrumental") && !obj.get("instrumental").isJsonNull() && obj.get("instrumental").getAsBoolean()) {
                        continue;
                    }

                    double candDur = getDouble(obj, "duration", -1.0);
                    double durDiff = 0.0;
                    if (durationSec > 0.0 && candDur > 0.0) {
                        durDiff = Math.abs(durationSec - candDur);
                        if (durDiff > 8.0) {
                            continue;
                        }
                    }

                    String candTrack = normalizeAlphaNum(cleanTitle(getString(obj, "trackName")));
                    String candArtist = normalizeAlphaNum(cleanArtist(getString(obj, "artistName")));

                    int baseScore = 0;
                    if (!normTargetArtist.isEmpty() && !candArtist.isEmpty()
                            && (candArtist.contains(normTargetArtist) || normTargetArtist.contains(candArtist))) {
                        baseScore += 300;
                    }
                    if (!normTargetTitle.isEmpty() && !candTrack.isEmpty()) {
                        if (candTrack.equals(normTargetTitle)) {
                            baseScore += 100;
                        } else if (candTrack.contains(normTargetTitle) || normTargetTitle.contains(candTrack)) {
                            baseScore += 40;
                        }
                    }
                    baseScore -= (int) (durDiff * 20.0);

                    String syncedLyrics = getString(obj, "syncedLyrics");
                    if (!syncedLyrics.isEmpty()) {
                        int score = baseScore;
                        if (HAS_ENHANCED_WORD.matcher(syncedLyrics).find()) {
                            score += 2000;
                        }
                        if (score > bestSyncedScore) {
                            bestSyncedScore = score;
                            bestSynced = syncedLyrics;
                        }
                    } else {
                        String plainLyrics = getString(obj, "plainLyrics");
                        if (!plainLyrics.isEmpty() && baseScore > bestPlainScore) {
                            bestPlainScore = baseScore;
                            bestPlain = plainLyrics;
                        }
                    }
                }

                if (bestSyncedScore >= 2300) {
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        if (bestSynced != null) {
            List<LyricLine> lines = parseLrcText(bestSynced);
            if (!lines.isEmpty()) {
                return new FetchResult("lrclib.net", lines, true);
            }
        }
        if (bestPlain != null) {
            List<LyricLine> plain = parsePlainLyrics(bestPlain);
            if (!plain.isEmpty()) {
                return new FetchResult("lrclib.net (Plain)", plain, false);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Provider 3: KuGou (KRC decrypted word-by-word format)
    // -------------------------------------------------------------------------

    private static FetchResult fetchFromKuGou(String cleanTitle, String cleanArtist, long durationMs) {
        try {
            String keyword = (cleanArtist + " " + cleanTitle).trim();
            if (keyword.isEmpty()) {
                return null;
            }
            String url = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + encode(keyword);
            if (durationMs > 0L) {
                url += "&duration=" + durationMs;
            }
            String body = httpGet(url, 4500);
            if (body == null) {
                return null;
            }
            JsonObject root = JSON_PARSER.parse(body).getAsJsonObject();
            if (!root.has("candidates") || !root.get("candidates").isJsonArray()) {
                return null;
            }
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates.size() == 0) {
                return null;
            }

            JsonObject chosen = null;
            long bestDiff = Long.MAX_VALUE;
            for (JsonElement el : candidates) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject cand = el.getAsJsonObject();
                String id = getString(cand, "id");
                String key = getString(cand, "accesskey");
                if (id.isEmpty() || key.isEmpty()) {
                    continue;
                }
                long candDur = cand.has("duration") && !cand.get("duration").isJsonNull() ? cand.get("duration").getAsLong() : 0L;
                long diff = 0L;
                if (durationMs > 0L && candDur > 0L) {
                    diff = Math.abs(durationMs - candDur);
                    if (diff > 8000L) {
                        continue;
                    }
                }
                if (diff < bestDiff) {
                    bestDiff = diff;
                    chosen = cand;
                }
            }

            if (chosen == null) {
                return null;
            }

            String dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id="
                    + encode(getString(chosen, "id"))
                    + "&accesskey=" + encode(getString(chosen, "accesskey"))
                    + "&fmt=krc&charset=utf8";
            String dlJson = httpGet(dlUrl, 4500);
            if (dlJson == null) {
                return null;
            }
            JsonObject dlObj = JSON_PARSER.parse(dlJson).getAsJsonObject();
            String b64 = getString(dlObj, "content");
            if (b64.isEmpty()) {
                return null;
            }
            String krc = decodeKrc(b64);
            if (krc == null || krc.isEmpty()) {
                return null;
            }
            List<LyricLine> lines = parseKrc(krc);
            return lines.isEmpty() ? null : new FetchResult("KuGou", lines, true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String decodeKrc(String base64) throws Exception {
        byte[] raw = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
        if (raw.length <= 4 || raw[0] != 'k' || raw[1] != 'r' || raw[2] != 'c' || raw[3] != '1') {
            return null;
        }
        byte[] zipped = new byte[raw.length - 4];
        for (int i = 4; i < raw.length; i++) {
            zipped[i - 4] = (byte) (raw[i] ^ KRC_KEY[(i - 4) & 15]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(zipped))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = inf.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<LyricLine> parseKrc(String krc) {
        List<LyricLine> lines = new ArrayList<LyricLine>();
        for (String raw : krc.split("\\r?\\n")) {
            String trimmed = raw.trim();
            Matcher m = KRC_LINE_PATTERN.matcher(trimmed);
            if (!m.matches()) {
                continue;
            }
            long startMs = parseLongSafe(m.group(1), -1L);
            long durMs = parseLongSafe(m.group(2), 0L);
            String body = m.group(3);
            if (startMs < 0L || body.isEmpty()) {
                continue;
            }

            List<LyricWord> words = new ArrayList<LyricWord>();
            StringBuilder lineText = new StringBuilder();
            Matcher wm = KRC_WORD_PATTERN.matcher(body);
            while (wm.find()) {
                long offset = parseLongSafe(wm.group(1), 0L);
                long wDur = parseLongSafe(wm.group(2), 200L);
                String wText = wm.group(3);
                if (wText == null) {
                    continue;
                }
                lineText.append(wText);
                String trimmedWord = wText.trim();
                if (!trimmedWord.isEmpty()) {
                    long ws = startMs + offset;
                    words.add(new LyricWord(ws, ws + Math.max(40L, wDur), trimmedWord));
                }
            }
            String full = lineText.toString().trim();
            if (!full.isEmpty()) {
                long endMs = durMs > 0L ? startMs + durMs : startMs + 3000L;
                LyricLine line = new LyricLine(startMs, endMs, full, words);
                line.hasWordTimings = !words.isEmpty();
                lines.add(line);
            }
        }
        return lines;
    }

    // -------------------------------------------------------------------------
    // Provider 4: NetEase fallback
    // -------------------------------------------------------------------------

    private static FetchResult fetchFromNetEase(String cleanTitle, String cleanArtist, long durationMs) {
        try {
            String query = (cleanTitle + " " + cleanArtist).trim();
            if (query.isEmpty()) {
                return null;
            }
            String searchJson = httpGet("https://music.163.com/api/search/get?type=1&limit=5&s=" + encode(query), 4500);
            if (searchJson == null) {
                return null;
            }
            JsonObject root = JSON_PARSER.parse(searchJson).getAsJsonObject();
            if (!root.has("result") || !root.get("result").isJsonObject()) {
                return null;
            }
            JsonObject result = root.getAsJsonObject("result");
            if (!result.has("songs") || !result.get("songs").isJsonArray()) {
                return null;
            }
            JsonArray songs = result.getAsJsonArray("songs");
            long bestSongId = -1L;
            long bestDiff = Long.MAX_VALUE;
            for (JsonElement el : songs) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject song = el.getAsJsonObject();
                long id = song.has("id") ? song.get("id").getAsLong() : -1L;
                long dur = song.has("duration") ? song.get("duration").getAsLong() : 0L;
                if (id <= 0L) {
                    continue;
                }
                long diff = 0L;
                if (durationMs > 0L && dur > 0L) {
                    diff = Math.abs(durationMs - dur);
                    if (diff > 8000L) {
                        continue;
                    }
                }
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestSongId = id;
                }
            }
            if (bestSongId <= 0L) {
                return null;
            }

            String lrcJson = httpGet("https://music.163.com/api/song/lyric?os=pc&id=" + bestSongId + "&lv=-1&kv=-1&tv=-1", 4500);
            if (lrcJson == null) {
                return null;
            }
            JsonObject lrcObj = JSON_PARSER.parse(lrcJson).getAsJsonObject();
            if (lrcObj.has("lrc") && lrcObj.get("lrc").isJsonObject()) {
                String lrcText = getString(lrcObj.getAsJsonObject("lrc"), "lyric");
                if (!lrcText.isEmpty()) {
                    List<LyricLine> lines = parseLrcText(lrcText);
                    if (!lines.isEmpty()) {
                        return new FetchResult("NetEase", lines, true);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // LRC Parsing + EvergreenHUD Beat Word Estimator + Importance Scoring
    // -------------------------------------------------------------------------

    private static List<LyricLine> parseLrcText(String rawLrc) {
        List<LyricLine> lines = new ArrayList<LyricLine>();
        for (String lineStr : rawLrc.split("\\r?\\n")) {
            String trimmed = lineStr.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher lineMatcher = LINE_TAG_PATTERN.matcher(trimmed);
            List<Long> lineStarts = new ArrayList<Long>();
            int contentStart = 0;
            while (lineMatcher.find()) {
                if (lineMatcher.start() > contentStart && trimmed.substring(contentStart, lineMatcher.start()).trim().length() > 0) {
                    break;
                }
                long ms = parseLrcTimestamp(lineMatcher.group(1), lineMatcher.group(2));
                if (ms >= 0L) {
                    lineStarts.add(ms);
                }
                contentStart = lineMatcher.end();
            }
            if (lineStarts.isEmpty()) {
                continue;
            }

            String content = trimmed.substring(contentStart).trim();
            if (content.isEmpty()) {
                continue;
            }

            boolean hasWordTags = HAS_ENHANCED_WORD.matcher(content).find();
            for (long lineStartMs : lineStarts) {
                if (hasWordTags) {
                    ParsedEnhancedLine parsed = parseEnhancedContent(content, lineStartMs);
                    if (!parsed.fullLine.isEmpty()) {
                        LyricLine line = new LyricLine(lineStartMs, lineStartMs + 3000L, parsed.fullLine, parsed.words);
                        line.hasWordTimings = !parsed.words.isEmpty();
                        lines.add(line);
                    }
                } else {
                    lines.add(new LyricLine(lineStartMs, lineStartMs + 3000L, content, new ArrayList<LyricWord>()));
                }
            }
        }

        Collections.sort(lines, new Comparator<LyricLine>() {
            @Override
            public int compare(LyricLine a, LyricLine b) {
                return Long.compare(a.startTimeMs, b.startTimeMs);
            }
        });
        return lines;
    }

    private static ParsedEnhancedLine parseEnhancedContent(String content, long defaultStartMs) {
        List<LyricWord> words = new ArrayList<LyricWord>();
        StringBuilder full = new StringBuilder();
        Matcher wm = WORD_TAG_PATTERN.matcher(content);
        List<Long> tagTimes = new ArrayList<Long>();
        List<String> tagSegments = new ArrayList<String>();

        int lastEnd = 0;
        long pendingTime = defaultStartMs;
        while (wm.find()) {
            String between = content.substring(lastEnd, wm.start());
            if (!between.trim().isEmpty()) {
                tagTimes.add(pendingTime);
                tagSegments.add(between);
            }
            long ts = parseLrcTimestamp(wm.group(1), wm.group(2));
            if (ts >= 0L) {
                pendingTime = ts;
            }
            lastEnd = wm.end();
        }
        String tail = content.substring(lastEnd);
        if (!tail.trim().isEmpty()) {
            tagTimes.add(pendingTime);
            tagSegments.add(tail);
        }

        for (int i = 0; i < tagSegments.size(); i++) {
            long start = tagTimes.get(i);
            long end = (i + 1 < tagTimes.size()) ? Math.max(start + 60L, tagTimes.get(i + 1)) : start + 350L;
            String rawSeg = tagSegments.get(i);
            full.append(rawSeg);
            String cleanSeg = rawSeg.trim();
            if (!cleanSeg.isEmpty()) {
                words.add(new LyricWord(start, end, cleanSeg));
            }
        }
        return new ParsedEnhancedLine(full.toString().replaceAll("\\s+", " ").trim(), words);
    }

    private static List<LyricLine> parsePlainLyrics(String plainText) {
        List<LyricLine> lines = new ArrayList<LyricLine>();
        long t = 0L;
        for (String line : plainText.split("\\r?\\n")) {
            String clean = line.trim();
            if (clean.isEmpty()) {
                continue;
            }
            lines.add(new LyricLine(t, t + 3200L, clean, new ArrayList<LyricWord>()));
            t += 3200L;
        }
        return lines;
    }

    private static List<LyricLine> postProcessSyncedLines(List<LyricLine> input) {
        Collections.sort(input, new Comparator<LyricLine>() {
            @Override
            public int compare(LyricLine a, LyricLine b) {
                return Long.compare(a.startTimeMs, b.startTimeMs);
            }
        });

        List<LyricLine> out = new ArrayList<LyricLine>(input.size());
        for (int i = 0; i < input.size(); i++) {
            LyricLine cur = input.get(i);
            long start = Math.max(0L, cur.startTimeMs);
            long end = cur.endTimeMs;
            if (i + 1 < input.size()) {
                long nextStart = input.get(i + 1).startTimeMs;
                if (nextStart > start) {
                    end = Math.min(Math.max(end, start + 500L), nextStart);
                    if (end <= start) {
                        end = nextStart;
                    }
                    if (!cur.hasWordTimings) {
                        end = Math.min(nextStart, start + 8000L);
                    }
                }
            } else if (end <= start) {
                end = start + 4500L;
            }
            cur.startTimeMs = start;
            cur.endTimeMs = Math.max(start + 200L, end);
            out.add(cur);
        }
        return out;
    }

    /**
     * EvergreenHUD "Beat" syllable + punctuation word-timing estimator:
     * computes song-wide 30th-percentile ms-per-syllable (clamped 110..650ms, default 260ms)
     * and distributes each line's sung duration proportionally across tokens.
     */
    private static void estimateMissingWordTimings(List<LyricLine> lines) {
        if (!LyricsConfig.estimateWordTimings) {
            for (LyricLine line : lines) {
                if (!line.hasWordTimings) {
                    populateUniformWords(line);
                }
            }
            return;
        }

        List<Double> rates = new ArrayList<Double>();
        for (LyricLine line : lines) {
            if (line.hasWordTimings) {
                continue;
            }
            long dur = line.endTimeMs - line.startTimeMs;
            if (dur <= 200L || dur >= 12000L) {
                continue;
            }
            int syllables = countLineSyllables(line.fullLine);
            if (syllables > 0) {
                rates.add((double) dur / syllables);
            }
        }

        double songPace = 260.0;
        if (!rates.isEmpty()) {
            Collections.sort(rates);
            int idx = Math.max(0, Math.min(rates.size() - 1, (int) (rates.size() * 0.30)));
            songPace = Math.max(110.0, Math.min(650.0, rates.get(idx)));
        }

        for (LyricLine line : lines) {
            if (line.hasWordTimings) {
                continue;
            }
            List<String> tokens = tokenizeWords(line.fullLine);
            if (tokens.isEmpty()) {
                continue;
            }
            long lineDur = Math.max(200L, line.endTimeMs - line.startTimeMs);
            double[] weights = new double[tokens.size()];
            double expected = 0.0;
            for (int i = 0; i < tokens.size(); i++) {
                String tok = tokens.get(i);
                double w = countWordSyllables(tok) * songPace + punctuationPauseMs(tok);
                weights[i] = Math.max(80.0, w);
                expected += weights[i];
            }

            double sung = Math.min(lineDur * 0.92, expected + Math.max(0.0, lineDur - expected) * 0.35);
            if (sung < 120.0) {
                sung = lineDur * 0.92;
            }

            double cursor = line.startTimeMs;
            line.words.clear();
            for (int i = 0; i < tokens.size(); i++) {
                double frac = expected > 0.0 ? weights[i] / expected : 1.0 / tokens.size();
                double slice = sung * frac;
                long ws = Math.round(cursor);
                long we = Math.round(cursor + slice);
                line.words.add(new LyricWord(ws, Math.max(ws + 40L, we), tokens.get(i)));
                cursor += slice;
            }
            line.estimatedWords = true;
        }
    }

    private static void populateUniformWords(LyricLine line) {
        List<String> tokens = tokenizeWords(line.fullLine);
        if (tokens.isEmpty()) {
            return;
        }
        line.words.clear();
        long dur = Math.max(200L, line.endTimeMs - line.startTimeMs);
        double step = (double) dur / tokens.size();
        for (int i = 0; i < tokens.size(); i++) {
            long ws = line.startTimeMs + Math.round(i * step);
            long we = line.startTimeMs + Math.round((i + 1) * step);
            line.words.add(new LyricWord(ws, Math.max(ws + 40L, we), tokens.get(i)));
        }
    }

    /**
     * Scores chorus/hook repetition and vocal emphasis so important lyrics can scale larger.
     */
    private static void analyzeImportantLyrics(List<LyricLine> lines) {
        Map<String, Integer> freq = new HashMap<String, Integer>();
        for (LyricLine line : lines) {
            String key = normalizeAlphaNum(line.fullLine);
            if (key.length() >= 6) {
                Integer prev = freq.get(key);
                freq.put(key, prev == null ? 1 : prev + 1);
            }
        }

        for (LyricLine line : lines) {
            String trimmed = line.fullLine.trim();
            String key = normalizeAlphaNum(trimmed);
            int count = freq.containsKey(key) ? freq.get(key) : 1;

            float boost = 1.0f;
            if (count >= 3) {
                boost = 1.28f;
            } else if (count == 2) {
                boost = 1.20f;
            }

            if (trimmed.endsWith("!") || trimmed.endsWith("?!") || trimmed.endsWith("!?")) {
                boost = Math.max(boost, 1.24f);
            }

            int uppercaseWords = 0;
            for (LyricWord w : line.words) {
                if (isEmphaticWord(w.text)) {
                    w.important = true;
                    w.boost = w.text.endsWith("!") ? 1.25f : 1.16f;
                    uppercaseWords++;
                }
            }
            if (uppercaseWords >= 2) {
                boost = Math.max(boost, 1.22f);
            }

            line.important = boost > 1.05f;
            line.importanceBoost = Math.min(1.32f, boost);
        }
    }

    private static boolean isEmphaticWord(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (token.endsWith("!")) {
            return true;
        }
        int letters = 0;
        int uppers = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    uppers++;
                }
            }
        }
        return letters >= 3 && uppers == letters;
    }

    private static List<String> tokenizeWords(String line) {
        List<String> tokens = new ArrayList<String>();
        if (line == null || line.trim().isEmpty()) {
            return tokens;
        }
        for (String chunk : line.trim().split("\\s+")) {
            if (chunk.isEmpty()) {
                continue;
            }
            if (chunk.length() >= 4 && containsCjk(chunk)) {
                StringBuilder buf = new StringBuilder();
                for (int i = 0; i < chunk.length(); i++) {
                    char c = chunk.charAt(i);
                    if (isCjk(c)) {
                        if (buf.length() > 0) {
                            tokens.add(buf.toString());
                            buf.setLength(0);
                        }
                        tokens.add(String.valueOf(c));
                    } else {
                        buf.append(c);
                    }
                }
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                }
            } else {
                tokens.add(chunk);
            }
        }
        return tokens;
    }

    private static int countLineSyllables(String line) {
        int total = 0;
        for (String tok : tokenizeWords(line)) {
            total += countWordSyllables(tok);
        }
        return Math.max(1, total);
    }

    private static int countWordSyllables(String word) {
        if (word == null || word.isEmpty()) {
            return 1;
        }
        if (containsCjk(word)) {
            int count = 0;
            for (int i = 0; i < word.length(); i++) {
                if (isCjk(word.charAt(i))) {
                    count++;
                }
            }
            return Math.max(1, count);
        }
        String lower = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (lower.isEmpty()) {
            return 1;
        }
        int syllables = 0;
        boolean prevVowel = false;
        for (int i = 0; i < lower.length(); i++) {
            boolean vowel = "aeiouy".indexOf(lower.charAt(i)) >= 0;
            if (vowel && !prevVowel) {
                syllables++;
            }
            prevVowel = vowel;
        }
        if (lower.endsWith("e") && syllables > 1) {
            syllables--;
        }
        return Math.max(1, syllables);
    }

    private static double punctuationPauseMs(String word) {
        if (word.endsWith(",") || word.endsWith(";")) {
            return 120.0;
        }
        if (word.endsWith(".") || word.endsWith("!") || word.endsWith("?")) {
            return 220.0;
        }
        if (word.endsWith("-") || word.endsWith("—")) {
            return 90.0;
        }
        return 0.0;
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isCjk(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long parseLrcTimestamp(String minStr, String secStr) {
        try {
            long min = Long.parseLong(minStr);
            double sec = Double.parseDouble(secStr);
            return Math.max(0L, Math.round(min * 60000.0 + sec * 1000.0));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String s = PARENS_PATTERN.matcher(title).replaceAll("");
        s = FEAT_PATTERN.matcher(s).replaceAll("");
        s = DASH_EDITION_PATTERN.matcher(s).replaceAll("");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String cleanTitleSoft(String title) {
        if (title == null) {
            return "";
        }
        return FEAT_PATTERN.matcher(title).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private static String cleanArtist(String artist) {
        if (artist == null) {
            return "";
        }
        String s = artist.replaceAll("(?i)\\s*-\\s*Topic$", "");
        s = FEAT_PATTERN.matcher(s).replaceAll("");
        int amp = s.indexOf(" & ");
        if (amp > 0) {
            s = s.substring(0, amp);
        }
        int comma = s.indexOf(", ");
        if (comma > 0) {
            s = s.substring(0, comma);
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeAlphaNum(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String norm = Normalizer.normalize(input, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(norm.length());
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static String httpGet(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "AirLyrics/1.1.0 (Minecraft 1.8.9)");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception ignored) {
            return s == null ? "" : s.replace(" ", "%20");
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString().trim();
    }

    private static double getDouble(JsonObject obj, String key, double fallback) {
        try {
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsDouble();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static long parseLongSafe(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class FetchResult {
        final String provider;
        final List<LyricLine> lines;
        final boolean synced;

        FetchResult(String provider, List<LyricLine> lines, boolean synced) {
            this.provider = provider;
            this.lines = lines;
            this.synced = synced;
        }
    }

    private static final class ParsedEnhancedLine {
        final String fullLine;
        final List<LyricWord> words;

        ParsedEnhancedLine(String fullLine, List<LyricWord> words) {
            this.fullLine = fullLine;
            this.words = words;
        }
    }
}
