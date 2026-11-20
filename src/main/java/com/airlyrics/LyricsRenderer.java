package com.airlyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Coordinates sub-frame lyric clock smoothing, floating in-world words/lines
 * (with optional World Apple Lyrics sweep effect), sky-dome line-by-line marquee,
 * and both Apple Lyrics Style and classic HUD overlays.
 */
public final class LyricsRenderer {
    private static final double SMOOTHING_MS = 250.0;
    private static final double SNAP_DISTANCE_MS = 1200.0;
    private static final float APPLE_CARD_WIDTH = 248.0f;
    private static final float APPLE_CARD_PAD_X = 10.0f;
    private static final float APPLE_CARD_PAD_Y = 9.0f;
    private static final float SPRING_OMEGA = 11.0f;
    private static final float SPRING_DAMPING = 0.9f;
    private static final float SCROLL_STAGGER_MS = 45.0f;

    private static final int[] PASTEL_PALETTE = {
        0x7AF8FF, 0xFF8BE6, 0x9BFFB3, 0xFFD97D, 0x99B4FF, 0xFF9E9E
    };

    private static final Random RANDOM = new Random();
    private static final List<FloatingWord> ACTIVE_WORLD_WORDS = new ArrayList<FloatingWord>();

    private static boolean clockInitialized = false;
    private static double smoothPosMs = 0.0;
    private static long lastFrameSysMs = 0L;
    private static String lastTrackKey = "";
    private static List<LyricsFetcher.LyricLine> lastLyricsRef = null;

    private static int lastSpawnLineIdx = -1;
    private static int lastSpawnWordIdx = -1;

    private static int lastHudLineIdx = -1;
    private static long hudScrollStartMs = 0L;
    private static float hudScrollFrom = 0.0f;
    private static float hudScrollCurrent = 0.0f;
    private static float pauseAlpha = 1.0f;

    private static int lastSkyLineIdx = -1;
    private static long skyScrollStartMs = 0L;
    private static float skyScrollFrom = 0.0f;
    private static float skyScrollCurrent = 0.0f;
    private static float skyYawDeg = 0.0f;
    private static boolean skyYawInitialized = false;

    private LyricsRenderer() {
    }

    public static void clearActiveWords() {
        ACTIVE_WORLD_WORDS.clear();
        lastSpawnLineIdx = -1;
        lastSpawnWordIdx = -1;
        lastHudLineIdx = -1;
        lastSkyLineIdx = -1;
    }

    /**
     * Client tick handler (50ms) handles pause fade state and cleanup when disabled or out of world.
     */
    public static void tick(Minecraft client) {
        if (client == null || client.world == null || client.player == null || !LyricsConfig.enabled) {
            clearActiveWords();
            clockInitialized = false;
            skyYawInitialized = false;
            return;
        }

        if (client.isPaused()) {
            pauseAlpha = approach(pauseAlpha, 0.0f, 0.25f);
        } else {
            float targetAlpha = MediaTracker.isPlaying ? 1.0f : 0.35f;
            pauseAlpha = approach(pauseAlpha, targetAlpha, 0.2f);
        }

        long nowSys = System.currentTimeMillis();
        pruneExpiredWords(nowSys);
    }

    /**
     * Main render hook invoked on GameGui tail (screen-space overlays and shader-safe 3D projections).
     */
    public static void renderHudLyrics(float tickDelta) {
        if (ShaderCompat.isShadowPass()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.world == null || client.player == null) {
            return;
        }

        boolean editorOpen = OneConfigCompat.isHudEditorOpen();
        if (!LyricsConfig.enabled && !editorOpen) {
            return;
        }

        long nowSys = System.currentTimeMillis();
        float frameDtMs = computeFrameDtMs(nowSys);

        updateClock(client, nowSys, frameDtMs);

        Window window = new Window(client);
        CameraView cam = computeCamera(client, window, tickDelta);

        if (LyricsConfig.enabled && !client.isPaused()) {
            updateWorldSpawns(client, cam, nowSys);
        }

        ShaderCompat.SavedRenderState savedState = ShaderCompat.beginLuminousTextPass();
        try {
            if (LyricsConfig.enabled && LyricsConfig.inWorld && !client.isPaused()) {
                renderWorldWords(client, cam, nowSys);
            }

            if (LyricsConfig.enabled && LyricsConfig.skyLyrics && !client.isPaused()) {
                renderSkyLyrics(client, cam, nowSys, frameDtMs);
            }

            if (LyricsConfig.onScreen || editorOpen) {
                renderHudOverlay(client, window, editorOpen, nowSys);
            }
        } finally {
            ShaderCompat.endLuminousTextPass(savedState);
        }
    }

    private static float computeFrameDtMs(long nowSys) {
        if (lastFrameSysMs == 0L) {
            lastFrameSysMs = nowSys;
            return 16.0f;
        }
        long dt = nowSys - lastFrameSysMs;
        lastFrameSysMs = nowSys;
        if (dt <= 0L) return 1.0f;
        if (dt > 100L) return 100.0f;
        return (float) dt;
    }

    private static void updateClock(Minecraft client, long nowSys, float frameDtMs) {
        if (!MediaTracker.hasMedia) {
            clockInitialized = false;
            lastLyricsRef = null;
            return;
        }

        String currKey = (MediaTracker.currentArtist != null ? MediaTracker.currentArtist : "")
                + "\0" + (MediaTracker.currentTitle != null ? MediaTracker.currentTitle : "");
        boolean trackChanged = !currKey.equals(lastTrackKey);
        List<LyricsFetcher.LyricLine> lines = LyricsFetcher.currentLyrics;
        boolean lyricsReloaded = lines != lastLyricsRef;

        double rawPosMs = MediaTracker.getEstimatedPositionMs() + LyricsConfig.syncOffsetMs;
        if (!clockInitialized || trackChanged || lyricsReloaded) {
            lastTrackKey = currKey;
            lastLyricsRef = lines;
            smoothPosMs = rawPosMs;
            clockInitialized = true;
            clearActiveWords();
            return;
        }

        if (client.isPaused()) {
            double err = rawPosMs - smoothPosMs;
            if (Math.abs(err) > SNAP_DISTANCE_MS) {
                smoothPosMs = rawPosMs;
                clearActiveWords();
            }
            return;
        }

        if (MediaTracker.isPlaying) {
            smoothPosMs += frameDtMs;
        }

        // Bidirectional smooth resync: catches up gently if lagging OR ahead without stalling
        double err = rawPosMs - smoothPosMs;
        if (Math.abs(err) > SNAP_DISTANCE_MS) {
            boolean jumpedBack = err < -SNAP_DISTANCE_MS;
            smoothPosMs = rawPosMs;
            if (jumpedBack) {
                clearActiveWords();
            } else {
                resyncSpawnCursor();
            }
        } else {
            double alpha = 1.0 - Math.exp(-frameDtMs / SMOOTHING_MS);
            smoothPosMs += err * alpha;
        }
    }

