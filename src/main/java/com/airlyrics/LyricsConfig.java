package com.airlyrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class LyricsConfig {
    public static boolean enabled = true;
    public static boolean inWorld = true;
    public static boolean onScreen = true;
    public static int syncOffsetMs = 0;
    public static boolean showWords = true;
    public static boolean scatterLayout = true;
    public static boolean uppercase = false;
    public static float scalePercent = 100.0f;
    public static boolean rainbowPastel = true;
    public static int primaryColor = 0x55FFFF;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/airlyrics.json");

    public static void reset() {
        apply(new ConfigData());
    }

    public static void save() {
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null) parent.mkdirs();

            ConfigData data = snapshot();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception ignored) {}
    }

    public static void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null) apply(data);
        } catch (Exception ignored) {}
    }

    private static ConfigData snapshot() {
        ConfigData d = new ConfigData();
        d.enabled = enabled;
        d.inWorld = inWorld;
        d.onScreen = onScreen;
        d.syncOffsetMs = syncOffsetMs;
        d.showWords = showWords;
        d.scatterLayout = scatterLayout;
        d.uppercase = uppercase;
        d.scalePercent = scalePercent;
        d.rainbowPastel = rainbowPastel;
        d.primaryColor = primaryColor;
        return d;
    }

    private static void apply(ConfigData d) {
        enabled = d.enabled;
        inWorld = d.inWorld;
        onScreen = d.onScreen;
        syncOffsetMs = d.syncOffsetMs;
        showWords = d.showWords;
        scatterLayout = d.scatterLayout;
        uppercase = d.uppercase;
        scalePercent = d.scalePercent;
        rainbowPastel = d.rainbowPastel;
        primaryColor = d.primaryColor;
    }

    private static class ConfigData {
        boolean enabled = true;
        boolean inWorld = true;
        boolean onScreen = true;
        int syncOffsetMs = 0;
        boolean showWords = true;
        boolean scatterLayout = true;
        boolean uppercase = false;
        float scalePercent = 100.0f;
        boolean rainbowPastel = true;
        int primaryColor = 0x55FFFF;
    }
}
