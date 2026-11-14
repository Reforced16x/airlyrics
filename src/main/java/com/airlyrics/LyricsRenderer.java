package com.airlyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

public class LyricsRenderer {
    public static class FloatingWord {
        public final String text;
        public final double x, y, z;
        public final int color;
        public final long spawnTimeMs;
        public final long durationMs;

        public FloatingWord(String text, double x, double y, double z, int color, long durationMs) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
            this.spawnTimeMs = System.currentTimeMillis();
            this.durationMs = durationMs;
        }
    }

    private static class CameraFrame {
        double x, y, z;
        double fx, fy, fz;
        double rx, rz;
        double ux, uy, uz;
    }

    private static final List<FloatingWord> ACTIVE_WORLD_WORDS = new CopyOnWriteArrayList<>();
    private static final Random RANDOM = new Random();
    private static final double THIRD_PERSON_DISTANCE = 4.0;
    private static final int[] PASTEL_PALETTE = {
        0xFF99CC,
        0x77DD77,
        0x88CEFA,
        0xFFDF77,
        0xCBAACB
    };

    private static long lastPlaybackPos = 0;

    public static void clearActiveWords() {
        ACTIVE_WORLD_WORDS.clear();
        for (LyricsFetcher.LyricLine line : LyricsFetcher.currentLyrics) {
            for (LyricsFetcher.LyricWord w : line.words) {
                w.spawned = false;
            }
        }
    }

    public static void tick(Minecraft client) {
        if (!LyricsConfig.enabled || client.player == null || client.world == null) return;

        long nowPos = MediaTracker.getCurrentPositionMs();
        if (Math.abs(nowPos - lastPlaybackPos) > 3500) {
            for (LyricsFetcher.LyricLine line : LyricsFetcher.currentLyrics) {
                for (LyricsFetcher.LyricWord w : line.words) {
                    w.spawned = w.timeMs < nowPos - 200;
                }
            }
        }
        lastPlaybackPos = nowPos;

        if (LyricsConfig.inWorld && MediaTracker.isPlaying) {
            for (LyricsFetcher.LyricLine line : LyricsFetcher.currentLyrics) {
                if (nowPos < line.startTimeMs - 100 || nowPos > line.endTimeMs) continue;

                if (LyricsConfig.showWords) {
                    for (int i = 0; i < line.words.size(); i++) {
                        LyricsFetcher.LyricWord word = line.words.get(i);
                        if (!word.spawned && nowPos >= word.timeMs) {
                            word.spawned = true;
                            spawnWordInView(client, word.text, i, line.words.size(), 1850);
                        }
                    }
                } else if (!line.words.isEmpty()) {
                    LyricsFetcher.LyricWord first = line.words.get(0);
                    if (!first.spawned && nowPos >= line.startTimeMs) {
                        first.spawned = true;
                        spawnWordInView(client, line.fullLine, 0, 1, Math.max(1800, line.endTimeMs - line.startTimeMs));
                    }
                }
            }
        }

        long sysNow = System.currentTimeMillis();
        ACTIVE_WORLD_WORDS.removeIf(fw -> sysNow - fw.spawnTimeMs > fw.durationMs);
    }

    private static CameraFrame computeCamera(Minecraft client, float t) {
        Entity e = client.getCamera();
        if (e == null) e = client.player;

        double ex = e.prevX + (e.x - e.prevX) * t;
        double ey = e.prevY + (e.y - e.prevY) * t + e.getEyeHeight();
        double ez = e.prevZ + (e.z - e.prevZ) * t;

        float yaw = e.lastYaw + (e.yaw - e.lastYaw) * t;
        float pitch = e.lastPitch + (e.pitch - e.lastPitch) * t;

        int perspective = client.options.perspective;
        if (perspective == 2) {
            // Front third-person view looks back toward the player
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

    private static void spawnWordInView(Minecraft client, String rawText, int wordIndex, int totalWords, long duration) {
        String text = LyricsConfig.uppercase ? rawText.toUpperCase() : rawText;
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

        int color = LyricsConfig.rainbowPastel
            ? PASTEL_PALETTE[RANDOM.nextInt(PASTEL_PALETTE.length)]
            : LyricsConfig.primaryColor;

        ACTIVE_WORLD_WORDS.add(new FloatingWord(text, wx, wy, wz, color, duration));
    }

    public static void renderHudLyrics(float tickDelta) {
        if (!LyricsConfig.enabled) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        Window window = new Window(client);
        // Project world words in the HUD pass so they billboard cleanly in every perspective
        renderProjectedWords(client, window, tickDelta);

        if (LyricsConfig.onScreen) {
            renderHudBar(client, window);
        }
    }

    private static void renderProjectedWords(Minecraft client, Window window, float tickDelta) {
        if (!LyricsConfig.inWorld || ACTIVE_WORLD_WORDS.isEmpty()) return;

        CameraFrame c = computeCamera(client, tickDelta);
        int sh = window.getHeight();
        double aspect = (double) client.width / Math.max(1, client.height);
        double sw = sh * aspect;
        double tanHalf = Math.tan(Math.toRadians(client.options.fov) / 2.0);

        TextRenderer font = client.textRenderer;
        long sysNow = System.currentTimeMillis();

        for (FloatingWord fw : ACTIVE_WORLD_WORDS) {
            long age = sysNow - fw.spawnTimeMs;
            float progress = (float) age / (float) fw.durationMs;
            if (progress < 0f || progress > 1f) continue;

            float popScale = progress < 0.12f ? (progress / 0.12f) : 1.0f;
            float alpha = progress > 0.72f ? (1.0f - (progress - 0.72f) / 0.28f) : 1.0f;
            int alphaInt = Math.max(5, Math.min(255, (int) (alpha * 255.0f)));

            double floatUp = progress * 0.28;
            double dx = fw.x - c.x;
            double dy = (fw.y + floatUp) - c.y;
            double dz = fw.z - c.z;

            double depth = dx * c.fx + dy * c.fy + dz * c.fz;
            if (depth < 0.4) continue;

            double camX = dx * c.rx + dz * c.rz;
            double camY = dx * c.ux + dy * c.uy + dz * c.uz;

            double screenX = sw / 2.0 + (camX / (depth * tanHalf * aspect)) * (sw / 2.0);
            double screenY = sh / 2.0 - (camY / (depth * tanHalf)) * (sh / 2.0);
            if (screenX < -300 || screenX > sw + 300 || screenY < -100 || screenY > sh + 100) continue;

            double pixelsPerBlock = (sh / 2.0) / (depth * tanHalf);
            float scale = (float) (0.038 * (LyricsConfig.scalePercent / 100.0) * popScale * pixelsPerBlock);
            scale = Math.min(scale, 8.0f);
            if (scale < 0.05f) continue;

            GlStateManager.pushMatrix();
            GlStateManager.translated(screenX, screenY, 0);
            GlStateManager.scalef(scale, scale, 1.0f);

            GlStateManager.disableLighting();
            GlStateManager.disableDepthTest();
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

            int argb = (alphaInt << 24) | (fw.color & 0x00FFFFFF);
            int width = font.getWidth(fw.text);
            font.drawWithShadow(fw.text, -width / 2.0f, -4.0f, argb);

            GlStateManager.enableDepthTest();
            GlStateManager.disableBlend();
            GlStateManager.color4f(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.popMatrix();
        }
    }

    private static void renderHudBar(Minecraft client, Window window) {
        TextRenderer font = client.textRenderer;
        long nowPos = MediaTracker.getCurrentPositionMs();

        List<LyricsFetcher.LyricLine> lines = LyricsFetcher.currentLyrics;
        if (lines.isEmpty()) return;

        int activeIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (nowPos >= lines.get(i).startTimeMs && nowPos < lines.get(i).endTimeMs) {
                activeIdx = i;
                break;
            }
        }
        if (activeIdx == -1) return;

        LyricsFetcher.LyricLine current = lines.get(activeIdx);
        LyricsFetcher.LyricLine next = (activeIdx + 1 < lines.size()) ? lines.get(activeIdx + 1) : null;

        float scale = LyricsConfig.scalePercent / 100.0f;
        int baseX = 16;
        int baseY = window.getHeight() - 56;

        GlStateManager.pushMatrix();
        GlStateManager.translated(baseX, baseY, 0);
        GlStateManager.scalef(scale, scale, 1.0f);

        String currText = LyricsConfig.uppercase ? current.fullLine.toUpperCase() : current.fullLine;
        float lineProgress = Math.max(0f, Math.min(1f,
            (float) (nowPos - current.startTimeMs) / Math.max(1f, (float) (current.endTimeMs - current.startTimeMs))));

        int splitChar = Math.round(currText.length() * lineProgress);
        String sungPart = currText.substring(0, splitChar);
        String unsungPart = currText.substring(splitChar);

        int highlightColor = LyricsConfig.rainbowPastel ? 0x55AAFF : LyricsConfig.primaryColor;
        font.drawWithShadow(sungPart, 0, 0, highlightColor);
        font.drawWithShadow(unsungPart, font.getWidth(sungPart), 0, 0xAAAAAA);

        if (next != null) {
            String nextText = LyricsConfig.uppercase ? next.fullLine.toUpperCase() : next.fullLine;
            font.drawWithShadow(nextText, 0, 12, 0x6688AA);
        } else {
            font.drawWithShadow("\u266A", 0, 12, highlightColor);
        }

        GlStateManager.popMatrix();
    }
}