    private static void resyncSpawnCursor() {
        long nowPos = Math.round(smoothPosMs);
        int idx = LyricsFetcher.getActiveLineIndex(nowPos);
        if (idx >= 0) {
            lastSpawnLineIdx = idx - 1;
            lastSpawnWordIdx = -1;
        }
    }

    private static void updateWorldSpawns(Minecraft client, CameraView cam, long nowSys) {
        if (!LyricsConfig.inWorld || !MediaTracker.hasMedia || !MediaTracker.isPlaying) {
            return;
        }
        List<LyricsFetcher.LyricLine> lines = LyricsFetcher.currentLyrics;
        if (lines == null || lines.isEmpty()) {
            return;
        }

        long nowPos = Math.round(smoothPosMs);
        long preRollMs = (LyricsConfig.worldAppleStyle && LyricsConfig.showWords) ? 220L : 0L;
        int activeIdx = LyricsFetcher.getActiveLineIndex(nowPos + preRollMs);
        if (activeIdx < 0 || activeIdx >= lines.size()) {
            return;
        }

        // Flush any remaining word on the previous line before advancing cursor at pre-roll boundary
        if (lastSpawnLineIdx >= 0 && lastSpawnLineIdx == activeIdx - 1 && LyricsConfig.showWords) {
            LyricsFetcher.LyricLine prevLine = lines.get(lastSpawnLineIdx);
            if (prevLine.words != null) {
                for (int i = Math.max(0, lastSpawnWordIdx + 1); i < prevLine.words.size(); i++) {
                    LyricsFetcher.LyricWord w = prevLine.words.get(i);
                    if (nowPos + preRollMs >= w.timeMs) {
                        String wordText = formatCase(w.text);
                        if (!wordText.isEmpty()) {
                            long wSpan = Math.max(400L, w.endTimeMs - w.timeMs);
                            long dur = Math.max(1550L, Math.min(2800L, wSpan + 1250L));
                            spawnInView(client, cam, wordText, dur, nowSys,
                                    lastSpawnLineIdx * 0.37f + i * 0.19f, i, prevLine.words.size(),
                                    w.timeMs, w.endTimeMs, null);
                        }
                    }
                }
            }
        }

        if (lastSpawnLineIdx != activeIdx) {
            lastSpawnLineIdx = activeIdx;
            lastSpawnWordIdx = -1;
        }

        LyricsFetcher.LyricLine line = lines.get(activeIdx);
        if (!LyricsConfig.showWords || line.words == null || line.words.isEmpty()) {
            if (lastSpawnWordIdx < 0 && nowPos >= line.startTimeMs) {
                lastSpawnWordIdx = 0;
                long dur = Math.max(1800L, Math.min(4800L, (line.endTimeMs - line.startTimeMs) + 800L));
                List<LyricsFetcher.LyricWord> subs = (line.words != null && !line.words.isEmpty()) ? line.words : null;
                spawnInView(client, cam, formatCase(line.fullLine), dur, nowSys, activeIdx * 0.31f,
                        0, 1, line.startTimeMs, line.endTimeMs, subs);
            }
            return;
        }

        for (int i = Math.max(0, lastSpawnWordIdx + 1); i < line.words.size(); i++) {
            LyricsFetcher.LyricWord w = line.words.get(i);
            if (nowPos < w.timeMs - preRollMs) {
                break;
            }
            lastSpawnWordIdx = i;
            String wordText = formatCase(w.text);
            if (wordText.isEmpty()) {
                continue;
            }
            long wSpan = Math.max(400L, w.endTimeMs - w.timeMs);
            long dur = Math.max(1550L, Math.min(2800L, wSpan + 1250L));
            float phaseSeed = activeIdx * 0.37f + i * 0.19f;
            spawnInView(client, cam, wordText, dur, nowSys, phaseSeed, i, line.words.size(),
                    w.timeMs, w.endTimeMs, null);
        }
    }

    private static void spawnInView(Minecraft client, CameraView cam, String text, long durationMs,
                                    long nowSys, float phaseSeed, int wordIndex, int totalWords,
                                    long wordStartMs, long wordEndMs, List<LyricsFetcher.LyricWord> subWords) {
        if (text == null || text.isEmpty() || client.player == null || cam == null) {
            return;
        }

        boolean firstPerson = client.options.perspective == 0;
        double baseDist = firstPerson ? 4.2 : 6.8;
        double spread = baseDist / 4.2;

        double bestWx = 0.0, bestWy = 0.0, bestWz = 0.0;
        if (LyricsConfig.scatterLayout) {
            double bestScore = -1.0;
            for (int attempt = 0; attempt < 8; attempt++) {
                double distance = baseDist + RANDOM.nextDouble() * 1.5;
                double offsetH = (RANDOM.nextDouble() - 0.5) * 3.1 * spread;
                double offsetV = (RANDOM.nextDouble() - 0.46) * 1.54 * spread;

                double cx = cam.x + cam.fX * distance + cam.rX * offsetH + cam.uX * offsetV;
                double cy = cam.y + cam.fY * distance + cam.uY * offsetV;
                double cz = cam.z + cam.fZ * distance + cam.rZ * offsetH + cam.uZ * offsetV;

                double minSq = ACTIVE_WORLD_WORDS.isEmpty() ? 100.0 : Double.MAX_VALUE;
                for (int j = 0; j < ACTIVE_WORLD_WORDS.size(); j++) {
                    FloatingWord existing = ACTIVE_WORLD_WORDS.get(j);
                    double ex = cx - existing.worldX;
                    double ey = cy - existing.worldY;
                    double ez = cz - existing.worldZ;
                    double sq = ex * ex + ey * ey + ez * ez;
                    if (sq < minSq) {
                        minSq = sq;
                    }
                }
                if (minSq > bestScore) {
                    bestScore = minSq;
                    bestWx = cx;
                    bestWy = cy;
                    bestWz = cz;
                }
            }
        } else {
            double distance = baseDist;
            double centeredIdx = wordIndex - (Math.max(1, totalWords) - 1) * 0.5;
            double offsetH = centeredIdx * 0.65 * spread;
            double offsetV = 0.15 * spread;
            bestWx = cam.x + cam.fX * distance + cam.rX * offsetH + cam.uX * offsetV;
            bestWy = cam.y + cam.fY * distance + cam.uY * offsetV;
            bestWz = cam.z + cam.fZ * distance + cam.rZ * offsetH + cam.uZ * offsetV;
        }

        int color = PASTEL_PALETTE[(Math.abs(wordIndex) + RANDOM.nextInt(PASTEL_PALETTE.length)) % PASTEL_PALETTE.length];

        FloatingWord fw = new FloatingWord();
        fw.text = text;
        fw.worldX = bestWx;
        fw.worldY = bestWy;
        fw.worldZ = bestWz;
        fw.spawnMs = nowSys;
        fw.durationMs = durationMs;
        fw.colorRgb = color;
        fw.phaseSeed = phaseSeed;
        fw.wordStartMs = wordStartMs;
        fw.wordEndMs = wordEndMs;
        fw.subWords = subWords;

        ACTIVE_WORLD_WORDS.add(fw);
        while (ACTIVE_WORLD_WORDS.size() > 28) {
            ACTIVE_WORLD_WORDS.remove(0);
        }
    }

