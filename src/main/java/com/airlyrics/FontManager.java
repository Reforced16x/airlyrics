package com.airlyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic anti-aliased TrueType glyph atlas and vanilla font dispatcher.
 * Supports switching seamlessly between vanilla "Minecraft" bitmap text and crisp
 * linear-filtered AWT font styles with multi-script glyph baking via glTexSubImage2D.
 */
public final class FontManager {
    private static final int ATLAS_SIZE = 1024;
    private static final int FONT_PX = 40;
    private static final int GLYPH_PAD = 4;
    public static final float LOGICAL_HEIGHT = 9.0f;

    private static Set<String> availableFamilies;
    private static final Map<Integer, FontAtlas> ATLASES = new HashMap<Integer, FontAtlas>();

    private FontManager() {
    }

    public static boolean isCustomFontActive() {
        return LyricsConfig.fontIndex > 0;
    }

    public static float getStringWidth(Minecraft client, String text) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        int idx = LyricsConfig.fontIndex;
        if (idx <= 0) {
            if (client != null && client.textRenderer != null) {
                return client.textRenderer.getWidth(text);
            }
            return text.length() * 6.0f;
        }
        FontAtlas atlas = getOrCreateAtlas(idx);
        return atlas.getStringWidth(text);
    }

    public static float getCharWidth(Minecraft client, char c) {
        int idx = LyricsConfig.fontIndex;
        if (idx <= 0) {
            if (client != null && client.textRenderer != null) {
                return client.textRenderer.getWidth(c);
            }
            return 6.0f;
        }
        FontAtlas atlas = getOrCreateAtlas(idx);
        Glyph g = atlas.getOrBakeGlyph(c);
        return g != null ? g.logicalAdvance : 0.0f;
    }

    public static void drawSolid(Minecraft client, String text, float x, float y, int argb, boolean shadow) {
        if (text == null || text.isEmpty()) return;
        int a = (argb >>> 24) & 0xFF;
        if (a <= 5) return;

        int idx = LyricsConfig.fontIndex;
        if (idx <= 0) {
            if (client != null && client.textRenderer != null) {
                client.textRenderer.draw(text, x, y, argb, shadow);
            }
            return;
        }

        FontAtlas atlas = getOrCreateAtlas(idx);
        if (shadow) {
            int shadowAlpha = Math.max(6, (int) (a * 0.45f));
            int shadowRgb = (((argb >> 16) & 0xFF) >> 2) << 16
                    | (((argb >> 8) & 0xFF) >> 2) << 8
                    | ((argb & 0xFF) >> 2);
            atlas.drawTextUniform(text, x + 0.65f, y + 0.65f, (shadowAlpha << 24) | shadowRgb);
        }
        atlas.drawTextUniform(text, x, y, argb);
    }

    /**
     * Draws a neutral dark shadow pass behind text so additive glow drawn on top
     * never gets punched through by dark drop-shadows.
     */
    public static void drawNeutralShadow(Minecraft client, String text, float x, float y, float alpha) {
        if (text == null || text.isEmpty()) return;
        int shadowAlpha = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 0.42f * 255.0f);
        if (shadowAlpha <= 5) return;

        int argb = (shadowAlpha << 24) | 0x0C1018;
        int idx = LyricsConfig.fontIndex;
        if (idx <= 0) {
            if (client != null && client.textRenderer != null) {
                client.textRenderer.draw(text, x + 0.75f, y + 0.75f, argb, false);
            }
            return;
        }
        FontAtlas atlas = getOrCreateAtlas(idx);
        atlas.drawTextUniform(text, x + 0.65f, y + 0.65f, argb);
    }

    /**
     * Draws a string with explicit per-character ARGB colors (for gradients and animations).
     * When a custom atlas is active, renders cleanly in batched GL quads with linear filtering.
     */
    public static void drawPerCharColored(Minecraft client, String text, float x, float y,
                                          int[] charColors, boolean shadow) {
        if (text == null || text.isEmpty()) return;
        int len = text.length();
        int idx = LyricsConfig.fontIndex;

        if (idx <= 0) {
            if (client == null || client.textRenderer == null) return;
            TextRenderer tr = client.textRenderer;
            float cx = x;
            for (int i = 0; i < len; i++) {
                char c = text.charAt(i);
                int col = charColors[Math.min(i, charColors.length - 1)];
                int a = (col >>> 24) & 0xFF;
                if (a > 5) {
                    tr.draw(String.valueOf(c), cx, y, col, shadow);
                }
                cx += tr.getWidth(c);
            }
            return;
        }

        FontAtlas atlas = getOrCreateAtlas(idx);
        if (shadow) {
            int[] shadowColors = new int[len];
            for (int i = 0; i < len; i++) {
                int c = charColors[Math.min(i, charColors.length - 1)];
                int a = Math.max(0, Math.min(255, (int) (((c >>> 24) & 0xFF) * 0.44f)));
                if (a <= 5) {
                    shadowColors[i] = 0;
                } else {
                    int sr = ((c >> 16) & 0xFF) >> 2;
                    int sg = ((c >> 8) & 0xFF) >> 2;
                    int sb = (c & 0xFF) >> 2;
                    shadowColors[i] = (a << 24) | (sr << 16) | (sg << 8) | sb;
                }
            }
            atlas.drawTextPerChar(text, x + 0.65f, y + 0.65f, shadowColors, -1f, 0f, 1f);
        }
        atlas.drawTextPerChar(text, x, y, charColors, -1f, 0f, 1f);
    }

    /**
     * Draws a partial progressive fill up to clipRelX (with smooth feathered right edge)
     * in custom atlas mode, or per-character smooth clip on vanilla font.
     */
    public static void drawClippedPerChar(Minecraft client, String text, float x, float y,
                                          int[] charColors, float maxRelX, float featherPx) {
        if (text == null || text.isEmpty() || maxRelX <= 0.0f) return;
        int idx = LyricsConfig.fontIndex;
        if (idx <= 0) {
            if (client == null || client.textRenderer == null) return;
            TextRenderer tr = client.textRenderer;
            float cx = 0.0f;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                float cw = tr.getWidth(c);
                if (cx >= maxRelX) break;
                float frac = Math.max(0.0f, Math.min(1.0f, (maxRelX - cx) / Math.max(1.0f, cw)));
                int col = charColors[Math.min(i, charColors.length - 1)];
                int a = (col >>> 24) & 0xFF;
                int effA = Math.round(a * Math.min(1.0f, frac * 1.35f));
                if (effA > 5) {
                    tr.draw(String.valueOf(c), x + cx, y, (effA << 24) | (col & 0x00FFFFFF), false);
                }
                cx += cw;
            }
            return;
        }
        FontAtlas atlas = getOrCreateAtlas(idx);
        atlas.drawTextPerChar(text, x, y, charColors, maxRelX, featherPx, 1.0f);
    }

    private static synchronized FontAtlas getOrCreateAtlas(int index) {
        int clamped = Math.max(1, Math.min(LyricsConfig.FONT_OPTIONS.length - 1, index));
        FontAtlas atlas = ATLASES.get(clamped);
        if (atlas == null) {
            Font awt = resolveAwtFont(clamped);
            atlas = new FontAtlas(awt);
            ATLASES.put(clamped, atlas);
        }
        return atlas;
    }

    private static Font resolveAwtFont(int index) {
        if (availableFamilies == null) {
            availableFamilies = new HashSet<String>();
            try {
                for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)) {
                    availableFamilies.add(name.toLowerCase(Locale.ROOT));
                }
            } catch (Throwable ignored) {
            }
        }

        switch (index) {
            case 1: // Clean Sans
                return pickFont(Font.PLAIN, FONT_PX,
                        "SF Pro Display", "Segoe UI Variable Display", "Segoe UI", "Inter", "Helvetica Neue", "Arial", Font.SANS_SERIF);
            case 2: // Sans Bold
                return pickFont(Font.BOLD, FONT_PX,
                        "SF Pro Display", "Segoe UI Variable Display", "Segoe UI", "Inter", "Arial", Font.SANS_SERIF);
            case 3: // Rounded UI
                return pickFont(Font.BOLD, FONT_PX,
                        "Nunito", "Quicksand", "Varela Round", "Calibri", "Trebuchet MS", "Segoe UI", Font.SANS_SERIF);
            case 4: // Editorial Serif
                return pickFont(Font.PLAIN, FONT_PX,
                        "Georgia", "Palatino Linotype", "Times New Roman", Font.SERIF);
            case 5: // JetBrains / Mono
                return pickFont(Font.PLAIN, FONT_PX,
                        "JetBrains Mono", "Cascadia Mono", "Consolas", "DejaVu Sans Mono", Font.MONOSPACED);
            default:
                return new Font(Font.SANS_SERIF, Font.PLAIN, FONT_PX);
        }
    }

    private static Font pickFont(int style, int size, String... candidates) {
        for (String name : candidates) {
            if (availableFamilies.contains(name.toLowerCase(Locale.ROOT))) {
                return new Font(name, style, size);
            }
        }
        return new Font(candidates[candidates.length - 1], style, size);
    }

    // -------------------------------------------------------------------------
    // Dynamic Single-Font Multi-Page RGBA Atlas with glTexSubImage2D
    // -------------------------------------------------------------------------

    private static final class Glyph {
        final int pageTexId;
        final float u0, v0, u1, v1;
        final float logicalW, logicalH, logicalAdvance;
        final float drawOffsetY;

        Glyph(int pageTexId, float u0, float v0, float u1, float v1,
              float logicalW, float logicalH, float logicalAdvance, float drawOffsetY) {
            this.pageTexId = pageTexId;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.logicalW = logicalW;
            this.logicalH = logicalH;
            this.logicalAdvance = logicalAdvance;
            this.drawOffsetY = drawOffsetY;
        }
    }

    private static final class AtlasPage {
        final int texId;
        int curX = GLYPH_PAD;
        int curY = GLYPH_PAD;
        int rowHeight = 0;

        AtlasPage() {
            int prevTex = ShaderCompat.getBoundTexture2D();
            GLX.activeTexture(GLX.GL_TEXTURE0);
            this.texId = GL11.glGenTextures();
            GlStateManager.bindTexture(this.texId);

            ByteBuffer empty = ByteBuffer.allocateDirect(ATLAS_SIZE * ATLAS_SIZE * 4).order(ByteOrder.nativeOrder());
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, ATLAS_SIZE, ATLAS_SIZE, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, empty);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            ShaderCompat.restoreBoundTexture2D(prevTex);
        }
    }

    private static final class FontAtlas {
        private final Font primaryFont;
        private final Font fallbackFont;
        private final float scaleToLogical;
        private final float baselineLogical;
        private final Map<Character, Glyph> glyphs = new HashMap<Character, Glyph>();
        private final List<AtlasPage> pages = new ArrayList<AtlasPage>();

        private final BufferedImage scratchImg = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        private final ByteBuffer uploadBuf = ByteBuffer.allocateDirect(128 * 128 * 4).order(ByteOrder.nativeOrder());
        private final FontMetrics primaryMetrics;

        FontAtlas(Font primary) {
            this.primaryFont = primary;
            this.fallbackFont = new Font(Font.SANS_SERIF, primary.getStyle(), primary.getSize());

            Graphics2D g = scratchImg.createGraphics();
            configureHints(g);
            g.setFont(primary);
            this.primaryMetrics = g.getFontMetrics();
            g.dispose();

            float totalPx = Math.max(12.0f, primaryMetrics.getAscent() + primaryMetrics.getDescent());
            this.scaleToLogical = (LOGICAL_HEIGHT * 1.05f) / totalPx;
            this.baselineLogical = primaryMetrics.getAscent() * this.scaleToLogical - 0.5f;
        }

        float getStringWidth(String text) {
            float w = 0.0f;
            for (int i = 0; i < text.length(); i++) {
                Glyph g = getOrBakeGlyph(text.charAt(i));
                if (g != null) {
                    w += g.logicalAdvance;
                }
            }
            return w;
        }

        Glyph getOrBakeGlyph(char c) {
            Glyph existing = glyphs.get(c);
            if (existing != null) {
                return existing;
            }
            return bakeGlyph(c);
        }

        private Glyph bakeGlyph(char c) {
            if (Character.isISOControl(c)) {
                Glyph empty = new Glyph(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
                glyphs.put(c, empty);
                return empty;
            }

            Font fontToUse = primaryFont.canDisplay(c) ? primaryFont : fallbackFont;
            Graphics2D g = scratchImg.createGraphics();
            configureHints(g);
            g.setFont(fontToUse);
            FontMetrics fm = g.getFontMetrics();

            int charAdvPx = Math.max(1, fm.charWidth(c));
            int padW = Math.min(110, charAdvPx + 14);
            int padH = Math.min(110, fm.getHeight() + 10);

            if (Character.isWhitespace(c)) {
                g.dispose();
                Glyph space = new Glyph(0, 0f, 0f, 0f, 0f, 0f, 0f, charAdvPx * scaleToLogical, 0f);
                glyphs.put(c, space);
                return space;
            }

            g.setBackground(new Color(0, 0, 0, 0));
            g.clearRect(0, 0, padW, padH);
            g.setColor(Color.WHITE);
            int drawBaseY = fm.getAscent() + 4;
            g.drawString(String.valueOf(c), 4, drawBaseY);
            g.dispose();

            AtlasPage page = pages.isEmpty() ? addPage() : pages.get(pages.size() - 1);
            if (page.curX + padW + GLYPH_PAD >= ATLAS_SIZE) {
                page.curX = GLYPH_PAD;
                page.curY += page.rowHeight + GLYPH_PAD;
                page.rowHeight = 0;
            }
            if (page.curY + padH + GLYPH_PAD >= ATLAS_SIZE) {
                page = addPage();
            }

            int ax = page.curX;
            int ay = page.curY;
            page.curX += padW + GLYPH_PAD;
            if (padH > page.rowHeight) {
                page.rowHeight = padH;
            }

            uploadBuf.clear();
            for (int y = 0; y < padH; y++) {
                for (int x = 0; x < padW; x++) {
                    int argb = scratchImg.getRGB(x, y);
                    byte alpha = (byte) ((argb >>> 24) & 0xFF);
                    // Pure white RGB + alpha eliminates bilinear dark halos on bright/glowing backgrounds.
                    uploadBuf.put((byte) 255);
                    uploadBuf.put((byte) 255);
                    uploadBuf.put((byte) 255);
                    uploadBuf.put(alpha);
                }
            }
            uploadBuf.flip();

            int prevTex = ShaderCompat.getBoundTexture2D();
            GLX.activeTexture(GLX.GL_TEXTURE0);
            GlStateManager.bindTexture(page.texId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, ax, ay, padW, padH,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, uploadBuf);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            ShaderCompat.restoreBoundTexture2D(prevTex);

            float u0 = ax / (float) ATLAS_SIZE;
            float v0 = ay / (float) ATLAS_SIZE;
            float u1 = (ax + padW) / (float) ATLAS_SIZE;
            float v1 = (ay + padH) / (float) ATLAS_SIZE;
            float logW = padW * scaleToLogical;
            float logH = padH * scaleToLogical;
            float logAdv = charAdvPx * scaleToLogical;
            float offY = baselineLogical - drawBaseY * scaleToLogical;

            Glyph glyph = new Glyph(page.texId, u0, v0, u1, v1, logW, logH, logAdv, offY);
            glyphs.put(c, glyph);
            return glyph;
        }

        private AtlasPage addPage() {
            AtlasPage page = new AtlasPage();
            pages.add(page);
            return page;
        }

        void drawTextUniform(String text, float x, float y, int argb) {
            int[] arr = new int[] { argb };
            drawTextPerChar(text, x, y, arr, -1.0f, 0.0f, 1.0f);
        }

        void drawTextPerChar(String text, float x, float y, int[] colors,
                             float maxRelX, float featherPx, float globalAlphaMul) {
            if (text == null || text.isEmpty()) return;

            // Pre-bake all missing glyphs before opening any Tesselator batch
            for (int i = 0; i < text.length(); i++) {
                getOrBakeGlyph(text.charAt(i));
            }

            int prevTex = ShaderCompat.getBoundTexture2D();
            GLX.activeTexture(GLX.GL_TEXTURE0);
            GlStateManager.enableTexture();

            Tesselator tess = Tesselator.getInstance();
            BufferBuilder buf = tess.getBuffer();

            int boundPage = -1;
            boolean batchOpen = false;
            float cursorX = 0.0f;
            float glyphLeftShift = -4.0f * scaleToLogical;

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                Glyph g = getOrBakeGlyph(c);
                if (g == null) continue;

                float charRelStart = cursorX;
                cursorX += g.logicalAdvance;

                if (g.pageTexId <= 0 || g.logicalW <= 0.0f) {
                    continue;
                }

                float drawX = x + charRelStart + glyphLeftShift;
                float drawY = y + g.drawOffsetY;
                float x0 = drawX;
                float x1 = drawX + g.logicalW;
                float u0 = g.u0;
                float u1 = g.u1;

                int col = colors[Math.min(i, colors.length - 1)];
                int baseA = (col >>> 24) & 0xFF;
                int effA = Math.round(baseA * globalAlphaMul);
                if (effA <= 5) continue;
                int cr = (col >> 16) & 0xFF;
                int cg = (col >> 8) & 0xFF;
                int cb = col & 0xFF;

                // Optional smooth progressive sub-glyph wipe clipping + feathered leading edge
                if (maxRelX >= 0.0f) {
                    float absClip = x + maxRelX;
                    if (x0 >= absClip) {
                        break;
                    }
                    if (x1 > absClip) {
                        float t = Math.max(0.0f, Math.min(1.0f, (absClip - x0) / Math.max(0.001f, x1 - x0)));
                        x1 = absClip;
                        u1 = u0 + (u1 - u0) * t;
                    }
                    if (featherPx > 0.25f) {
                        float distFromEdge = (x + maxRelX) - (x0 + g.logicalW * 0.5f);
                        if (distFromEdge < featherPx) {
                            float f = Math.max(0.15f, Math.min(1.0f, distFromEdge / featherPx));
                            effA = Math.round(effA * f);
                            if (effA <= 5) continue;
                        }
                    }
                }

                if (g.pageTexId != boundPage) {
                    if (batchOpen) {
                        tess.end();
                        batchOpen = false;
                    }
                    boundPage = g.pageTexId;
                    GlStateManager.bindTexture(boundPage);
                    buf.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                    batchOpen = true;
                }

                float y0 = drawY;
                float y1 = drawY + g.logicalH;
                buf.vertex(x0, y1, 0.0).texture(u0, g.v1).color(cr, cg, cb, effA).nextVertex();
                buf.vertex(x1, y1, 0.0).texture(u1, g.v1).color(cr, cg, cb, effA).nextVertex();
                buf.vertex(x1, y0, 0.0).texture(u1, g.v0).color(cr, cg, cb, effA).nextVertex();
                buf.vertex(x0, y0, 0.0).texture(u0, g.v0).color(cr, cg, cb, effA).nextVertex();
            }

            if (batchOpen) {
                tess.end();
            }

            ShaderCompat.restoreBoundTexture2D(prevTex);
        }

        private static void configureHints(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        }
    }
}
