package com.airlyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Smooth hardware-filtered glow halo, per-character moving gradient colorist,
 * progressive feathered wipe text renderer, and translucent rounded backdrop utility.
 */
public final class TextEffectRenderer {
    private static int glowTexId = 0;
    private static int whiteTexId = 0;

    private TextEffectRenderer() {
    }

    /**
     * Computes a character's RGB color, smoothly evaluating linear or moving gradients when enabled.
     */
    public static int resolveColorRgb(int fallbackRgb, float normalizedPos, float phaseSeed) {
        if (!LyricsConfig.gradientEnabled) {
            return fallbackRgb & 0x00FFFFFF;
        }
        int start = resolveGradientStartRgb();
        int end = resolveGradientEndRgb();
        double shift = 0.0;
        if (LyricsConfig.movingGradient) {
            double speed = Math.max(0.1, LyricsConfig.gradientSpeedPercent / 100.0);
            shift = (System.currentTimeMillis() / 1000.0) * speed * 0.65;
        }
        // Seamless C-infinity cosine interpolation avoids any hard wrap seam on moving gradients.
        double wave = 0.5 - 0.5 * Math.cos(Math.PI * (normalizedPos * 1.15 + shift + phaseSeed));
        return lerpRgb(start, end, (float) wave);
    }

    public static int resolveGradientStartRgb() {
        return OneConfigCompat.resolveGradientStartRgb(LyricsConfig.gradientColorStart);
    }

    public static int resolveGradientEndRgb() {
        return OneConfigCompat.resolveGradientEndRgb(LyricsConfig.gradientColorEnd);
    }

    public static int lerpRgb(int rgbA, int rgbB, float t) {
        float clamped = Math.max(0.0f, Math.min(1.0f, t));
        int ar = (rgbA >> 16) & 0xFF;
        int ag = (rgbA >> 8) & 0xFF;
        int ab = rgbA & 0xFF;
        int br = (rgbB >> 16) & 0xFF;
        int bg = (rgbB >> 8) & 0xFF;
        int bb = rgbB & 0xFF;
        int r = Math.round(ar + (br - ar) * clamped);
        int g = Math.round(ag + (bg - ag) * clamped);
        int b = Math.round(ab + (bb - ab) * clamped);
        return (r << 16) | (g << 8) | b;
    }