    private static void pruneExpiredWords(long nowSys) {
        Iterator<FloatingWord> it = ACTIVE_WORLD_WORDS.iterator();
        while (it.hasNext()) {
            FloatingWord fw = it.next();
            if (nowSys - fw.spawnMs >= fw.durationMs) {
                it.remove();
            }
        }
    }

    private static void renderWorldWords(Minecraft client, CameraView cam, long nowSys) {
        if (ACTIVE_WORLD_WORDS.isEmpty()) {
            return;
        }

        float opacity = LyricsConfig.getOpacityAlpha() * pauseAlpha;
        if (opacity <= 0.03f) {
            return;
        }

        long nowPos = Math.round(smoothPosMs);
        int fixedPrimary = OneConfigCompat.resolvePrimaryColorRgb(LyricsConfig.primaryColor);
        float glowSetting = LyricsConfig.getGlowFactor();
        float userScale = Math.max(0.5f, Math.min(2.5f, LyricsConfig.scalePercent / 100.0f));
        boolean appleWorld = LyricsConfig.worldAppleStyle;

        Iterator<FloatingWord> it = ACTIVE_WORLD_WORDS.iterator();
        while (it.hasNext()) {
            FloatingWord fw = it.next();
            float age = (float) (nowSys - fw.spawnMs);
            if (age >= fw.durationMs) {
                it.remove();
                continue;
            }
            float progress = Math.max(0.0f, Math.min(1.0f, age / (float) fw.durationMs));

            float fadeIn = smoothstep(0.0f, 0.14f, progress);
            float fadeOut = 1.0f - smoothstep(0.70f, 1.0f, progress);
            float alpha = opacity * fadeIn * fadeOut;
            if (alpha <= 0.03f) {
                continue;
            }

            // World-space gentle upward drift (independent of camera pitch/movement)
            double wx = fw.worldX;
            double wy = fw.worldY + progress * 0.24;
            double wz = fw.worldZ;

            ProjectedPoint pt = projectToScreen(cam, wx, wy, wz);
            if (pt == null) {
                continue;
            }

            float scalePop = appleWorld
                    ? (0.86f + 0.14f * evaluateSpring(age))
                    : (0.92f + 0.10f * fadeIn - 0.04f * smoothstep(0.74f, 1.0f, progress));
            float worldHeightBlocks = 0.34f * userScale * scalePop;
            float baseRenderScale = Math.max(0.45f, Math.min(4.2f,
                    (float) ((worldHeightBlocks * pt.pixelsPerBlock) / FontManager.LOGICAL_HEIGHT)));

            int rgb = LyricsConfig.rainbowPastel ? fw.colorRgb : fixedPrimary;

            GlStateManager.pushMatrix();
            GlStateManager.translated(pt.screenX, pt.screenY, 0.0);
            GlStateManager.scalef(baseRenderScale, baseRenderScale, 1.0f);

            if (appleWorld && fw.subWords != null && !fw.subWords.isEmpty()) {
                renderWorldAppleLine(client, fw, nowPos, rgb, alpha, glowSetting);
            } else if (appleWorld) {
                if (nowPos < fw.wordStartMs) {
                    float width = FontManager.getStringWidth(client, fw.text);
                    TextEffectRenderer.drawStyledText(client, fw.text, -width * 0.5f, -4.5f, width,
                            0xA8B9CC, alpha * 0.58f, true, 0.0f, fw.phaseSeed, false);
                } else if (nowPos <= fw.wordEndMs) {
                    float t = (float) (nowPos - fw.wordStartMs) / Math.max(45.0f, (float) (fw.wordEndMs - fw.wordStartMs));
                    t = Math.max(0.0f, Math.min(1.0f, t));
                    float wScale = evalWordScale(t);
                    float wLift = evalWordLift(t) * 1.15f;
                    float glowAmount = evalWordGlow(t) * glowSetting * 1.15f * fadeOut;
                    float width = FontManager.getStringWidth(client, fw.text);
                    GlStateManager.translatef(0.0f, -wLift, 0.0f);
                    GlStateManager.scalef(wScale, wScale, 1.0f);
                    TextEffectRenderer.drawWipedStyledText(client, fw.text, -width * 0.5f, -4.5f, width,
                            0xA8B9CC, alpha * 0.58f, rgb, alpha, t, true, glowAmount, fw.phaseSeed);
                } else {
                    float afterMs = (float) (nowPos - fw.wordEndMs);
                    float tailFade = 1.0f - Math.min(1.0f, afterMs / 360.0f);
                    float tailGlow = glowSetting * fadeOut * (0.45f + 0.55f * tailFade);
                    float width = FontManager.getStringWidth(client, fw.text);
                    TextEffectRenderer.drawStyledText(client, fw.text, -width * 0.5f, -4.5f, width,
                            rgb, alpha, true, tailGlow, fw.phaseSeed, true);
                }
            } else {
                float glowAmount = glowSetting * fadeIn * fadeOut * (0.50f + 0.65f * (float) Math.exp(-progress * 1.9));
                float width = FontManager.getStringWidth(client, fw.text);
                TextEffectRenderer.drawStyledText(client, fw.text, -width * 0.5f, -4.5f, width,
                        rgb, alpha, true, glowAmount, fw.phaseSeed, true);
            }

            GlStateManager.popMatrix();
        }
    }

