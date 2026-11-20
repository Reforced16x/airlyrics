package com.airlyrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class LyricsConfig {
    public static final String[] FONT_OPTIONS = {
        "Minecraft",
        "Clean Sans",
        "Sans Bold",
        "Rounded UI",
        "Editorial Serif",
        "JetBrains / Mono"
    };

    public static boolean enabled = true;
    public static boolean inWorld = true;
    public static boolean skyLyrics = false;
    public static boolean onScreen = true;
    public static boolean appleStyle = true;
    public static boolean worldAppleStyle = true;

    public static int syncOffsetMs = 0;
    public static boolean showWords = true;
    public static boolean scatterLayout = true;
    public static boolean uppercase = false;

    public static int fontIndex = 0;
    public static float scalePercent = 100.0f;
    public static float skyScalePercent = 145.0f;
    public static float skyElevationDeg = 38.0f;
    public static float opacityPercent = 100.0f;

    public static boolean glowEnabled = true;
    public static float glowIntensityPercent = 100.0f;

    public static boolean gradientEnabled = false;
    public static boolean movingGradient = true;
    public static int gradientColorStart = 0x66F3FF;
    public static int gradientColorEnd = 0xFF7BE5;
    public static float gradientSpeedPercent = 100.0f;

    public static boolean estimateWordTimings = true;
    public static boolean rainbowPastel = true;
    public static int primaryColor = 0x55FFFF;
    public static float hudX = -1.0f;
    public static float hudY = -1.0f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/airlyrics.json");

    public static void reset() {
        apply(new ConfigData());
    }

    public static String getFontOptionName() {
        int idx = Math.max(0, Math.min(FONT_OPTIONS.length - 1, fontIndex));
        return FONT_OPTIONS[idx];
    }

    public static void clampAll() {
        syncOffsetMs = Math.max(-2000, Math.min(2000, syncOffsetMs));
        fontIndex = Math.max(0, Math.min(FONT_OPTIONS.length - 1, fontIndex));
        scalePercent = Math.max(50.0f, Math.min(250.0f, scalePercent));
        skyScalePercent = Math.max(60.0f, Math.min(300.0f, skyScalePercent));
        skyElevationDeg = Math.max(15.0f, Math.min(72.0f, skyElevationDeg));
        opacityPercent = Math.max(10.0f, Math.min(100.0f, opacityPercent));
        glowIntensityPercent = Math.max(25.0f, Math.min(200.0f, glowIntensityPercent));
        gradientSpeedPercent = Math.max(20.0f, Math.min(300.0f, gradientSpeedPercent));
        gradientColorStart &= 0x00FFFFFF;
        gradientColorEnd &= 0x00FFFFFF;
        primaryColor &= 0x00FFFFFF;
    }

    public static float getOpacityAlpha() {
        return Math.max(0.10f, Math.min(1.0f, opacityPercent / 100.0f));
    }

    public static float getGlowFactor() {
        if (!glowEnabled) return 0.0f;
        return Math.max(0.0f, Math.min(2.0f, glowIntensityPercent / 100.0f));
    }

    public static void save() {
        clampAll();
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null) parent.mkdirs();

            ConfigData data = snapshot();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception ignored) {
        }
        OneConfigCompat.syncFromCore();
    }

    public static void saveQuiet() {
        clampAll();
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null) parent.mkdirs();

            ConfigData data = snapshot();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception ignored) {
        }
    }

    public static void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null) {
                apply(data);
            }
        } catch (Exception ignored) {
        }
        clampAll();
    }

    private static ConfigData snapshot() {
        ConfigData d = new ConfigData();
        d.enabled = enabled;
        d.inWorld = inWorld;
        d.skyLyrics = skyLyrics;
        d.onScreen = onScreen;
        d.appleStyle = appleStyle;
        d.worldAppleStyle = worldAppleStyle;
        d.syncOffsetMs = syncOffsetMs;
        d.showWords = showWords;
        d.scatterLayout = scatterLayout;
        d.uppercase = uppercase;
        d.fontIndex = fontIndex;
        d.scalePercent = scalePercent;
        d.skyScalePercent = skyScalePercent;
        d.skyElevationDeg = skyElevationDeg;
        d.opacityPercent = opacityPercent;
        d.glowEnabled = glowEnabled;
        d.glowIntensityPercent = glowIntensityPercent;
        d.gradientEnabled = gradientEnabled;
        d.movingGradient = movingGradient;
        d.gradientColorStart = gradientColorStart;
        d.gradientColorEnd = gradientColorEnd;
        d.gradientSpeedPercent = gradientSpeedPercent;
        d.estimateWordTimings = estimateWordTimings;
        d.rainbowPastel = rainbowPastel;
        d.primaryColor = primaryColor;
        d.hudX = hudX;
        d.hudY = hudY;
        return d;
    }

    private static void apply(ConfigData d) {
        enabled = d.enabled;
        inWorld = d.inWorld;
        skyLyrics = d.skyLyrics;
        onScreen = d.onScreen;
        appleStyle = d.appleStyle;
        worldAppleStyle = d.worldAppleStyle;
        syncOffsetMs = d.syncOffsetMs;
        showWords = d.showWords;
        scatterLayout = d.scatterLayout;
        uppercase = d.uppercase;
        fontIndex = d.fontIndex;
        scalePercent = d.scalePercent;
        skyScalePercent = d.skyScalePercent;
        skyElevationDeg = d.skyElevationDeg;
        opacityPercent = d.opacityPercent;
        glowEnabled = d.glowEnabled;
        glowIntensityPercent = d.glowIntensityPercent;
        gradientEnabled = d.gradientEnabled;
        movingGradient = d.movingGradient;
        gradientColorStart = d.gradientColorStart;
        gradientColorEnd = d.gradientColorEnd;
        gradientSpeedPercent = d.gradientSpeedPercent;
        estimateWordTimings = d.estimateWordTimings;
        rainbowPastel = d.rainbowPastel;
        primaryColor = d.primaryColor;
        hudX = d.hudX;
        hudY = d.hudY;
        clampAll();
    }

    private static class ConfigData {
        boolean enabled = true;
        boolean inWorld = true;
        boolean skyLyrics = false;
        boolean onScreen = true;
        boolean appleStyle = true;
        boolean worldAppleStyle = true;
        int syncOffsetMs = 0;
        boolean showWords = true;
        boolean scatterLayout = true;
        boolean uppercase = false;
        int fontIndex = 0;
        float scalePercent = 100.0f;
        float skyScalePercent = 145.0f;
        float skyElevationDeg = 38.0f;
        float opacityPercent = 100.0f;
        boolean glowEnabled = true;
        float glowIntensityPercent = 100.0f;
        boolean gradientEnabled = false;
        boolean movingGradient = true;
        int gradientColorStart = 0x66F3FF;
        int gradientColorEnd = 0xFF7BE5;
        float gradientSpeedPercent = 100.0f;
        boolean estimateWordTimings = true;
        boolean rainbowPastel = true;
        int primaryColor = 0x55FFFF;
        float hudX = -1.0f;
        float hudY = -1.0f;
    }
}
