package com.airlyrics.oneconfig;

import org.polyfrost.oneconfig.api.hud.v1.HudManager;
import org.polyfrost.oneconfig.api.ui.v1.OneConfigUI;

/**
 * Direct bridge between AirLyrics core and OneConfig v1 APIs.
 * Only loaded reflectively by {@link com.airlyrics.OneConfigCompat} when OneConfig is present.
 */
public final class OneConfigBridge {
    private static AirLyricsOneConfig config;
    private static AirLyricsHudWrapper hudWrapper;

    private OneConfigBridge() {
    }

    public static void init() {
        if (config != null) {
            return;
        }
        config = new AirLyricsOneConfig();
        hudWrapper = new AirLyricsHudWrapper(config);
        hudWrapper.register();
    }

    public static boolean openConfigScreen() {
        if (config == null) {
            init();
        }
        if (config == null) {
            return false;
        }
        OneConfigUI.open(config);
        return true;
    }

    public static boolean isHudEditorOpen() {
        try {
            return HudManager.INSTANCE.isEditorOpen();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void syncFromCore() {
        if (config != null) {
            config.syncFromCore();
        }
    }

    public static int resolvePrimaryColorRgb(int fallbackRgb) {
        if (config != null) {
            return config.getLivePrimaryColorRgb();
        }
        return fallbackRgb & 0x00FFFFFF;
    }

    public static int resolveGradientStartRgb(int fallbackRgb) {
        if (config != null) {
            return config.getLiveGradientStartRgb();
        }
        return fallbackRgb & 0x00FFFFFF;
    }

    public static int resolveGradientEndRgb(int fallbackRgb) {
        if (config != null) {
            return config.getLiveGradientEndRgb();
        }
        return fallbackRgb & 0x00FFFFFF;
    }

    public static void notifyHudBounds(float defaultX, float defaultY, float unscaledW, float unscaledH) {
        if (hudWrapper != null) {
            hudWrapper.updateCoreBounds(defaultX, defaultY, unscaledW, unscaledH);
        }
    }
}