    private static void renderWorldAppleLine(Minecraft client, FloatingWord fw, long nowPos,
                                             int highlightRgb, float lineAlpha, float glowSetting) {
        float spaceW = FontManager.getCharWidth(client, ' ');
        float totalW = 0.0f;
        for (int i = 0; i < fw.subWords.size(); i++) {
            String wt = formatCase(fw.subWords.get(i).text);
            if (wt.isEmpty()) continue;
            if (totalW > 0.0f) totalW += spaceW;
            totalW += FontManager.getStringWidth(client, wt);
        }
        float cursorX = -totalW * 0.5f;
        for (int i = 0; i < fw.subWords.size(); i++) {
            LyricsFetcher.LyricWord word = fw.subWords.get(i);
            String wt = formatCase(word.text);
            if (wt.isEmpty()) continue;
            float ww = FontManager.getStringWidth(client, wt);
            float seed = fw.phaseSeed + i * 0.18f;

            if (nowPos < word.timeMs) {
                TextEffectRenderer.drawStyledText(client, wt, cursorX, -4.5f, ww,
                        0xA8B9CC, lineAlpha * 0.56f, true, 0.0f, seed, false);
            } else if (nowPos <= word.endTimeMs) {
                float t = (float) (nowPos - word.timeMs) / Math.max(45.0f, (float) (word.endTimeMs - word.timeMs));
                t = Math.max(0.0f, Math.min(1.0f, t));
                float wScale = evalWordScale(t);
                float wLift = evalWordLift(t) * 1.15f;
                float wGlow = evalWordGlow(t) * glowSetting * 1.15f;

                GlStateManager.pushMatrix();
                GlStateManager.translatef(cursorX + ww * 0.5f, -wLift, 0.0f);
                GlStateManager.scalef(wScale, wScale, 1.0f);
                TextEffectRenderer.drawWipedStyledText(client, wt, -ww * 0.5f, -4.5f, ww,
                        0xA8B9CC, lineAlpha * 0.58f, 0xFFFFFF, lineAlpha, t, true, wGlow, seed);
                GlStateManager.popMatrix();
            } else {
                float afterMs = (float) (nowPos - word.endTimeMs);
                float tailGlow = glowSetting * 0.55f * (1.0f - Math.min(1.0f, afterMs / 320.0f));
                TextEffectRenderer.drawStyledText(client, wt, cursorX, -4.5f, ww,
                        highlightRgb, lineAlpha, true, tailGlow, seed, true);
            }
            cursorX += ww + spaceW;
        }
    }

    private static void renderSkyLyrics(Minecraft client, CameraView cam, long nowSys, float frameDtMs) {
        if (!MediaTracker.hasMedia) {
            return;
        }
        List<LyricsFetcher.LyricLine> lines = LyricsFetcher.currentLyrics;
        if (lines == null || lines.isEmpty()) {
            return;
        }

        long nowPos = Math.round(smoothPosMs);
        int activeIdx = LyricsFetcher.getActiveLineIndex(nowPos);
        if (activeIdx < 0) {
            activeIdx = 0;
        }

        if (activeIdx != lastSkyLineIdx) {
            skyScrollFrom = (lastSkyLineIdx >= 0) ? (skyScrollCurrent + (activeIdx - lastSkyLineIdx)) : 0.0f;
            skyScrollStartMs = nowSys;
            lastSkyLineIdx = activeIdx;
        }
        float spring = evaluateSpring((float) (nowSys - skyScrollStartMs));
        skyScrollCurrent = skyScrollFrom * (1.0f - spring);

        if (!skyYawInitialized) {
            skyYawDeg = cam.yawDeg;
            skyYawInitialized = true;
        } else {
            float diff = wrapDegrees(cam.yawDeg - skyYawDeg);
            double k = 1.0 - Math.exp(-frameDtMs / 480.0);
            skyYawDeg += diff * (float) k;
            float rem = wrapDegrees(cam.yawDeg - skyYawDeg);
            if (Math.abs(rem) > 42.0f) {
                skyYawDeg = cam.yawDeg - Math.copySign(42.0f, rem);
            }
        }

        float opacity = LyricsConfig.getOpacityAlpha() * pauseAlpha;
        if (opacity <= 0.03f) {
            return;
        }

        double skyYawRad = Math.toRadians(skyYawDeg);
        double elRad = Math.toRadians(LyricsConfig.skyElevationDeg);
        double cosEl = Math.cos(elRad);
        double sinEl = Math.sin(elRad);

        // Sky dome direction at distance 28 blocks
        double dirX = -Math.sin(skyYawRad) * cosEl;
        double dirY = sinEl;
        double dirZ = Math.cos(skyYawRad) * cosEl;

        double upX = Math.sin(skyYawRad) * sinEl;
        double upY = cosEl;
        double upZ = -Math.cos(skyYawRad) * sinEl;

        double skyDist = 28.0;
        double anchorX = cam.x + dirX * skyDist;
        double anchorY = cam.y + dirY * skyDist;
        double anchorZ = cam.z + dirZ * skyDist;

        float skyScale = Math.max(0.6f, Math.min(3.0f, LyricsConfig.skyScalePercent / 100.0f));
        float lineSpacingBlocks = 2.65f * skyScale;
        int primaryRgb = OneConfigCompat.resolvePrimaryColorRgb(LyricsConfig.primaryColor);
        float glowSetting = LyricsConfig.getGlowFactor();

        for (int delta = -1; delta <= 1; delta++) {
            int idx = activeIdx + delta;
            if (idx < 0 || idx >= lines.size()) {
                continue;
            }
            LyricsFetcher.LyricLine line = lines.get(idx);
            String text = formatCase(line.fullLine);
            if (text.isEmpty()) {
                continue;
            }

            // Positive delta -> upcoming -> placed lower along -up axis
            double offsetUp = -(delta - skyScrollCurrent) * lineSpacingBlocks;
            double lx = anchorX + upX * offsetUp;
            double ly = anchorY + upY * offsetUp;
            double lz = anchorZ + upZ * offsetUp;

            ProjectedPoint pt = projectToScreen(cam, lx, ly, lz);
            if (pt == null) {
                continue;
            }

            boolean isCurrent = (delta == 0);
            float distFromCenter = Math.abs(delta - skyScrollCurrent);
            float roleWeight = Math.max(0.0f, 1.0f - Math.min(1.0f, distFromCenter));

            float lineBlocksH = (isCurrent ? 1.85f : 1.15f) * skyScale;
            float renderScale = Math.max(0.6f, Math.min(8.0f,
                    (float) ((lineBlocksH * pt.pixelsPerBlock) / FontManager.LOGICAL_HEIGHT)));
            float textW = FontManager.getStringWidth(client, text);
            float phaseSeed = idx * 0.27f;

            GlStateManager.pushMatrix();
            GlStateManager.translated(pt.screenX, pt.screenY, 0.0);
            GlStateManager.scalef(renderScale, renderScale, 1.0f);

            if (isCurrent || roleWeight > 0.45f) {
                float prog = computeLineProgress(line, nowPos);
                float lineAlpha = opacity * (0.48f + 0.52f * roleWeight);
                float glow = glowSetting * (0.45f + 0.65f * roleWeight);
                TextEffectRenderer.drawWipedStyledText(client, text, -textW * 0.5f, -4.5f, textW,
                        0x9EB3C8, lineAlpha * 0.62f, primaryRgb, lineAlpha, prog, true, glow, phaseSeed);
            } else {
                float sideAlpha = opacity * (delta < 0 ? 0.34f : 0.52f);
                int sideRgb = delta < 0 ? 0x8BA0B4 : 0xC6D8EA;
                TextEffectRenderer.drawStyledText(client, text, -textW * 0.5f, -4.5f, textW,
                        sideRgb, sideAlpha, true, glowSetting * 0.25f, phaseSeed, false);
            }

            GlStateManager.popMatrix();
        }
    }

