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

    public static boolean isEffectiveGradientActive() {
        return (LyricsConfig.thumbnailGradient && CoverColorExtractor.hasCoverGradient())
                || LyricsConfig.gradientEnabled;
    }

    /**
     * Computes a character's RGB color, smoothly evaluating linear or moving gradients when enabled.
     */
    public static int resolveColorRgb(int fallbackRgb, float normalizedPos, float phaseSeed) {
        if (!isEffectiveGradientActive()) {
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
        if (LyricsConfig.thumbnailGradient && CoverColorExtractor.hasCoverGradient()) {
            return CoverColorExtractor.getStartRgb();
        }
        return OneConfigCompat.resolveGradientStartRgb(LyricsConfig.gradientColorStart);
    }

    public static int resolveGradientEndRgb() {
        if (LyricsConfig.thumbnailGradient && CoverColorExtractor.hasCoverGradient()) {
            return CoverColorExtractor.getEndRgb();
        }
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
     * Builds per-character ARGB array across text using pixel-consistent gradient spacing
     * so short words do not compress an entire gradient cycle.
     */
    public static int[] buildCharColors(Minecraft client, String text, int fallbackRgb, float alpha,
                                        float phaseSeed, float brightenMix, boolean allowGradient) {
        int n = text == null ? 0 : text.length();
        int[] out = new int[Math.max(1, n)];
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        int solidRgb = lerpRgb(fallbackRgb & 0x00FFFFFF, 0xFFFFFF, brightenMix);
        if (n == 0) {
            out[0] = (a << 24) | solidRgb;
            return out;
        }
        if (!allowGradient || !isEffectiveGradientActive()) {
            int argb = (a << 24) | solidRgb;
            for (int i = 0; i < n; i++) {
                out[i] = argb;
            }
            return out;
        }

        float cursorX = 0.0f;
        for (int i = 0; i < n; i++) {
            float cw = FontManager.getCharWidth(client, text.charAt(i));
            float u = (cursorX + cw * 0.5f) / 68.0f;
            int rgb = resolveColorRgb(fallbackRgb, u, phaseSeed);
            if (brightenMix > 0.001f) {
                rgb = lerpRgb(rgb, 0xFFFFFF, brightenMix);
            }
            out[i] = (a << 24) | rgb;
            cursorX += cw;
        }
        return out;
    }

    /**
     * Draws text cleanly with current FontManager style, neutral shadow behind glow,
     * per-character Gaussian halo matched to gradient colors, and foreground glyphs.
     */
    public static void drawStyledText(Minecraft client, String text, float x, float y, float width,
                                      int fallbackRgb, float alpha, boolean shadow,
                                      float glowAmount, float phaseSeed) {
        drawStyledText(client, text, x, y, width, fallbackRgb, alpha, shadow, glowAmount, phaseSeed, true);
    }

    public static void drawStyledText(Minecraft client, String text, float x, float y, float width,
                                      int fallbackRgb, float alpha, boolean shadow,
                                      float glowAmount, float phaseSeed, boolean allowGradient) {
        if (text == null || text.isEmpty()) return;
        int baseA = Math.round(alpha * 255.0f);
        if (baseA <= 6) return;

        int[] cols = buildCharColors(client, text, fallbackRgb, alpha, phaseSeed, 0.0f, allowGradient);

        if (shadow) {
            FontManager.drawNeutralShadow(client, text, x, y, alpha);
        }

        if (glowAmount > 0.01f && LyricsConfig.glowEnabled) {
            float spread = Math.max(0.75f, Math.min(1.75f, 0.85f + 0.30f * glowAmount));
            float glowAlpha = alpha * Math.min(1.0f, glowAmount * 0.62f);
            drawPerCharGlow(client, text, x, y, cols, -1.0f, glowAlpha, spread);
        }

        if (allowGradient && isEffectiveGradientActive()) {
            FontManager.drawPerCharColored(client, text, x, y, cols, false);
        } else {
            int argb = (baseA << 24) | (fallbackRgb & 0x00FFFFFF);
            FontManager.drawSolid(client, text, x, y, argb, false);
        }
    }

    /**
     * Draws progressively swept text (Apple Style / Sky / World) keeping unsung text neutral,
     * drawing neutral shadow behind per-character gradient glow, and sweeping sub-glyph wipe on top.
     */
    public static void drawWipedStyledText(Minecraft client, String text, float x, float y, float width,
                                           int baseRgb, float baseAlpha, int sungRgb, float sungAlpha,
                                           float progress01, boolean shadow, float glowAmount, float phaseSeed) {
        if (text == null || text.isEmpty()) return;
        float clampedProg = Math.max(0.0f, Math.min(1.0f, progress01));
        float clipW = width * clampedProg;

        int[] baseCols = buildCharColors(client, text, baseRgb, baseAlpha, phaseSeed, 0.0f, false);
        int[] sungCols = buildCharColors(client, text, sungRgb, sungAlpha, phaseSeed, 0.10f, true);

        if (shadow) {
            FontManager.drawNeutralShadow(client, text, x, y, Math.max(baseAlpha, sungAlpha));
        }

        if (glowAmount > 0.01f && LyricsConfig.glowEnabled && sungAlpha > 0.03f && clampedProg > 0.005f) {
            float spread = Math.max(0.75f, Math.min(1.75f, 0.85f + 0.30f * glowAmount));
            float glowAlpha = sungAlpha * Math.min(1.0f, glowAmount * 0.62f);
            drawPerCharGlow(client, text, x, y, sungCols, clipW, glowAlpha, spread);
        }

        if (baseAlpha > 0.03f && clampedProg < 0.995f) {
            FontManager.drawPerCharColored(client, text, x, y, baseCols, false);
        }

        if (sungAlpha > 0.03f && clampedProg > 0.005f) {
            if (clampedProg >= 0.995f) {
                FontManager.drawPerCharColored(client, text, x, y, sungCols, false);
            } else {
                FontManager.drawClippedPerChar(client, text, x, y, sungCols, clipW, 4.5f);
            }
        }
    }

    /**
     * Emits soft Gaussian halos individually per visible character matching exact per-glyph colors,
     * skipping spaces and preventing dark shadows from cutting into the glow.
     */
    public static void drawPerCharGlow(Minecraft client, String text, float x, float y,
                                       int[] charColors, float maxRelX, float glowAlpha, float glowSpread) {
        if (glowAlpha <= 0.02f || text == null || text.isEmpty()) return;
        ensureGlowTexture();

        int prevTex = ShaderCompat.getBoundTexture2D();
        GLX.activeTexture(GLX.GL_TEXTURE0);
        GlStateManager.enableTexture();
        GlStateManager.bindTexture(glowTexId);
        ShaderCompat.setAdditiveGlowMode(true);

        float outerPadX = 4.2f * glowSpread;
        float outerPadY = 3.8f * glowSpread;
        float innerPadX = 2.2f * glowSpread;
        float innerPadY = 2.2f * glowSpread;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        float cx = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            float cw = FontManager.getCharWidth(client, c);
            if (Character.isWhitespace(c)) {
                cx += cw;
                continue;
            }
            float weight = 1.0f;
            if (maxRelX >= 0.0f) {
                if (cx >= maxRelX) {
                    break;
                }
                weight = Math.max(0.0f, Math.min(1.0f, (maxRelX - cx) / Math.max(1.0f, cw)));
            }

            int col = charColors[Math.min(i, charColors.length - 1)];
            int rgb = lerpRgb(col & 0x00FFFFFF, 0xFFFFFF, 0.14f);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            int aOuter = Math.max(0, Math.min(255, Math.round(glowAlpha * 0.34f * weight * 255.0f)));
            int aInner = Math.max(0, Math.min(255, Math.round(glowAlpha * 0.48f * weight * 255.0f)));

            if (aOuter > 4) {
                emitTexQuad(buf, x + cx - outerPadX, y - outerPadY,
                        x + cx + cw + outerPadX, y + FontManager.LOGICAL_HEIGHT + outerPadY, r, g, b, aOuter);
            }
            if (aInner > 4) {
                emitTexQuad(buf, x + cx - innerPadX, y - innerPadY,
                        x + cx + cw + innerPadX, y + FontManager.LOGICAL_HEIGHT + innerPadY, r, g, b, aInner);
            }
            cx += cw;
        }

        tess.end();
        ShaderCompat.setAdditiveGlowMode(false);
        ShaderCompat.restoreBoundTexture2D(prevTex);
    }

    private static void emitTexQuad(BufferBuilder buf, float x0, float y0, float x1, float y1,
                                    int r, int g, int b, int a) {
        buf.vertex(x0, y1, 0.0).texture(0.0, 1.0).color(r, g, b, a).nextVertex();
        buf.vertex(x1, y1, 0.0).texture(1.0, 1.0).color(r, g, b, a).nextVertex();
        buf.vertex(x1, y0, 0.0).texture(1.0, 0.0).color(r, g, b, a).nextVertex();
        buf.vertex(x0, y0, 0.0).texture(0.0, 0.0).color(r, g, b, a).nextVertex();
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
