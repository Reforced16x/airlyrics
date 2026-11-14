package com.airlyrics;

import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * OpenGL and shader/dynamic-lighting state guard for crisp translucent text and glow passes
 * alongside Cera / OptiFine shaderpacks and dynamic lighting mods.
 */
public final class ShaderCompat {
    private static boolean ceraChecked = false;
    private static Method ceraIsManaged;
    private static Method ceraIsShadowPass;
    private static Method ceraIsTextBatchPushed;
    private static Method ceraPushTextBatch;
    private static Method ceraPopTextBatch;

    private static final FloatBuffer LIGHTMAP_TEXCOORD_BUF =
        ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final IntBuffer PREV_ACTIVE_TEX =
        ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();

    private ShaderCompat() {
    }

    private static void initCera() {
        if (ceraChecked) return;
        ceraChecked = true;
        try {
            Class<?> cls = Class.forName("dev.rdh.cera.modules.shaders.ShaderRender");
            ceraIsManaged = cls.getMethod("isManaged");
            ceraIsShadowPass = cls.getMethod("isShadowPass");
            ceraIsTextBatchPushed = cls.getMethod("isTextBatchPushed");
            ceraPushTextBatch = cls.getMethod("pushTextBatch");
            ceraPopTextBatch = cls.getMethod("popTextBatch");
        } catch (Throwable ignored) {
        }
    }

    public static boolean isShadowPass() {
        initCera();
        if (ceraIsShadowPass == null) return false;
        try {
            return Boolean.TRUE.equals(ceraIsShadowPass.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isShaderManaged() {
        initCera();
        if (ceraIsManaged == null) return false;
        try {
            return Boolean.TRUE.equals(ceraIsManaged.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static SavedRenderState beginLuminousTextPass() {
        SavedRenderState state = new SavedRenderState();
        state.alphaTestWasEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        state.lightingWasEnabled = GL11.glIsEnabled(GL11.GL_LIGHTING);
        state.depthTestWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        state.fogWasEnabled = GL11.glIsEnabled(GL11.GL_FOG);
        state.blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);

        // Preserve current lightmap texcoord so dynamic lighting mods restore cleanly after full-bright text.
        PREV_ACTIVE_TEX.clear();
        GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE, PREV_ACTIVE_TEX);
        int prevActiveTex = PREV_ACTIVE_TEX.get(0);
        GLX.activeTexture(GLX.GL_TEXTURE1);
        LIGHTMAP_TEXCOORD_BUF.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_TEXTURE_COORDS, LIGHTMAP_TEXCOORD_BUF);
        state.prevLightmapU = LIGHTMAP_TEXCOORD_BUF.get(0);
        state.prevLightmapV = LIGHTMAP_TEXCOORD_BUF.get(1);
        GLX.activeTexture(prevActiveTex);

        // Push full-bright lightmap (240, 240) so dynamic lighting / ambient shading never darkens lyrics.
        GLX.multiTexCoord2f(GLX.GL_TEXTURE1, 240.0F, 240.0F);

        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableDepthTest();
        GlStateManager.enableAlphaTest();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003F);
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);

        // Batch Cera text shader stack across an entire lyric pass rather than switching per glyph/glow offset.
        initCera();
        if (ceraIsManaged != null && ceraIsTextBatchPushed != null && ceraPushTextBatch != null && ceraPopTextBatch != null) {
            try {
                if (Boolean.TRUE.equals(ceraIsManaged.invoke(null))
                    && !Boolean.TRUE.equals(ceraIsTextBatchPushed.invoke(null))) {
                    ceraPushTextBatch.invoke(null);
                    state.pushedCeraTextBatch = true;
                }
            } catch (Throwable ignored) {
            }
        }
        return state;
    }

    public static void setAdditiveGlowMode(boolean additive) {
        if (additive) {
            // Preserve destination FBO alpha (ZERO, ONE) so shaderpacks/bloom pipelines don't clip or wash out.
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003F);
        } else {
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003F);
        }
    }

    public static void endLuminousTextPass(SavedRenderState state) {
        if (state == null) return;
        if (state.pushedCeraTextBatch && ceraPopTextBatch != null) {
            try {
                ceraPopTextBatch.invoke(null);
            } catch (Throwable ignored) {
            }
        }

        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        if (!state.alphaTestWasEnabled) {
            GlStateManager.disableAlphaTest();
        }
        GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        if (!state.blendWasEnabled) {
            GlStateManager.disableBlend();
        }
        if (state.depthTestWasEnabled) {
            GlStateManager.enableDepthTest();
        }
        if (state.fogWasEnabled) {
            GlStateManager.enableFog();
        }
        if (state.lightingWasEnabled) {
            GlStateManager.enableLighting();
        }

        GLX.multiTexCoord2f(GLX.GL_TEXTURE1, state.prevLightmapU, state.prevLightmapV);
        GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static final class SavedRenderState {
        boolean alphaTestWasEnabled;
        boolean lightingWasEnabled;
        boolean depthTestWasEnabled;
        boolean fogWasEnabled;
        boolean blendWasEnabled;
        boolean pushedCeraTextBatch;
        float prevLightmapU;
        float prevLightmapV;
    }
}