    private static void renderHudOverlay(Minecraft client, Window window, boolean editorOpen, long nowSys) {
        long nowPos = Math.round(smoothPosMs);
        List<LyricsFetcher.LyricLine> lines = LyricsFetcher.currentLyrics;
        List<LyricsFetcher.LyricLine> renderSource = lines;
        int activeIdx = 0;

        if (renderSource != null && !renderSource.isEmpty() && MediaTracker.hasMedia) {
            activeIdx = LyricsFetcher.getActiveLineIndex(nowPos);
            if (activeIdx < 0) {
                activeIdx = 0;
            }
        } else if (editorOpen) {
            renderSource = createPreviewLines();
            long cycle = System.currentTimeMillis() % 4800L;
            nowPos = cycle;
            activeIdx = cycle < 2600L ? 1 : 2;
        } else {
            return;
        }

        if (activeIdx != lastHudLineIdx) {
            hudScrollFrom = (lastHudLineIdx >= 0) ? (hudScrollCurrent + (activeIdx - lastHudLineIdx)) : 0.0f;
            hudScrollStartMs = nowSys;
            lastHudLineIdx = activeIdx;
        }
        float spring = evaluateSpring((float) (nowSys - hudScrollStartMs));
        hudScrollCurrent = hudScrollFrom * (1.0f - spring);

        float globalAlpha = LyricsConfig.getOpacityAlpha() * (editorOpen ? 1.0f : pauseAlpha);
        if (toRenderAlpha(globalAlpha) <= 4) {
            return;
        }

        if (LyricsConfig.appleStyle) {
            renderAppleHud(client, window, renderSource, activeIdx, nowPos, nowSys, globalAlpha);
        } else {
            renderClassicHud(client, window, renderSource, activeIdx, nowPos, globalAlpha);
        }
    }

    private static void renderAppleHud(Minecraft client, Window window, List<LyricsFetcher.LyricLine> lines,
                                       int activeIdx, long nowPos, long nowSys, float globalAlpha) {
        float userScale = Math.max(0.5f, Math.min(2.5f, LyricsConfig.scalePercent / 100.0f));
        float maxTextW = APPLE_CARD_WIDTH - APPLE_CARD_PAD_X * 2.0f;

        // Build wrapped line entries for window [activeIdx - 1 .. activeIdx + 2]
        List<AppleSlot> slots = new ArrayList<AppleSlot>(4);
        for (int delta = -1; delta <= 2; delta++) {
            int idx = activeIdx + delta;
            if (idx < 0 || idx >= lines.size()) {
                continue;
            }
            LyricsFetcher.LyricLine line = lines.get(idx);
            String full = formatCase(line.fullLine);
            if (full.isEmpty()) {
                continue;
            }
            AppleSlot slot = new AppleSlot();
            slot.delta = delta;
            slot.lineIdx = idx;
            slot.line = line;
            slot.wrapped = wrapLine(client, line, full, maxTextW);
            slots.add(slot);
        }

        if (slots.isEmpty()) {
            return;
        }

        float rowStep = 12.0f;
        float slotGap = 4.0f;
        float totalH = APPLE_CARD_PAD_Y * 2.0f;
        for (int i = 0; i < slots.size(); i++) {
            AppleSlot s = slots.get(i);
            s.height = Math.max(1, s.wrapped.size()) * rowStep;
            totalH += s.height + (i + 1 < slots.size() ? slotGap : 0.0f);
        }
        totalH = Math.max(54.0f, totalH);

        float defaultX = 16.0f;
        float defaultY = Math.max(16.0f, window.getHeight() - totalH * userScale - 18.0f);
        OneConfigCompat.notifyHudBounds(defaultX, defaultY, APPLE_CARD_WIDTH, totalH);

        float baseX = LyricsConfig.hudX >= 0.0f ? LyricsConfig.hudX : defaultX;
        float baseY = LyricsConfig.hudY >= 0.0f ? LyricsConfig.hudY : defaultY;

        int primaryRgb = OneConfigCompat.resolvePrimaryColorRgb(LyricsConfig.primaryColor);
        int highlightRgb = LyricsConfig.rainbowPastel ? lerpRgb(primaryRgb, 0x70D6FF, 0.30f) : primaryRgb;
        float glowFactor = LyricsConfig.getGlowFactor();

        GlStateManager.pushMatrix();
        GlStateManager.translated(baseX, baseY, 0.0);
        GlStateManager.scalef(userScale, userScale, 1.0f);

        // Translucent frosted card backdrop
        int bgAlpha = Math.round(Math.max(10.0f, Math.min(185.0f, 138.0f * globalAlpha)));
        int cardBg = (bgAlpha << 24) | 0x0D131F;
        TextEffectRenderer.drawRoundedBackdrop(0.0f, 0.0f, APPLE_CARD_WIDTH, totalH, 7.0f, cardBg);

        // Soft top accent border strip
        int accentRgb = TextEffectRenderer.resolveColorRgb(highlightRgb, 0.5f, activeIdx * 0.25f);
        int accentAlpha = Math.round(70.0f * globalAlpha);
        TextEffectRenderer.drawRoundedBackdrop(APPLE_CARD_PAD_X, 0.0f, APPLE_CARD_WIDTH - APPLE_CARD_PAD_X * 2.0f, 1.25f,
                0.5f, (accentAlpha << 24) | accentRgb);

        float cursorY = APPLE_CARD_PAD_Y;
        float elapsedScroll = (float) (nowSys - hudScrollStartMs);

        for (int sIdx = 0; sIdx < slots.size(); sIdx++) {
            AppleSlot slot = slots.get(sIdx);
            float staggeredSpring = evaluateSpring(Math.max(0.0f, elapsedScroll - (slot.delta + 1) * SCROLL_STAGGER_MS));
            float scrollOffset = hudScrollFrom * (1.0f - staggeredSpring) * 11.5f;
            float drawY = cursorY + scrollOffset;

            if (slot.delta == 0) {
                renderAppleActiveSlot(client, slot, APPLE_CARD_PAD_X, drawY, rowStep,
                        nowPos, highlightRgb, globalAlpha, glowFactor);
            } else {
                float roleAlpha;
                int roleRgb;
                if (slot.delta < 0) {
                    roleAlpha = globalAlpha * 0.36f;
                    roleRgb = 0x8EA3B8;
                } else if (slot.delta == 1) {
                    roleAlpha = globalAlpha * 0.60f;
                    roleRgb = 0xBDCEE0;
                } else {
                    roleAlpha = globalAlpha * 0.34f;
                    roleRgb = 0x8FA4BA;
                }

                for (int r = 0; r < slot.wrapped.size(); r++) {
                    WrappedRow row = slot.wrapped.get(r);
                    String txt = row.plainText;
                    float w = FontManager.getStringWidth(client, txt);
                    TextEffectRenderer.drawStyledText(client, txt, APPLE_CARD_PAD_X, drawY + r * rowStep, w,
                            roleRgb, roleAlpha, true, glowFactor * 0.15f, slot.lineIdx * 0.29f + r * 0.17f, false);
                }
            }

            cursorY += slot.height + slotGap;
        }

        GlStateManager.popMatrix();
    }

