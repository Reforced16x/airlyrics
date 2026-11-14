package com.airlyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricsFetcher {
    public static class LyricWord {
        public final long timeMs;
        public final String text;
        public boolean spawned = false;

        public LyricWord(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public static class LyricLine {
        public final long startTimeMs;
        public final long endTimeMs;
        public final String fullLine;
        public final List<LyricWord> words;

        public LyricLine(long startTimeMs, long endTimeMs, String fullLine, List<LyricWord> words) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.fullLine = fullLine;
            this.words = words;
        }
    }

    public static volatile List<LyricLine> currentLyrics = Collections.emptyList();

    private static final Pattern LRC_LINE_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)");
    private static final AtomicInteger FETCH_SEQ = new AtomicInteger();

    public static void fetchAsync(String title, String artist) {
        if (title == null || title.isEmpty()) return;
        final int seq = FETCH_SEQ.incrementAndGet();

        new Thread(() -> {
            List<LyricLine> result = Collections.emptyList();
            try {
                String lrc = fetchFromLrclib(title, artist);
                if (lrc == null || lrc.isEmpty()) {
                    lrc = fetchFromNetEase(title, artist);
                }
                if (lrc != null && !lrc.isEmpty()) {
                    result = parseLrc(lrc);
                }
            } catch (Exception ignored) {
            }

            if (FETCH_SEQ.get() == seq) {
                currentLyrics = result;
            }
        }, "AirLyrics-Fetch").start();
    }

    private static String fetchFromLrclib(String title, String artist) {
        try {
            String q = URLEncoder.encode(title + " " + artist, "UTF-8");
            String json = httpGet("https://lrclib.net/api/search?q=" + q);
            JsonArray arr = new JsonParser().parse(json).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("syncedLyrics") && !obj.get("syncedLyrics").isJsonNull()) {
                    String synced = obj.get("syncedLyrics").getAsString();
                    if (!synced.trim().isEmpty()) return synced;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String fetchFromNetEase(String title, String artist) {
        try {
            String q = URLEncoder.encode(title + " " + artist, "UTF-8");
            String searchJson = httpGet("https://music.163.com/api/search/get?s=" + q + "&type=1&limit=1");
            JsonObject root = new JsonParser().parse(searchJson).getAsJsonObject();
            JsonArray songs = root.getAsJsonObject("result").getAsJsonArray("songs");
            if (songs.size() > 0) {
                long songId = songs.get(0).getAsJsonObject().get("id").getAsLong();
                String lyricJson = httpGet("https://music.163.com/api/song/lyric?os=pc&id=" + songId + "&lv=-1");
                JsonObject lrcObj = new JsonParser().parse(lyricJson).getAsJsonObject();
                if (lrcObj.has("lrc") && lrcObj.getAsJsonObject("lrc").has("lyric")) {
                    return lrcObj.getAsJsonObject("lrc").get("lyric").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "AirLyrics/1.0.0 (Minecraft 1.8.9)");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    private static List<LyricLine> parseLrc(String rawLrc) {
        List<RawEntry> rawEntries = new ArrayList<>();
        for (String line : rawLrc.split("\\r?\\n")) {
            Matcher m = LRC_LINE_PATTERN.matcher(line.trim());
            if (!m.matches()) continue;

            long min = Long.parseLong(m.group(1));
            long sec = Long.parseLong(m.group(2));
            String msStr = m.group(3);
            long ms = Long.parseLong(msStr) * (msStr.length() == 2 ? 10 : 1);
            long totalMs = min * 60000 + sec * 1000 + ms;

            String text = m.group(4).replaceAll("<[^>]+>", "").trim();
            if (!text.isEmpty()) {
                rawEntries.add(new RawEntry(totalMs, text));
            }
        }

        List<LyricLine> parsedLines = new ArrayList<>();
        for (int i = 0; i < rawEntries.size(); i++) {
            RawEntry curr = rawEntries.get(i);
            long nextStart = (i + 1 < rawEntries.size()) ? rawEntries.get(i + 1).timeMs : curr.timeMs + 4000;
            long duration = Math.max(800, Math.min(4500, nextStart - curr.timeMs - 150));

            String[] splitWords = curr.text.split("\\s+");
            List<LyricWord> wordList = new ArrayList<>();
            long step = duration / Math.max(1, splitWords.length);
            for (int w = 0; w < splitWords.length; w++) {
                wordList.add(new LyricWord(curr.timeMs + (w * step), splitWords[w]));
            }
            parsedLines.add(new LyricLine(curr.timeMs, nextStart, curr.text, wordList));
        }
        return parsedLines;
    }

    private static class RawEntry {
        final long timeMs;
        final String text;

        RawEntry(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }
}