    public static int mixArgb(int argbA, int argbB, float t) {
        float clamped = Math.max(0.0f, Math.min(1.0f, t));
        int aa = (argbA >>> 24) & 0xFF;
        int ar = (argbA >> 16) & 0xFF;
        int ag = (argbA >> 8) & 0xFF;
        int ab = argbA & 0xFF;

        int ba = (argbB >>> 24) & 0xFF;
        int br = (argbB >> 16) & 0xFF;
        int bg = (argbB >> 8) & 0xFF;
        int bb = argbB & 0xFF;

        int a = Math.round(aa + (ba - aa) * clamped);
        int r = Math.round(ar + (br - ar) * clamped);
        int g = Math.round(ag + (bg - ag) * clamped);
        int b = Math.round(ab + (bb - ab) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Builds per-character ARGB array across text applying gradient / fallback color and uniform alpha.
     */
    public static int[] buildCharColors(String text, int fallbackRgb, float alpha, float phaseSeed, float brightenMix) {
        int n = text == null ? 0 : text.length();
        int[] out = new int[Math.max(1, n)];
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        if (n == 0) {
            out[0] = (a << 24) | (fallbackRgb & 0x00FFFFFF);
            return out;
        }
        float denom = Math.max(1.0f, n - 1.0f);
        for (int i = 0; i < n; i++) {
            float u = n == 1 ? 0.5f : (i / denom);
            int rgb = resolveColorRgb(fallbackRgb, u, phaseSeed);
            if (brightenMix > 0.001f) {
                rgb = lerpRgb(rgb, 0xFFFFFF, brightenMix);
            }
            out[i] = (a << 24) | rgb;
        }
        return out;
    }

    /**
     * Draws text cleanly with current FontManager style, optional soft Gaussian halo behind it,
     * and gradient / moving gradient support. Never renders ugly pixel-offset double vision.
     */
    public static void drawStyledText(Minecraft client, String text, float x, float y, float width,
                                      int fallbackRgb, float alpha, boolean shadow,
                                      float glowAmount, float phaseSeed) {
        if (text == null || text.isEmpty()) return;
        int baseA = Math.round(alpha * 255.0f);
        if (baseA <= 6) return;

        if (glowAmount > 0.01f && LyricsConfig.glowEnabled) {
            int centerRgb = resolveColorRgb(fallbackRgb, 0.5f, phaseSeed);
            int startRgb = resolveColorRgb(fallbackRgb, 0.0f, phaseSeed);
            int endRgb = resolveColorRgb(fallbackRgb, 1.0f, phaseSeed);
            drawSoftGlowCapsule(x, y, width, FontManager.LOGICAL_HEIGHT, startRgb, centerRgb, endRgb,
                    alpha * glowAmount * 0.58f, glowAmount);
        }

        if (LyricsConfig.gradientEnabled) {
            int[] cols = buildCharColors(text, fallbackRgb, alpha, phaseSeed, 0.0f);
            FontManager.drawPerCharColored(client, text, x, y, cols, shadow);
        } else {
            int argb = (baseA << 24) | (fallbackRgb & 0x00FFFFFF);
            FontManager.drawSolid(client, text, x, y, argb, shadow);
        }
    }

    /**
     * Draws progressively swept text (used in Apple Style and Sky lyrics) with dimmed base layer
     * topped by a bright sub-glyph progressive fill and soft emissive bloom.
     */
    public static void drawWipedStyledText(Minecraft client, String text, float x, float y, float width,
                                           int baseRgb, float baseAlpha, int sungRgb, float sungAlpha,
                                           float progress01, boolean shadow, float glowAmount, float phaseSeed) {
        if (text == null || text.isEmpty()) return;
        float clampedProg = Math.max(0.0f, Math.min(1.0f, progress01));

        if (glowAmount > 0.01f && LyricsConfig.glowEnabled && sungAlpha > 0.03f) {
            int sRgb = resolveColorRgb(sungRgb, 0.15f, phaseSeed);
            int cRgb = resolveColorRgb(sungRgb, 0.5f, phaseSeed);
            int eRgb = resolveColorRgb(sungRgb, 0.85f, phaseSeed);
            float haloW = Math.max(8.0f, width * Math.max(0.25f, clampedProg));
            drawSoftGlowCapsule(x, y, haloW, FontManager.LOGICAL_HEIGHT, sRgb, cRgb, eRgb,
                    sungAlpha * glowAmount * 0.50f, glowAmount);
        }

        if (baseAlpha > 0.03f && clampedProg < 0.995f) {
            int[] baseCols = LyricsConfig.gradientEnabled
                    ? buildCharColors(text, baseRgb, baseAlpha, phaseSeed, 0.0f)
                    : fillArray(text.length(), (Math.round(baseAlpha * 255.0f) << 24) | (baseRgb & 0x00FFFFFF));
            FontManager.drawPerCharColored(client, text, x, y, baseCols, shadow);
        }

        if (sungAlpha > 0.03f && clampedProg > 0.005f) {
            int[] sungCols = LyricsConfig.gradientEnabled
                    ? buildCharColors(text, sungRgb, sungAlpha, phaseSeed, 0.12f)
                    : fillArray(text.length(), (Math.round(sungAlpha * 255.0f) << 24) | (sungRgb & 0x00FFFFFF));
            if (clampedProg >= 0.995f) {
                FontManager.drawPerCharColored(client, text, x, y, sungCols, shadow);
            } else {
                float clipW = width * clampedProg;
                FontManager.drawClippedPerChar(client, text, x, y, sungCols, clipW, 4.5f);
            }
        }
    }

    private static int[] fillArray(int len, int val) {
        int[] arr = new int[Math.max(1, len)];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = val;
        }
        return arr;
    }

    /**
     * Renders a genuinely smooth Gaussian pill halo behind a word/line (zero jagged bitmap shadows).
     */
    public static void drawSoftGlowCapsule(float x, float y, float w, float h,
                                           int startRgb, int midRgb, int endRgb,
                                           float alpha, float spreadMul) {
        if (alpha <= 0.02f || w <= 0.5f) return;
        ensureGlowTexture();

        int prevTex = ShaderCompat.getBoundTexture2D();
        GLX.activeTexture(GLX.GL_TEXTURE0);
        GlStateManager.enableTexture();
        GlStateManager.bindTexture(glowTexId);
        ShaderCompat.setAdditiveGlowMode(true);

        float spread = Math.max(0.75f, Math.min(2.0f, 0.85f + 0.35f * spreadMul));
        float padX = 7.5f * spread;
        float padY = 5.5f * spread;

        // Pass 1: wide smooth ambient aura; Pass 2: warmer inner core for depth
        emitCapsulePass(x, y, w, h, padX * 1.45f, padY * 1.45f, startRgb, midRgb, endRgb, alpha * 0.45f);
        emitCapsulePass(x, y, w, h, padX * 0.82f, padY * 0.85f, startRgb, midRgb, endRgb, alpha * 0.62f);

        ShaderCompat.setAdditiveGlowMode(false);
        ShaderCompat.restoreBoundTexture2D(prevTex);
    }

    private static void emitCapsulePass(float x, float y, float w, float h,
                                        float padX, float padY,
                                        int rgb0, int rgb1, int rgb2, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        if (a <= 4) return;

        float x0 = x - padX;
        float xL = x;
        float xR = x + w;
        float x3 = x + w + padX;
        float y0 = y - padY;
        float y1 = y + h + padY;

        int r0 = (rgb0 >> 16) & 0xFF, g0 = (rgb0 >> 8) & 0xFF, b0 = rgb0 & 0xFF;
        int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        // Left radial cap (u: 0.0 -> 0.5)
        buf.vertex(x0, y1, 0.0).texture(0.0, 1.0).color(r0, g0, b0, a).nextVertex();
        buf.vertex(xL, y1, 0.0).texture(0.5, 1.0).color(r0, g0, b0, a).nextVertex();
        buf.vertex(xL, y0, 0.0).texture(0.5, 0.0).color(r0, g0, b0, a).nextVertex();
        buf.vertex(x0, y0, 0.0).texture(0.0, 0.0).color(r0, g0, b0, a).nextVertex();

        // Center stretched Gaussian band (u: 0.5 -> 0.5, leftHalf & rightHalf blend through midRgb)
        float midX = (xL + xR) * 0.5f;
        buf.vertex(xL, y1, 0.0).texture(0.5, 1.0).color(r0, g0, b0, a).nextVertex();
        buf.vertex(midX, y1, 0.0).texture(0.5, 1.0).color(r1, g1, b1, a).nextVertex();
        buf.vertex(midX, y0, 0.0).texture(0.5, 0.0).color(r1, g1, b1, a).nextVertex();
        buf.vertex(xL, y0, 0.0).texture(0.5, 0.0).color(r0, g0, b0, a).nextVertex();

        buf.vertex(midX, y1, 0.0).texture(0.5, 1.0).color(r1, g1, b1, a).nextVertex();
        buf.vertex(xR, y1, 0.0).texture(0.5, 1.0).color(r2, g2, b2, a).nextVertex();
        buf.vertex(xR, y0, 0.0).texture(0.5, 0.0).color(r2, g2, b2, a).nextVertex();
        buf.vertex(midX, y0, 0.0).texture(0.5, 0.0).color(r1, g1, b1, a).nextVertex();

        // Right radial cap (u: 0.5 -> 1.0)
        buf.vertex(xR, y1, 0.0).texture(0.5, 1.0).color(r2, g2, b2, a).nextVertex();
        buf.vertex(x3, y1, 0.0).texture(1.0, 1.0).color(r2, g2, b2, a).nextVertex();
        buf.vertex(x3, y0, 0.0).texture(1.0, 0.0).color(r2, g2, b2, a).nextVertex();
        buf.vertex(xR, y0, 0.0).texture(0.5, 0.0).color(r2, g2, b2, a).nextVertex();

        tess.end();
    }

    /**
     * Draws a sleek rounded translucent backdrop card (for Apple Lyrics Style and classic HUD)
     * using a bound 1x1 white texture so shader packs never encounter untextured uniforms.
     */
    public static void drawRoundedBackdrop(float x, float y, float w, float h, float radius, int argb) {
        int a = (argb >>> 24) & 0xFF;
        if (a <= 2 || w <= 0f || h <= 0f) return;

        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        ensureWhiteTexture();
        int prevTex = ShaderCompat.getBoundTexture2D();

        float rad = Math.max(0.0f, Math.min(radius, Math.min(w, h) * 0.5f));
        GlStateManager.enableTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        GLX.activeTexture(GLX.GL_TEXTURE0);
        GlStateManager.bindTexture(whiteTexId);
        GlStateManager.color4f(1.0f, 1.0f, 1.0f, 1.0f);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // Center body rectangles
        buf.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        emitRectColor(buf, x + rad, y, x + w - rad, y + h, r, g, b, a);
        emitRectColor(buf, x, y + rad, x + rad, y + h - rad, r, g, b, a);
        emitRectColor(buf, x + w - rad, y + rad, x + w, y + h - rad, r, g, b, a);
        tess.end();

        // 4 smooth rounded corner fans
        if (rad > 0.5f) {
            emitCornerFan(x + rad, y + rad, rad, Math.PI, Math.PI * 1.5, r, g, b, a);
            emitCornerFan(x + w - rad, y + rad, rad, Math.PI * 1.5, Math.PI * 2.0, r, g, b, a);
            emitCornerFan(x + w - rad, y + h - rad, rad, 0.0, Math.PI * 0.5, r, g, b, a);
            emitCornerFan(x + rad, y + h - rad, rad, Math.PI * 0.5, Math.PI, r, g, b, a);
        }

        ShaderCompat.restoreBoundTexture2D(prevTex);
    }

    private static void emitRectColor(BufferBuilder buf, float x0, float y0, float x1, float y1, int r, int g, int b, int a) {
        buf.vertex(x0, y1, 0.0).texture(0.5, 0.5).color(r, g, b, a).nextVertex();
        buf.vertex(x1, y1, 0.0).texture(0.5, 0.5).color(r, g, b, a).nextVertex();
        buf.vertex(x1, y0, 0.0).texture(0.5, 0.5).color(r, g, b, a).nextVertex();
        buf.vertex(x0, y0, 0.0).texture(0.5, 0.5).color(r, g, b, a).nextVertex();
    }

    private static void emitCornerFan(float cx, float cy, float rad, double startRad, double endRad,
                                      int r, int g, int b, int a) {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX_COLOR);
        buf.vertex(cx, cy, 0.0).texture(0.5, 0.5).color(r, g, b, a).nextVertex();
        int segs = 12;
        for (int i = 0; i <= segs; i++) {
            double ang = startRad + (endRad - startRad) * (i / (double) segs);
            double px = cx + Math.cos(ang) * rad;
            double py = cy + Math.sin(ang) * rad;
            buf.vertex(px, py, 0.0).texture(0.5, 0.5).color(r, g, b, a).nextVertex();
        }
        tess.end();
    }