    private static void renderAppleActiveSlot(Minecraft client, AppleSlot slot, float startX, float startY,
                                              float rowStep, long nowPos, int highlightRgb,
                                              float globalAlpha, float glowFactor) {
        LyricsFetcher.LyricLine line = slot.line;
        boolean hasWords = line.words != null && !line.words.isEmpty();

        if (!hasWords) {
            float totalProg = computeLineProgress(line, nowPos);
            int totalChars = 0;
            for (WrappedRow r : slot.wrapped) {
                totalChars += r.plainText.length();
            }
            int passedChars = 0;
            float denom = Math.max(1.0f, (float) totalChars);
            for (int rIdx = 0; rIdx < slot.wrapped.size(); rIdx++) {
                WrappedRow row = slot.wrapped.get(rIdx);
                int len = row.plainText.length();
                float rowStart = passedChars / denom;
                float rowEnd = (passedChars + len) / denom;
                passedChars += len;

                float rowProg = rowEnd > rowStart ? (totalProg - rowStart) / (rowEnd - rowStart) : 1.0f;
                float w = FontManager.getStringWidth(client, row.plainText);
                TextEffectRenderer.drawWipedStyledText(client, row.plainText, startX, startY + rIdx * rowStep, w,
                        0x9EB0C4, globalAlpha * 0.56f, highlightRgb, globalAlpha, rowProg,
                        true, glowFactor * 0.90f, slot.lineIdx * 0.31f + rIdx * 0.15f);
            }
            return;
        }

        float spaceW = FontManager.getCharWidth(client, ' ');
        for (int rIdx = 0; rIdx < slot.wrapped.size(); rIdx++) {
            WrappedRow row = slot.wrapped.get(rIdx);
            float cursorX = startX;
            float rowY = startY + rIdx * rowStep;

            for (int wIdx = 0; wIdx < row.words.size(); wIdx++) {
                LyricsFetcher.LyricWord word = row.words.get(wIdx);
                String wText = formatCase(word.text);
                if (wText.isEmpty()) {
                    continue;
                }
                float wWidth = FontManager.getStringWidth(client, wText);
                float phaseSeed = slot.lineIdx * 0.31f + (rIdx * 7 + wIdx) * 0.13f;

                if (nowPos < word.timeMs) {
                    TextEffectRenderer.drawStyledText(client, wText, cursorX, rowY, wWidth,
                            0x9EB0C4, globalAlpha * 0.54f, true, 0.0f, phaseSeed, false);
                } else if (nowPos <= word.endTimeMs) {
                    float t = (float) (nowPos - word.timeMs) / Math.max(40.0f, (float) (word.endTimeMs - word.timeMs));
                    t = Math.max(0.0f, Math.min(1.0f, t));
                    float wScale = evalWordScale(t);
                    float wLift = evalWordLift(t) * 1.15f;
                    float wGlow = evalWordGlow(t) * glowFactor * 1.15f;

                    GlStateManager.pushMatrix();
                    GlStateManager.translatef(cursorX + wWidth * 0.5f, rowY + 4.5f - wLift, 0.0f);
                    GlStateManager.scalef(wScale, wScale, 1.0f);
                    TextEffectRenderer.drawWipedStyledText(client, wText, -wWidth * 0.5f, -4.5f, wWidth,
                            0xA8B9CC, globalAlpha * 0.62f, 0xFFFFFF, globalAlpha, t,
                            true, wGlow, phaseSeed);
                    GlStateManager.popMatrix();
                } else {
                    float afterMs = (float) (nowPos - word.endTimeMs);
                    float tailGlow = glowFactor * 0.55f * (1.0f - Math.min(1.0f, afterMs / 320.0f));
                    TextEffectRenderer.drawStyledText(client, wText, cursorX, rowY, wWidth,
                            highlightRgb, globalAlpha, true, tailGlow, phaseSeed);
                }

                cursorX += wWidth;
                if (wIdx + 1 < row.words.size()) {
                    cursorX += spaceW;
                }
            }
        }
    }

    private static void renderClassicHud(Minecraft client, Window window, List<LyricsFetcher.LyricLine> lines,
                                         int activeIdx, long nowPos, float globalAlpha) {
        LyricsFetcher.LyricLine current = lines.get(activeIdx);
        LyricsFetcher.LyricLine next = (activeIdx + 1 < lines.size()) ? lines.get(activeIdx + 1) : null;

        float userScale = Math.max(0.5f, Math.min(2.5f, LyricsConfig.scalePercent / 100.0f));
        float slideY = Math.max(-6.0f, Math.min(6.0f, hudScrollCurrent * 4.5f));

        String currText = formatCase(current.fullLine);
        String nextText = next != null ? formatCase(next.fullLine) : "\u266A";

        float currW = FontManager.getStringWidth(client, currText);
        float nextW = FontManager.getStringWidth(client, nextText);
        float unscaledW = Math.max(120.0f, Math.max(currW, nextW) + 8.0f);
        float unscaledH = 26.0f;

        float defaultX = 16.0f;
        float defaultY = Math.max(16.0f, window.getHeight() - 56.0f);
        OneConfigCompat.notifyHudBounds(defaultX, defaultY, unscaledW, unscaledH);

        float baseX = LyricsConfig.hudX >= 0.0f ? LyricsConfig.hudX : defaultX;
        float baseY = LyricsConfig.hudY >= 0.0f ? LyricsConfig.hudY : defaultY;

        int primaryRgb = OneConfigCompat.resolvePrimaryColorRgb(LyricsConfig.primaryColor);
        int highlightRgb = LyricsConfig.rainbowPastel ? lerpRgb(primaryRgb, 0x55AAFF, 0.35f) : primaryRgb;
        float glowFactor = LyricsConfig.getGlowFactor();

        GlStateManager.pushMatrix();
        GlStateManager.translated(baseX, baseY + slideY, 0.0);
        GlStateManager.scalef(userScale, userScale, 1.0f);

        if (current.words != null && !current.words.isEmpty()) {
            renderClassicWordLine(client, current, activeIdx, nowPos, highlightRgb, globalAlpha, glowFactor);
        } else {
            float prog = computeLineProgress(current, nowPos);
            TextEffectRenderer.drawWipedStyledText(client, currText, 0.0f, 0.0f, currW,
                    0xB8C2CC, globalAlpha * 0.78f, highlightRgb, globalAlpha, prog,
                    true, glowFactor, activeIdx * 0.31f);
        }

        TextEffectRenderer.drawStyledText(client, nextText, 0.0f, 13.0f, nextW,
                0x7A93AA, globalAlpha * 0.70f, true, glowFactor * 0.25f, (activeIdx + 1) * 0.31f, false);

        GlStateManager.popMatrix();
    }

