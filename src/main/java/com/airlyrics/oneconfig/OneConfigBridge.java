package com.airlyrics.oneconfig;

import org.polyfrost.oneconfig.api.hud.v1.HudManager;
import org.polyfrost.oneconfig.api.ui.v1.OneConfigUI;
import org.polyfrost.oneconfig.api.ui.v1.page.OneConfigPages;

/**
 * Runtime entrypoints invoked reflectively from OneConfigCompat when OneConfig v1 is loaded.
 */
public final class OneConfigBridge {
    private static AirLyricsOneConfig config;
    private static AirLyricsHudWrapper hudWrapper;
    private static boolean initialized = false;

    private OneConfigBridge() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        config = new AirLyricsOneConfig();
        config.preload();

        hudWrapper = new AirLyricsHudWrapper(config);
        config.attachHud(hudWrapper);
        hudWrapper.register();
    }

    public static boolean openConfig() {
        init();
        OneConfigUI.open(OneConfigPages.modConfig(AirLyricsOneConfig.MOD_ID));
        return true;
    }

    public static boolean isHudEditorOpen() {
        return HudManager.INSTANCE.isEditorOpen();
    }

    public static void syncFromCore() {
        if (config != null) {
            config.syncFromCore();
        }
    }

    public static int getPrimaryColorRgb() {
        if (config != null) {
            return config.getLivePrimaryColorRgb();
        }
        return com.airlyrics.LyricsConfig.primaryColor;
    }

    public static void notifyHudBounds(float defaultX, float defaultY, float unscaledW, float unscaledH) {
        if (hudWrapper != null) {
            hudWrapper.updateBounds(defaultX, defaultY, unscaledW, unscaledH);
        }
    }
}
