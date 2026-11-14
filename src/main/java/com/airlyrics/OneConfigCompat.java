package com.airlyrics;

import java.lang.reflect.Method;

/**
 * Pure-reflection compatibility facade for optional OneConfig v1 integration.
 * Deliberately holds zero direct references to org.polyfrost.* classes so AirLyrics
 * loads and runs cleanly even when OneConfig is absent.
 */
public final class OneConfigCompat {
    private static boolean initialized = false;
    private static boolean available = false;
    private static Class<?> bridgeClass;

    private static Method openConfigMethod;
    private static Method isHudEditorOpenMethod;
    private static Method syncFromCoreMethod;
    private static Method resolvePrimaryColorMethod;
    private static Method resolveGradientStartMethod;
    private static Method resolveGradientEndMethod;
    private static Method notifyHudBoundsMethod;

    private OneConfigCompat() {
    }

    public static boolean isAvailable() {
        init();
        return available;
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            Class.forName("org.polyfrost.oneconfig.api.config.v1.Config", false, OneConfigCompat.class.getClassLoader());
            Class.forName("org.polyfrost.oneconfig.api.hud.v1.HudManager", false, OneConfigCompat.class.getClassLoader());

            bridgeClass = Class.forName("com.airlyrics.oneconfig.OneConfigBridge");
            Method initMethod = bridgeClass.getMethod("init");
            openConfigMethod = bridgeClass.getMethod("openConfigScreen");
            isHudEditorOpenMethod = bridgeClass.getMethod("isHudEditorOpen");
            syncFromCoreMethod = bridgeClass.getMethod("syncFromCore");
            resolvePrimaryColorMethod = bridgeClass.getMethod("resolvePrimaryColorRgb", int.class);
            resolveGradientStartMethod = bridgeClass.getMethod("resolveGradientStartRgb", int.class);
            resolveGradientEndMethod = bridgeClass.getMethod("resolveGradientEndRgb", int.class);
            notifyHudBoundsMethod = bridgeClass.getMethod("notifyHudBounds", float.class, float.class, float.class, float.class);

            initMethod.invoke(null);
            available = true;
        } catch (Throwable ignored) {
            available = false;
            bridgeClass = null;
        }
    }

    public static boolean openConfigScreen() {
        init();
        if (!available || openConfigMethod == null) {
            return false;
        }
        try {
            Object res = openConfigMethod.invoke(null);
            return Boolean.TRUE.equals(res);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isHudEditorOpen() {
        if (!available || isHudEditorOpenMethod == null) {
            return false;
        }
        try {
            Object res = isHudEditorOpenMethod.invoke(null);
            return Boolean.TRUE.equals(res);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void syncFromCore() {
        if (!available || syncFromCoreMethod == null) {
            return;
        }
        try {
            syncFromCoreMethod.invoke(null);
        } catch (Throwable ignored) {
        }
    }

    public static int resolvePrimaryColorRgb(int fallbackRgb) {
        if (!available || resolvePrimaryColorMethod == null) {
            return fallbackRgb & 0x00FFFFFF;
        }
        try {
            Object res = resolvePrimaryColorMethod.invoke(null, fallbackRgb);
            if (res instanceof Integer) {
                return ((Integer) res) & 0x00FFFFFF;
            }
        } catch (Throwable ignored) {
        }
        return fallbackRgb & 0x00FFFFFF;
    }

    public static int resolveGradientStartRgb(int fallbackRgb) {
        if (!available || resolveGradientStartMethod == null) {
            return fallbackRgb & 0x00FFFFFF;
        }
        try {
            Object res = resolveGradientStartMethod.invoke(null, fallbackRgb);
            if (res instanceof Integer) {
                return ((Integer) res) & 0x00FFFFFF;
            }
        } catch (Throwable ignored) {
        }
        return fallbackRgb & 0x00FFFFFF;
    }

    public static int resolveGradientEndRgb(int fallbackRgb) {
        if (!available || resolveGradientEndMethod == null) {
            return fallbackRgb & 0x00FFFFFF;
        }
        try {
            Object res = resolveGradientEndMethod.invoke(null, fallbackRgb);
            if (res instanceof Integer) {
                return ((Integer) res) & 0x00FFFFFF;
            }
        } catch (Throwable ignored) {
        }
        return fallbackRgb & 0x00FFFFFF;
    }

    public static void notifyHudBounds(float defaultX, float defaultY, float unscaledW, float unscaledH) {
        if (!available || notifyHudBoundsMethod == null) {
            return;
        }
        try {
            notifyHudBoundsMethod.invoke(null, defaultX, defaultY, unscaledW, unscaledH);
        } catch (Throwable ignored) {
        }
    }
}