    private static void renderClassicWordLine(Minecraft client, LyricsFetcher.LyricLine line, int lineIdx, long nowPos,
                                              int highlightRgb, float globalAlpha, float glowFactor) {
        float cursorX = 0.0f;
        float spaceW = FontManager.getCharWidth(client, ' ');

        for (int i = 0; i < line.words.size(); i++) {
            LyricsFetcher.LyricWord word = line.words.get(i);
            String wText = formatCase(word.text);
            if (wText.isEmpty()) {
                continue;
            }
            float wWidth = FontManager.getStringWidth(client, wText);
            float phaseSeed = lineIdx * 0.31f + i * 0.14f;

            if (nowPos >= word.timeMs && nowPos <= word.endTimeMs) {
                float p = (float) (nowPos - word.timeMs) / Math.max(40.0f, (float) (word.endTimeMs - word.timeMs));
                p = Math.max(0.0f, Math.min(1.0f, p));
                float liftY = -evalWordLift(p) * 1.15f;
                float wScale = evalWordScale(p);
                float wGlow = evalWordGlow(p) * glowFactor * 1.2f;

                GlStateManager.pushMatrix();
                GlStateManager.translatef(cursorX + wWidth * 0.5f, 4.5f + liftY, 0.0f);
                GlStateManager.scalef(wScale, wScale, 1.0f);
                TextEffectRenderer.drawWipedStyledText(client, wText, -wWidth * 0.5f, -4.5f, wWidth,
                        0xB8C2CC, globalAlpha * 0.65f, 0xFFFFFF, globalAlpha, p, true, wGlow, phaseSeed);
                GlStateManager.popMatrix();
            } else if (nowPos > word.endTimeMs) {
                float afterMs = (float) (nowPos - word.endTimeMs);
                float tailGlow = glowFactor * 0.65f * (1.0f - Math.min(1.0f, afterMs / 350.0f));
                TextEffectRenderer.drawStyledText(client, wText, cursorX, 0.0f, wWidth,
                        highlightRgb, globalAlpha, true, tailGlow, phaseSeed);
            } else {
                TextEffectRenderer.drawStyledText(client, wText, cursorX, 0.0f, wWidth,
                        0xB8C2CC, globalAlpha * 0.72f, true, glowFactor * 0.2f, phaseSeed, false);
            }

            cursorX += wWidth;
            if (i + 1 < line.words.size()) {
                cursorX += spaceW;
            }
        }
    }

    private static List<WrappedRow> wrapLine(Minecraft client, LyricsFetcher.LyricLine line,
                                             String fullText, float maxWidth) {
        List<WrappedRow> rows = new ArrayList<WrappedRow>();
        if (line.words != null && !line.words.isEmpty()) {
            WrappedRow current = new WrappedRow();
            float spaceW = FontManager.getCharWidth(client, ' ');
            float rowW = 0.0f;
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < line.words.size(); i++) {
                LyricsFetcher.LyricWord word = line.words.get(i);
                String wTxt = formatCase(word.text);
                if (wTxt.isEmpty()) continue;
                float ww = FontManager.getStringWidth(client, wTxt);
                float needed = current.words.isEmpty() ? ww : (spaceW + ww);

                if (!current.words.isEmpty() && rowW + needed > maxWidth) {
                    current.plainText = sb.toString();
                    rows.add(current);
                    current = new WrappedRow();
                    sb.setLength(0);
                    rowW = 0.0f;
                }
                if (!current.words.isEmpty()) {
                    sb.append(' ');
                    rowW += spaceW;
                }
                current.words.add(word);
                sb.append(wTxt);
                rowW += ww;
            }
            if (!current.words.isEmpty()) {
                current.plainText = sb.toString();
                rows.add(current);
            }
            if (!rows.isEmpty()) {
                return rows;
            }
        }

