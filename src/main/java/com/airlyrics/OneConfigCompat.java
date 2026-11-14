package com.airlyrics;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Reflection facade over optional OneConfig v1 integration so AirLyrics runs cleanly
 * both with Ornithe 1.8.9 OneConfig installed and standalone without it.
 */
public final class OneConfigCompat {
    private static boolean checked = false;
    private static boolean available = false;
    private static boolean initialized = false;

    private static Method initMethod;
    private static Method openConfigMethod;
    private static Method isHudEditorOpenMethod;
    private static Method syncFromCoreMethod;
    private static Method getPrimaryColorMethod;
    private static Method notifyHudBoundsMethod;

    private OneConfigCompat() {
    }

    public static boolean isAvailable() {
        if (!checked) {
            checked = true;
            FabricLoader loader = FabricLoader.getInstance();
            if (loader.isModLoaded("oneconfigv1") || loader.isModLoaded("oneconfig")) {
                try {
                    Class<?> bridge = Class.forName("com.airlyrics.oneconfig.OneConfigBridge");
                    initMethod = bridge.getMethod("init");
                    openConfigMethod = bridge.getMethod("openConfig");
                    isHudEditorOpenMethod = bridge.getMethod("isHudEditorOpen");
                    syncFromCoreMethod = bridge.getMethod("syncFromCore");
                    getPrimaryColorMethod = bridge.getMethod("getPrimaryColorRgb");
                    notifyHudBoundsMethod = bridge.getMethod("notifyHudBounds", float.class, float.class, float.class, float.class);
                    available = true;
                } catch (Throwable t) {
                    System.err.println("[AirLyrics] OneConfig detected but bridge failed to load: " + t);
                    available = false;
                }
            }
        }
        return available;
    }

    public static void init() {
        if (initialized || !isAvailable()) return;
        initialized = true;
        try {
            initMethod.invoke(null);
        } catch (Throwable t) {
            System.err.println("[AirLyrics] Failed to initialize OneConfig integration: " + t);
            available = false;
        }
    }

    public static boolean openConfigScreen() {
        init();
        if (!available || openConfigMethod == null) return false;
        try {
            return Boolean.TRUE.equals(openConfigMethod.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isHudEditorOpen() {
        if (!available || isHudEditorOpenMethod == null) return false;
        try {
            return Boolean.TRUE.equals(isHudEditorOpenMethod.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void syncFromCore() {
        if (!initialized || !available || syncFromCoreMethod == null) return;
        try {
            syncFromCoreMethod.invoke(null);
        } catch (Throwable ignored) {
        }
    }

    public static int resolvePrimaryColorRgb(int fallbackRgb) {
        if (!initialized || !available || getPrimaryColorMethod == null) return fallbackRgb;
        try {
            Object val = getPrimaryColorMethod.invoke(null);
            if (val instanceof Integer) {
                return (Integer) val;
            }
        } catch (Throwable ignored) {
        }
        return fallbackRgb;
    }

    public static void notifyHudBounds(float defaultX, float defaultY, float unscaledW, float unscaledH) {
        if (!initialized || !available || notifyHudBoundsMethod == null) return;
        try {
            notifyHudBoundsMethod.invoke(null, defaultX, defaultY, unscaledW, unscaledH);
        } catch (Throwable ignored) {
        }
    }
}
