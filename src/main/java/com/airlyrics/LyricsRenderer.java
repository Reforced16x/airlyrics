package com.airlyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.Entity;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Renders AirLyrics in both 3D camera-projected floating world mode and 2D HUD mode.
 * Features EvergreenHUD-grade exponential clock smoothing, pause fading, OneConfig HUD bounds
 * and preview integration, opacity control, larger important/chorus lyrics, and
 * shader/dynamic-lighting-safe emissive bloom & shine.
 */
public class LyricsRenderer {
    public static class FloatingWord {
        public final String text;
        public final double x, y, z;
        public final int color;
        public final long spawnTimeMs;
        public final long durationMs;
        public final float importanceScale;
        public final boolean important;

        public FloatingWord(String text, double x, double y, double z, int color, long durationMs,
                            boolean important, float importanceScale) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
            this.spawnTimeMs = System.currentTimeMillis();
            this.durationMs = Math.max(400L, durationMs);
            this.important = important;
            this.importanceScale = Math.max(1.0f, importanceScale);
        }
    }

    private static class CameraFrame {
        double x, y, z;
        double fx, fy, fz;
        double rx, rz;
        double ux, uy, uz;
    }

    private static final List<FloatingWord> ACTIVE_WORLD_WORDS = new CopyOnWriteArrayList<FloatingWord>();
    private static final Random RANDOM = new Random();
    private static final double THIRD_PERSON_DISTANCE = 4.0;
    private static final int[] PASTEL_PALETTE = {
            0xFF99CC,
            0x77DD77,
            0x88CEFA,
            0xFFDF77,
            0xCBAACB
    };

    private static long lastPlaybackPos = 0L;
    private static double smoothPosMs = 0.0;
    private static long lastFrameNs = 0L;
    private static String smoothTrackKey = "";
    private static boolean clockInitialized = false;

    private static int lastHudLineIdx = -1;
    private static float hudTransition = 1.0f;
    private static float pauseAlpha = 1.0f;
    private static long pausedSinceMs = 0L;

    public static void clearActiveWords() {
        ACTIVE_WORLD_WORDS.clear();
        for (LyricsFetcher.LyricLine line : LyricsFetcher.currentLyrics) {
            for (LyricsFetcher.LyricWord w : line.words) {
                w.spawned = false;
            }
        }
        clockInitialized = false;
        lastPlaybackPos = 0L;
        lastHudLineIdx = -1;
        hudTransition = 1.0f;
    }

    private static double updateClockAndTransitions() {
        long nowNs = System.nanoTime();
        double dtMs = lastFrameNs == 0L ? 16.0 : Math.max(1.0, Math.min(100.0, (nowNs - lastFrameNs) / 1_000_000.0));
        lastFrameNs = nowNs;

        String trackKey = LyricsFetcher.currentTrackKey;
        if (trackKey != null && !trackKey.equals(smoothTrackKey)) {
            smoothTrackKey = trackKey;
            clockInitialized = false;
            lastHudLineIdx = -1;
            hudTransition = 1.0f;
        }

        boolean editorOpen = OneConfigCompat.isHudEditorOpen();
        long nowMs = System.currentTimeMillis();
        if (MediaTracker.isPlaying || editorOpen) {
            pausedSinceMs = 0L;
            pauseAlpha = approach(pauseAlpha, 1.0f, (float) (dtMs / 180.0));
        } else {
            if (pausedSinceMs == 0L) {
                pausedSinceMs = nowMs;
            }
            long pausedFor = nowMs - pausedSinceMs;
            float target = pausedFor > 5000L ? 0.30f : 0.70f;
            pauseAlpha = approach(pauseAlpha, target, (float) (dtMs / 400.0));
        }

        double rawPos = Math.max(0L, MediaTracker.getEstimatedPositionMs() + LyricsConfig.syncOffsetMs);
        if (!clockInitialized || Math.abs(rawPos - smoothPosMs) > 650.0) {
            smoothPosMs = rawPos;
            clockInitialized = true;
        } else if (MediaTracker.isPlaying) {
            double predicted = smoothPosMs + dtMs;
            double alpha = 1.0 - Math.exp(-dtMs / 250.0);
            double updated = predicted + (rawPos - predicted) * alpha;
            if (updated < smoothPosMs - 40.0) {
                updated = smoothPosMs;
            }
            smoothPosMs = updated;
        }

        if (hudTransition < 1.0f) {
            hudTransition = Math.min(1.0f, hudTransition + (float) (dtMs / 320.0));
        }
        return smoothPosMs;
    }

    public static void tick(Minecraft client) {
        if (!LyricsConfig.enabled || client == null || client.player == null || client.world == null) {
            return;
        }

        long nowPos = Math.max(0L, MediaTracker.getEstimatedPositionMs() + LyricsConfig.syncOffsetMs);
        if (Math.abs(nowPos - lastPlaybackPos) > 3500L) {
            for (LyricsFetcher.LyricLine line : LyricsFetcher.currentLyrics) {
                for (LyricsFetcher.LyricWord w : line.words) {
                    w.spawned = w.timeMs < nowPos - 200L;
                }
            }
        }
        lastPlaybackPos = nowPos;

        if (LyricsConfig.inWorld && MediaTracker.isPlaying) {
            for (LyricsFetcher.LyricLine line : LyricsFetcher.currentLyrics) {
                if (nowPos < line.startTimeMs - 100L || nowPos > line.endTimeMs) {
                    continue;
                }

                if (LyricsConfig.showWords && !line.words.isEmpty()) {
                    for (int i = 0; i < line.words.size(); i++) {
                        LyricsFetcher.LyricWord word = line.words.get(i);
                        if (!word.spawned && nowPos >= word.timeMs) {
                            word.spawned = true;
                            boolean isImp = line.important || word.important;
                            float boost = Math.max(line.important ? line.importanceBoost : 1.0f,
                                    word.important ? word.boost : 1.0f);
                            long dur = Math.max(1700L, (word.endTimeMs - word.timeMs) + 1250L);
                            spawnWordInView(client, word.text, i, line.words.size(), Math.min(2600L, dur), isImp, boost);
                        }
                    }
                } else {
                    boolean markerSpawned = !line.words.isEmpty() && line.words.get(0).spawned;
                    if (!markerSpawned && nowPos >= line.startTimeMs) {
                        if (!line.words.isEmpty()) {
                            line.words.get(0).spawned = true;
                        }
                        long dur = Math.max(1800L, line.endTimeMs - line.startTimeMs);
                        spawnWordInView(client, line.fullLine, 0, 1, dur, line.important, line.importanceBoost);
                    }
                }
            }
        }

        long sysNow = System.currentTimeMillis();
        ACTIVE_WORLD_WORDS.removeIf(fw -> sysNow - fw.spawnTimeMs > fw.durationMs);
    }

    private static CameraFrame computeCamera(Minecraft client, float t) {
        Entity e = client.getCamera();
        if (e == null) {
            e = client.player;
        }

        double ex = e.prevX + (e.x - e.prevX) * t;
        double ey = e.prevY + (e.y - e.prevY) * t + e.getEyeHeight();
        double ez = e.prevZ + (e.z - e.prevZ) * t;

        float yaw = e.lastYaw + (e.yaw - e.lastYaw) * t;
        float pitch = e.lastPitch + (e.pitch - e.lastPitch) * t;

        int perspective = client.options.perspective;
        if (perspective == 2) {
            yaw += 180.0F;
            pitch = -pitch;
        }

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        double sinY = Math.sin(yawRad), cosY = Math.cos(yawRad);
        double sinP = Math.sin(pitchRad), cosP = Math.cos(pitchRad);

        CameraFrame c = new CameraFrame();
        c.fx = -sinY * cosP;
        c.fy = -sinP;
        c.fz = cosY * cosP;

        c.rx = -cosY;
        c.rz = -sinY;

        c.ux = -c.rz * c.fy;
        c.uy = c.rz * c.fx - c.rx * c.fz;
        c.uz = c.rx * c.fy;

        double back = perspective == 0 ? 0.0 : THIRD_PERSON_DISTANCE;
        c.x = ex - c.fx * back;
        c.y = ey - c.fy * back;
        c.z = ez - c.fz * back;
        return c;
    }

    private static void spawnWordInView(Minecraft client, String rawText, int wordIndex, int totalWords,
                                        long duration, boolean important, float boost) {
        String text = LyricsConfig.uppercase ? rawText.toUpperCase(Locale.ROOT) : rawText;
        CameraFrame c = computeCamera(client, 1.0F);

        boolean firstPerson = client.options.perspective == 0;
        double distance = firstPerson ? 4.2 : THIRD_PERSON_DISTANCE + 2.6;
        double spread = distance / 4.2;

        double offsetH;
        double offsetV;
        if (LyricsConfig.scatterLayout) {
            offsetH = (RANDOM.nextDouble() - 0.5) * 2.8 * spread;
            offsetV = (RANDOM.nextDouble() - 0.45) * 1.6 * spread;
        } else {
            double centeredIndex = wordIndex - (totalWords - 1) / 2.0;
            offsetH = centeredIndex * 0.65 * spread;
            offsetV = 0.15 * spread;
        }

        double wx = c.x + c.fx * distance + c.rx * offsetH + c.ux * offsetV;
        double wy = c.y + c.fy * distance + c.uy * offsetV;
        double wz = c.z + c.fz * distance + c.rz * offsetH + c.uz * offsetV;

        int fixedColor = OneConfigCompat.resolvePrimaryColorRgb(LyricsConfig.primaryColor);
        int color = LyricsConfig.rainbowPastel
                ? PASTEL_PALETTE[RANDOM.nextInt(PASTEL_PALETTE.length)]
                : fixedColor;

        ACTIVE_WORLD_WORDS.add(new FloatingWord(text, wx, wy, wz, color, duration, important, boost));
    }

    /**
     * Main GUI render pass hook called from InGameHudMixin.
     */
    public static void renderHudLyrics(float tickDelta) {
        boolean editorOpen = OneConfigCompat.isHudEditorOpen();
        if (!LyricsConfig.enabled && !editorOpen) {
            return;
        }
        if (ShaderCompat.isShadowPass()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.textRenderer == null) {
            return;
        }

        updateClockAndTransitions();
        Window window = new Window(client);

        ShaderCompat.SavedRenderState glState = ShaderCompat.beginLuminousTextPass();
        try {
            if (LyricsConfig.enabled && LyricsConfig.inWorld) {
                renderProjectedWords(client, window, tickDelta);
            }
            if (LyricsConfig.onScreen || editorOpen) {
                renderHudBar(client, window, editorOpen);
            }
        } finally {
            ShaderCompat.endLuminousTextPass(glState);
        }
    }

    private static void renderProjectedWords(Minecraft client, Window window, float tickDelta) {
        if (ACTIVE_WORLD_WORDS.isEmpty()) {
            return;
        }

        CameraFrame c = computeCamera(client, tickDelta);
        int sh = window.getHeight();
        double aspect = (double) client.width / Math.max(1, client.height);
        double sw = sh * aspect;
        double tanHalf = Math.tan(Math.toRadians(client.options.fov) / 2.0);

        TextRenderer font = client.textRenderer;
        long sysNow = System.currentTimeMillis();
        float globalOpacity = LyricsConfig.getOpacityAlpha() * pauseAlpha;
        float glowFactor = LyricsConfig.getGlowFactor();
        int fixedPrimaryRgb = OneConfigCompat.resolvePrimaryColorRgb(LyricsConfig.primaryColor);

        for (FloatingWord fw : ACTIVE_WORLD_WORDS) {
            long age = sysNow - fw.spawnTimeMs;
            float progress = (float) age / (float) fw.durationMs;
            if (progress < 0f || progress > 1f) {
                continue;
            }

            float popScale = progress < 0.12f ? (progress / 0.12f) : 1.0f;
            float importanceMul = (LyricsConfig.importantLyricsLarger && fw.important) ? fw.importanceScale : 1.0f;
            float curveAlpha = progress > 0.72f ? (1.0f - (progress - 0.72f) / 0.28f) : 1.0f;
            float finalAlpha = curveAlpha * globalOpacity;
            if (toRenderAlpha(finalAlpha) <= 4) {
                continue;
            }

            double floatUp = progress * 0.28;
            double dx = fw.x - c.x;
            double dy = (fw.y + floatUp) - c.y;
            double dz = fw.z - c.z;

            double depth = dx * c.fx + dy * c.fy + dz * c.fz;
            if (depth < 0.4) {
                continue;
            }

            double camX = dx * c.rx + dz * c.rz;
            double camY = dx * c.ux + dy * c.uy + dz * c.uz;

            double screenX = sw / 2.0 + (camX / (depth * tanHalf * aspect)) * (sw / 2.0);
            double screenY = sh / 2.0 - (camY / (depth * tanHalf)) * (sh / 2.0);
            if (screenX < -300 || screenX > sw + 300 || screenY < -100 || screenY > sh + 100) {
                continue;
            }

            double pixelsPerBlock = (sh / 2.0) / (depth * tanHalf);
            float scale = (float) (0.038 * (LyricsConfig.scalePercent / 100.0) * importanceMul * popScale * pixelsPerBlock);
            scale = Math.min(scale, 8.5f);
            if (scale < 0.05f) {
                continue;
            }

            GlStateManager.pushMatrix();
            GlStateManager.translated(screenX, screenY, 0);
            GlStateManager.scalef(scale, scale, 1.0f);

            int rgb = LyricsConfig.rainbowPastel ? fw.color : fixedPrimaryRgb;
            float shinePhase = progress < 0.35f ? (1.0f - progress / 0.35f) : 0.0f;
            int width = font.getWidth(fw.text);
            drawLuminousText(font, fw.text, -width / 2.0f, -4.0f, rgb, finalAlpha,
                    glowFactor * (fw.important ? 1.25f : 1.0f), shinePhase);

            GlStateManager.popMatrix();
        }
    }

    private static void renderHudBar(Minecraft client, Window window, boolean editorOpen) {
        TextRenderer font = client.textRenderer;
        long nowPos = Math.round(smoothPosMs);

        List<LyricsFetcher.LyricLine> lines = LyricsFetcher.currentLyrics;
        LyricsFetcher.LyricLine current = null;
        LyricsFetcher.LyricLine next = null;

        if (lines != null && !lines.isEmpty() && MediaTracker.hasMedia) {
            int activeIdx = LyricsFetcher.getActiveLineIndex(nowPos);
            if (activeIdx < 0) {
                activeIdx = 0;
            }
            if (activeIdx != lastHudLineIdx) {
                lastHudLineIdx = activeIdx;
                hudTransition = 0.0f;
            }
            current = lines.get(activeIdx);
            next = (activeIdx + 1 < lines.size()) ? lines.get(activeIdx + 1) : null;
        } else if (editorOpen) {
            current = createPreviewCurrentLine();
            next = createPreviewNextLine();
            nowPos = current.startTimeMs + ((System.currentTimeMillis() % 2400L));
        } else {
            return;
        }

        float importanceScale = (LyricsConfig.importantLyricsLarger && current.important) ? current.importanceBoost : 1.0f;
        float userScale = Math.max(0.5f, Math.min(2.5f, LyricsConfig.scalePercent / 100.0f));
        float easedTransition = 1.0f - (float) Math.pow(1.0f - hudTransition, 3);
        float slideY = (1.0f - easedTransition) * 4.0f;

        String currText = formatCase(current.fullLine);
        String nextText = next != null ? formatCase(next.fullLine) : "\u266A";

        float unscaledW = Math.max(120.0f, Math.max(font.getWidth(currText) * importanceScale, font.getWidth(nextText)) + 8.0f);
        float unscaledH = 14.0f * importanceScale + 12.0f;

        float defaultX = 16.0f;
        float defaultY = Math.max(16.0f, window.getHeight() - 56.0f);
        OneConfigCompat.notifyHudBounds(defaultX, defaultY, unscaledW, unscaledH);

        float baseX = LyricsConfig.hudX >= 0.0f ? LyricsConfig.hudX : defaultX;
        float baseY = LyricsConfig.hudY >= 0.0f ? LyricsConfig.hudY : defaultY;

        float globalAlpha = LyricsConfig.getOpacityAlpha() * (editorOpen ? 1.0f : pauseAlpha);
        if (toRenderAlpha(globalAlpha) <= 4) {
            return;
        }

        int primaryRgb = OneConfigCompat.resolvePrimaryColorRgb(LyricsConfig.primaryColor);
        int highlightRgb = LyricsConfig.rainbowPastel ? lerpRgb(primaryRgb, 0x55AAFF, 0.35f) : primaryRgb;
        float glowFactor = LyricsConfig.getGlowFactor();

        GlStateManager.pushMatrix();
        GlStateManager.translated(baseX, baseY + slideY, 0);
        GlStateManager.scalef(userScale, userScale, 1.0f);

        // Render active HUD line (with word-by-word karaoke sweep when available + importance boost)
        GlStateManager.pushMatrix();
        GlStateManager.scalef(importanceScale, importanceScale, 1.0f);
        if (current.words != null && !current.words.isEmpty()) {
            renderHudWordLine(font, current, nowPos, highlightRgb, globalAlpha, glowFactor);
        } else {
            float lineProgress = Math.max(0f, Math.min(1f,
                    (float) (nowPos - current.startTimeMs) / Math.max(1f, (float) (current.endTimeMs - current.startTimeMs))));
            int splitChar = Math.round(currText.length() * lineProgress);
            String sungPart = currText.substring(0, splitChar);
            String unsungPart = currText.substring(splitChar);
            float shine = 0.45f + 0.45f * (float) Math.sin((System.currentTimeMillis() % 1400L) / 1400.0 * Math.PI * 2.0);
            drawLuminousText(font, sungPart, 0, 0, highlightRgb, globalAlpha, glowFactor, shine);
            drawLuminousText(font, unsungPart, font.getWidth(sungPart), 0, 0xB8C2CC, globalAlpha * 0.82f, glowFactor * 0.4f, 0.0f);
        }
        GlStateManager.popMatrix();

        // Render next line below
        float nextY = 12.0f * importanceScale + 1.0f;
        drawLuminousText(font, nextText, 0, nextY, 0x7A93AA, globalAlpha * 0.72f, glowFactor * 0.25f, 0.0f);

        GlStateManager.popMatrix();
    }

    private static void renderHudWordLine(TextRenderer font, LyricsFetcher.LyricLine line, long nowPos,
                                          int highlightRgb, float globalAlpha, float glowFactor) {
        float cursorX = 0.0f;
        float spaceW = font.getWidth(" ");
        long sysNow = System.currentTimeMillis();

        for (int i = 0; i < line.words.size(); i++) {
            LyricsFetcher.LyricWord word = line.words.get(i);
            String wText = formatCase(word.text);
            float wScale = (LyricsConfig.importantLyricsLarger && word.important) ? word.boost : 1.0f;

            boolean isCurrent = nowPos >= word.timeMs && nowPos <= word.endTimeMs;
            boolean isSung = nowPos > word.endTimeMs;

            int colorRgb;
            float alpha = globalAlpha;
            float shine = 0.0f;
            float wordGlow = glowFactor;
            float liftY = 0.0f;

            if (isCurrent) {
                float p = (float) (nowPos - word.timeMs) / Math.max(40.0f, (float) (word.endTimeMs - word.timeMs));
                liftY = -1.2f * (float) Math.sin(Math.min(1.0f, Math.max(0.0f, p)) * Math.PI);
                shine = 0.55f + 0.45f * (float) Math.sin((sysNow % 1200L) / 1200.0 * Math.PI * 2.0);
                colorRgb = lerpRgb(highlightRgb, 0xFFFFFF, shine * 0.45f);
                wordGlow *= 1.25f;
            } else if (isSung) {
                colorRgb = highlightRgb;
                wordGlow *= 0.85f;
            } else {
                colorRgb = 0xB8C2CC;
                alpha *= 0.78f;
                wordGlow *= 0.35f;
            }

            GlStateManager.pushMatrix();
            GlStateManager.translatef(cursorX, liftY - (wScale - 1.0f) * 2.0f, 0.0f);
            if (Math.abs(wScale - 1.0f) > 0.005f) {
                GlStateManager.scalef(wScale, wScale, 1.0f);
            }
            drawLuminousText(font, wText, 0.0f, 0.0f, colorRgb, alpha, wordGlow, shine);
            GlStateManager.popMatrix();

            cursorX += font.getWidth(wText) * wScale;
            if (i + 1 < line.words.size()) {
                cursorX += spaceW;
            }
        }
    }

    private static void drawLuminousText(TextRenderer font, String text, float x, float y,
                                         int rgb, float alpha, float glowFactor, float shinePhase) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int mainAlpha = toRenderAlpha(alpha);
        if (mainAlpha <= 4) {
            return;
        }

        if (LyricsConfig.glowEnabled && glowFactor > 0.01f) {
            int haloAlpha = toRenderAlpha(alpha * Math.min(0.85f, 0.28f * glowFactor));
            if (haloAlpha > 4) {
                int haloRgb = shinePhase > 0.05f ? lerpRgb(rgb & 0x00FFFFFF, 0xFFFFFF, shinePhase * 0.45f) : (rgb & 0x00FFFFFF);
                int haloArgb = (haloAlpha << 24) | haloRgb;

                ShaderCompat.setAdditiveGlowMode(true);
                float r = 0.65f + 0.45f * Math.min(1.8f, glowFactor);
                font.draw(text, x - r, y, haloArgb, false);
                font.draw(text, x + r, y, haloArgb, false);
                font.draw(text, x, y - r, haloArgb, false);
                font.draw(text, x, y + r, haloArgb, false);

                if (shinePhase > 0.1f || glowFactor >= 1.15f) {
                    int specAlpha = toRenderAlpha(alpha * Math.min(0.65f, (0.16f + 0.14f * shinePhase) * glowFactor));
                    if (specAlpha > 4) {
                        int specArgb = (specAlpha << 24) | 0xFFFFFF;
                        float r2 = r * 1.4f;
                        font.draw(text, x - r2, y - r2, specArgb, false);
                        font.draw(text, x + r2, y - r2, specArgb, false);
                        font.draw(text, x - r2, y + r2, specArgb, false);
                        font.draw(text, x + r2, y + r2, specArgb, false);
                    }
                }
                ShaderCompat.setAdditiveGlowMode(false);
            }
        }

        int argb = (mainAlpha << 24) | (rgb & 0x00FFFFFF);
        font.drawWithShadow(text, x, y, argb);
    }

    private static String formatCase(String s) {
        if (s == null) {
            return "";
        }
        return LyricsConfig.uppercase ? s.toUpperCase(Locale.ROOT) : s;
    }

    private static LyricsFetcher.LyricLine createPreviewCurrentLine() {
        LyricsFetcher.LyricWord w1 = new LyricsFetcher.LyricWord(0L, 900L, "AirLyrics");
        w1.important = true;
        w1.boost = 1.22f;
        LyricsFetcher.LyricWord w2 = new LyricsFetcher.LyricWord(900L, 1700L, "chorus");
        LyricsFetcher.LyricWord w3 = new LyricsFetcher.LyricWord(1700L, 2600L, "spotlight!");
        w3.important = true;
        w3.boost = 1.24f;
        LyricsFetcher.LyricLine line = new LyricsFetcher.LyricLine(0L, 2600L, "AirLyrics chorus spotlight!",
                Arrays.asList(w1, w2, w3));
        line.important = true;
        line.importanceBoost = 1.22f;
        return line;
    }

    private static LyricsFetcher.LyricLine createPreviewNextLine() {
        return new LyricsFetcher.LyricLine(2600L, 5000L, "Drag or resize HUD in OneConfig",
                Arrays.asList(new LyricsFetcher.LyricWord(2600L, 5000L, "Drag or resize HUD in OneConfig")));
    }

    /**
     * Minecraft 1.8.9 FontRenderer treats alpha bits < 4 (<= 3) as 255 opaque!
     */
    private static int toRenderAlpha(float alpha) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        if (a <= 4) {
            return 0;
        }
        return Math.min(255, a);
    }

    private static int lerpRgb(int c1, int c2, float t) {
        float k = Math.max(0.0f, Math.min(1.0f, t));
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int r = Math.round(r1 + (r2 - r1) * k);
        int g = Math.round(g1 + (g2 - g1) * k);
        int b = Math.round(b1 + (b2 - b1) * k);
        return (r << 16) | (g << 8) | b;
    }

    private static float approach(float current, float target, float maxStep) {
        if (current < target) {
            return Math.min(target, current + maxStep);
        }
        return Math.max(target, current - maxStep);
    }
}