        // Fallback character/space wrapping for plain lines
        String[] tokens = fullText.split(" ");
        float spaceW = FontManager.getCharWidth(client, ' ');
        StringBuilder sb = new StringBuilder();
        float rowW = 0.0f;
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i];
            if (tok.isEmpty()) continue;
            float tw = FontManager.getStringWidth(client, tok);
            float needed = sb.length() == 0 ? tw : (spaceW + tw);
            if (sb.length() > 0 && rowW + needed > maxWidth) {
                WrappedRow r = new WrappedRow();
                r.plainText = sb.toString();
                rows.add(r);
                sb.setLength(0);
                rowW = 0.0f;
            }
            if (sb.length() > 0) {
                sb.append(' ');
                rowW += spaceW;
            }
            sb.append(tok);
            rowW += tw;
        }
        if (sb.length() > 0 || rows.isEmpty()) {
            WrappedRow r = new WrappedRow();
            r.plainText = sb.length() > 0 ? sb.toString() : fullText;
            rows.add(r);
        }
        return rows;
    }

    private static float computeLineProgress(LyricsFetcher.LyricLine line, long nowPos) {
        if (line.words != null && !line.words.isEmpty()) {
            int n = line.words.size();
            for (int i = 0; i < n; i++) {
                LyricsFetcher.LyricWord w = line.words.get(i);
                if (nowPos < w.timeMs) {
                    return i / (float) n;
                }
                if (nowPos <= w.endTimeMs) {
                    float wp = (float) (nowPos - w.timeMs) / Math.max(40.0f, (float) (w.endTimeMs - w.timeMs));
                    return (i + Math.max(0.0f, Math.min(1.0f, wp))) / (float) n;
                }
            }
            return 1.0f;
        }
        long span = Math.max(200L, line.endTimeMs - line.startTimeMs);
        return Math.max(0.0f, Math.min(1.0f, (float) (nowPos - line.startTimeMs) / (float) span));
    }

    private static CameraView computeCamera(Minecraft client, Window window, float tickDelta) {
        Entity cam = client.getCamera() != null ? client.getCamera() : client.player;

        double ex = cam.prevX + (cam.x - cam.prevX) * tickDelta;
        double ey = cam.prevY + (cam.y - cam.prevY) * tickDelta + cam.getEyeHeight();
        double ez = cam.prevZ + (cam.z - cam.prevZ) * tickDelta;

        float yawDeg = cam.lastYaw + wrapDegrees(cam.yaw - cam.lastYaw) * tickDelta;
        float pitchDeg = cam.lastPitch + (cam.pitch - cam.lastPitch) * tickDelta;

        int perspective = client.options.perspective;
        if (perspective == 2) {
            yawDeg += 180.0f;
            pitchDeg = -pitchDeg;
        }

        double yawRad = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);
        double sinY = Math.sin(yawRad);
        double cosY = Math.cos(yawRad);
        double sinP = Math.sin(pitchRad);
        double cosP = Math.cos(pitchRad);

        CameraView view = new CameraView();
        view.fX = -sinY * cosP;
        view.fY = -sinP;
        view.fZ = cosY * cosP;

        view.rX = -cosY;
        view.rZ = -sinY;

        // Right-handed Camera Up = Right x Forward (uy = +cos(pitch), positive upward)
        view.uX = -view.rZ * view.fY;
        view.uY = view.rZ * view.fX - view.rX * view.fZ;
        view.uZ = view.rX * view.fY;

        double back = (perspective == 0) ? 0.0 : 4.0;
        view.x = ex - view.fX * back;
        view.y = ey - view.fY * back;
        view.z = ez - view.fZ * back;
        view.yawDeg = yawDeg;

        double fov = Math.max(30.0, Math.min(130.0, client.options.fov));
        view.tanHalfFovY = Math.tan(Math.toRadians(fov) * 0.5);
        view.aspect = (double) Math.max(1, client.width) / (double) Math.max(1, client.height);
        view.scaledH = Math.max(1.0, (double) window.getHeight());
        view.scaledW = view.scaledH * view.aspect;
        return view;
    }

    private static ProjectedPoint projectToScreen(CameraView cam, double wx, double wy, double wz) {
        double dx = wx - cam.x;
        double dy = wy - cam.y;
        double dz = wz - cam.z;

        double camZ = dx * cam.fX + dy * cam.fY + dz * cam.fZ;
        if (camZ <= 0.2) {
            return null;
        }

        double camX = dx * cam.rX + dz * cam.rZ;
        double camY = dx * cam.uX + dy * cam.uY + dz * cam.uZ;

        double ndcX = camX / (camZ * cam.tanHalfFovY * cam.aspect);
        double ndcY = camY / (camZ * cam.tanHalfFovY);

        if (Math.abs(ndcX) > 2.2 || Math.abs(ndcY) > 2.2) {
            return null;
        }

        ProjectedPoint pt = new ProjectedPoint();
        pt.screenX = (0.5 + 0.5 * ndcX) * cam.scaledW;
        pt.screenY = (0.5 - 0.5 * ndcY) * cam.scaledH;
        pt.pixelsPerBlock = (0.5 * cam.scaledH) / (camZ * cam.tanHalfFovY);
        return pt;
    }

    // EvergreenHUD under-damped spring response (omega = 11 rad/s, zeta = 0.9)
    private static float evaluateSpring(float elapsedMs) {
        if (elapsedMs <= 0.0f) return 0.0f;
        if (elapsedMs >= 1500.0f) return 1.0f;
        double s = elapsedMs / 1000.0;
        double wd = SPRING_OMEGA * Math.sqrt(1.0 - SPRING_DAMPING * SPRING_DAMPING);
        double env = Math.exp(-SPRING_OMEGA * SPRING_DAMPING * s);
        return (float) (1.0 - env * (Math.cos(wd * s) + (SPRING_OMEGA * SPRING_DAMPING / wd) * Math.sin(wd * s)));
    }

    // EvergreenHUD smooth word keyframes
    private static float evalWordScale(float t) {
        if (t <= 0.7f) {
            float u = smoothstep(0.0f, 0.7f, t);
            return 0.985f + (1.045f - 0.985f) * u;
        }
        float u = smoothstep(0.7f, 1.0f, t);
        return 1.045f + (1.0f - 1.045f) * u;
    }

    private static float evalWordLift(float t) {
        if (t <= 0.85f) {
            return smoothstep(0.0f, 0.85f, t);
        }
        return 1.0f - 0.4f * smoothstep(0.85f, 1.0f, t);
    }

    private static float evalWordGlow(float t) {
        if (t <= 0.15f) {
            return smoothstep(0.0f, 0.15f, t);
        }
        if (t <= 0.65f) {
            return 1.0f;
        }
        return 1.0f - smoothstep(0.65f, 1.0f, t);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / Math.max(1e-5f, edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    private static String formatCase(String s) {
        if (s == null) return "";
        return LyricsConfig.uppercase ? s.toUpperCase(Locale.ROOT) : s;
    }

    private static List<LyricsFetcher.LyricLine> createPreviewLines() {
        List<LyricsFetcher.LyricLine> list = new ArrayList<LyricsFetcher.LyricLine>();
        list.add(new LyricsFetcher.LyricLine(-2500L, 0L, "Previous lyric drifts softly", null));

        List<LyricsFetcher.LyricWord> w1 = new ArrayList<LyricsFetcher.LyricWord>();
        w1.add(new LyricsFetcher.LyricWord(0L, 800L, "AirLyrics"));
        w1.add(new LyricsFetcher.LyricWord(800L, 1700L, "apple"));
        w1.add(new LyricsFetcher.LyricWord(1700L, 2600L, "preview"));
        list.add(new LyricsFetcher.LyricLine(0L, 2600L, "AirLyrics apple preview", w1));

        List<LyricsFetcher.LyricWord> w2 = new ArrayList<LyricsFetcher.LyricWord>();
        w2.add(new LyricsFetcher.LyricWord(2600L, 3600L, "Smooth"));
        w2.add(new LyricsFetcher.LyricWord(3600L, 4800L, "gradients!"));
        list.add(new LyricsFetcher.LyricLine(2600L, 4800L, "Smooth gradients!", w2));

        list.add(new LyricsFetcher.LyricLine(4800L, 7400L, "Drag & resize in HUD editor", null));
        return list;
    }

    private static int toRenderAlpha(float alpha) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        return a <= 4 ? 0 : Math.min(255, a);
    }

    private static int lerpRgb(int c1, int c2, float t) {
        return TextEffectRenderer.lerpRgb(c1, c2, t);
    }

    private static float approach(float current, float target, float maxStep) {
        if (current < target) return Math.min(target, current + maxStep);
        return Math.max(target, current - maxStep);
    }

    private static final class FloatingWord {
        String text;
        double worldX, worldY, worldZ;
        long spawnMs;
        long durationMs;
        int colorRgb;
        float phaseSeed;
        long wordStartMs;
        long wordEndMs;
        List<LyricsFetcher.LyricWord> subWords;
    }

    private static final class CameraView {
        double x, y, z;
        float yawDeg;
        double fX, fY, fZ;
        double rX, rZ;
        double uX, uY, uZ;
        double tanHalfFovY;
        double scaledW, scaledH;
        double aspect;
    }

    private static final class ProjectedPoint {
        double screenX;
        double screenY;
        double pixelsPerBlock;
    }

    private static final class WrappedRow {
        final List<LyricsFetcher.LyricWord> words = new ArrayList<LyricsFetcher.LyricWord>();
        String plainText = "";
    }

    private static final class AppleSlot {
        int delta;
        int lineIdx;
        LyricsFetcher.LyricLine line;
        List<WrappedRow> wrapped;
        float height;
    }
}