    private static void ensureWhiteTexture() {
        if (whiteTexId > 0) return;

        ByteBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        buf.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        buf.flip();

        int prevTex = ShaderCompat.getBoundTexture2D();
        GLX.activeTexture(GLX.GL_TEXTURE0);
        whiteTexId = GL11.glGenTextures();
        GlStateManager.bindTexture(whiteTexId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        ShaderCompat.restoreBoundTexture2D(prevTex);
    }

    private static void ensureGlowTexture() {
        if (glowTexId > 0) return;

        final int size = 64;
        ByteBuffer buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
        float half = (size - 1) * 0.5f;
        for (int y = 0; y < size; y++) {
            float dy = (y - half) / half;
            for (int x = 0; x < size; x++) {
                float dx = (x - half) / half;
                float r2 = dx * dx + dy * dy;
                float r = (float) Math.sqrt(r2);
                // Smooth window -> zero strictly at radius 1.0 with creamy Gaussian core
                float gVal = r >= 1.0f ? 0.0f : (float) (Math.exp(-3.4 * r2) * Math.pow(1.0 - r * r, 1.35));
                byte aByte = (byte) Math.max(0, Math.min(255, Math.round(gVal * 255.0f)));
                buf.put((byte) 255);
                buf.put((byte) 255);
                buf.put((byte) 255);
                buf.put(aByte);
            }
        }
        buf.flip();

        int prevTex = ShaderCompat.getBoundTexture2D();
        GLX.activeTexture(GLX.GL_TEXTURE0);
        glowTexId = GL11.glGenTextures();
        GlStateManager.bindTexture(glowTexId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        ShaderCompat.restoreBoundTexture2D(prevTex);
    }
}
