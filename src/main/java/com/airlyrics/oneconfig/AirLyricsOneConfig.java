package com.airlyrics.oneconfig;

import com.airlyrics.LyricsConfig;
import com.airlyrics.LyricsFetcher;
import org.polyfrost.compose.render.PolyColor;
import org.polyfrost.oneconfig.api.config.v1.Config;
import org.polyfrost.oneconfig.api.config.v1.annotations.Button;
import org.polyfrost.oneconfig.api.config.v1.annotations.Color;
import org.polyfrost.oneconfig.api.config.v1.annotations.Dropdown;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Native OneConfig v1 configuration profile for AirLyrics.
 * Keeps bi-directional synchronization with {@link LyricsConfig}.
 */
public final class AirLyricsOneConfig extends Config {
    private boolean syncing = false;
    private AirLyricsHudWrapper hudRef;

    @Switch(
        title = "Enable AirLyrics",
        description = "Toggle all floating world, sky, and HUD lyrics.",
        category = "Display",
        subcategory = "General"
    )
    public boolean enabled = LyricsConfig.enabled;

    @Switch(
        title = "In-World Floating Lyrics",
        description = "Spawn luminous lyric words floating in 3D space around your view.",
        category = "Display",
        subcategory = "General"
    )
    public boolean inWorld = LyricsConfig.inWorld;

    @Switch(
        title = "Sky Line-by-Line Lyrics",
        description = "Display a large line-by-line karaoke marquee high in the sky.",
        category = "Display",
        subcategory = "Sky Lyrics"
    )
    public boolean skyLyrics = LyricsConfig.skyLyrics;

    @Slider(
        title = "Sky Lyrics Scale (%)",
        description = "Overall size multiplier for sky marquee lyrics.",
        category = "Display",
        subcategory = "Sky Lyrics",
        min = 60.0f,
        max = 300.0f,
        step = 5.0f
    )
    public float skyScalePercent = LyricsConfig.skyScalePercent;

    @Slider(
        title = "Sky Elevation Angle (deg)",
        description = "Vertical pitch elevation in the sky dome.",
        category = "Display",
        subcategory = "Sky Lyrics",
        min = 15.0f,
        max = 72.0f,
        step = 1.0f
    )
    public float skyElevationDeg = LyricsConfig.skyElevationDeg;

    @Switch(
        title = "On-Screen HUD",
        description = "Show compact karaoke HUD on screen.",
        category = "Display",
        subcategory = "HUD Overlay"
    )
    public boolean onScreen = LyricsConfig.onScreen;

    @Switch(
        title = "Apple Lyrics Style",
        description = "Render sleek multi-line Apple Music card with spring scrolling & wipe.",
        category = "Display",
        subcategory = "HUD Overlay"
    )
    public boolean appleStyle = LyricsConfig.appleStyle;

    @Switch(
        title = "World Apple Lyrics Effect",
        description = "Apply Apple Lyrics progressive wipe, spring swell, and lift to floating in-world lyrics.",
        category = "Display",
        subcategory = "General"
    )
    public boolean worldAppleStyle = LyricsConfig.worldAppleStyle;

    @Switch(
        title = "Word-by-Word Popups",
        description = "Spawn individual word popups instead of whole lines in world.",
        category = "Display",
        subcategory = "General"
    )
    public boolean showWords = LyricsConfig.showWords;

    @Switch(
        title = "Floating Scatter Layout",
        description = "Scatter floating world words organically rather than flat rows.",
        category = "Display",
        subcategory = "General"
    )
    public boolean scatterLayout = LyricsConfig.scatterLayout;

    @Slider(
        title = "Scale (%)",
        description = "Overall scale of HUD & world lyrics.",
        category = "Display",
        subcategory = "HUD Overlay",
        min = 50.0f,
        max = 250.0f,
        step = 5.0f
    )
    public float scalePercent = LyricsConfig.scalePercent;

    @Slider(
        title = "Opacity (%)",
        description = "Overall transparency of rendered lyrics.",
        category = "Display",
        subcategory = "HUD Overlay",
        min = 10.0f,
        max = 100.0f,
        step = 5.0f
    )
    public float opacityPercent = LyricsConfig.opacityPercent;

    @Dropdown(
        title = "Font Style",
        description = "Choose Minecraft font or anti-aliased custom fonts across HUD, World & Sky.",
        category = "Typography & Glow",
        subcategory = "Typography",
        options = {"Minecraft", "Clean Sans", "Sans Bold", "Rounded UI", "Editorial Serif", "JetBrains / Mono"}
    )
    public int fontIndex = LyricsConfig.fontIndex;

    @Switch(
        title = "Uppercase Lyrics",
        description = "Render lyric text in uppercase.",
        category = "Typography & Glow",
        subcategory = "Typography"
    )
    public boolean uppercase = LyricsConfig.uppercase;

    @Switch(
        title = "Luminous Glow",
        description = "Soft Gaussian glow halo compatible with shaders.",
        category = "Typography & Glow",
        subcategory = "Glow & Colors"
    )
    public boolean glowEnabled = LyricsConfig.glowEnabled;

    @Slider(
        title = "Glow Intensity (%)",
        description = "Strength and spread of the emissive halo.",
        category = "Typography & Glow",
        subcategory = "Glow & Colors",
        min = 25.0f,
        max = 200.0f,
        step = 5.0f
    )
    public float glowIntensityPercent = LyricsConfig.glowIntensityPercent;

    @Switch(
        title = "Song Thumbnail Gradient",
        description = "Automatically extract a text color gradient from the song's cover art.",
        category = "Typography & Glow",
        subcategory = "Gradients"
    )
    public boolean thumbnailGradient = LyricsConfig.thumbnailGradient;

    @Switch(
        title = "Gradient Colors",
        description = "Blend per-character gradient colors across words and lines.",
        category = "Typography & Glow",
        subcategory = "Gradients"
    )
    public boolean gradientEnabled = LyricsConfig.gradientEnabled;

    @Switch(
        title = "Moving Gradient Wave",
        description = "Animate gradient phase smoothly over time.",
        category = "Typography & Glow",
        subcategory = "Gradients"
    )
    public boolean movingGradient = LyricsConfig.movingGradient;

    @Color(
        title = "Gradient Start Color",
        description = "First stop of the lyric gradient.",
        category = "Typography & Glow",
        subcategory = "Gradients",
        alpha = false
    )
    public PolyColor gradientColorStart = new PolyColor(0xFF000000 | (LyricsConfig.gradientColorStart & 0x00FFFFFF));

    @Color(
        title = "Gradient End Color",
        description = "Second stop of the lyric gradient.",
        category = "Typography & Glow",
        subcategory = "Gradients",
        alpha = false
    )
    public PolyColor gradientColorEnd = new PolyColor(0xFF000000 | (LyricsConfig.gradientColorEnd & 0x00FFFFFF));

    @Slider(
        title = "Gradient Speed (%)",
        description = "Speed of the moving gradient animation.",
        category = "Typography & Glow",
        subcategory = "Gradients",
        min = 20.0f,
        max = 300.0f,
        step = 10.0f
    )
    public float gradientSpeedPercent = LyricsConfig.gradientSpeedPercent;

    @Switch(
        title = "Rainbow Pastel World Words",
        description = "Cycle soft pastel hues across floating world words when gradients are off.",
        category = "Typography & Glow",
        subcategory = "Glow & Colors"
    )
    public boolean rainbowPastel = LyricsConfig.rainbowPastel;

    @Color(
        title = "Primary Color",
        description = "Highlight accent color for active lyrics.",
        category = "Typography & Glow",
        subcategory = "Glow & Colors",
        alpha = false
    )
    public PolyColor primaryColor = new PolyColor(0xFF000000 | (LyricsConfig.primaryColor & 0x00FFFFFF));

    @Slider(
        title = "Sync Offset (ms)",
        description = "Manual audio/lyrics timing adjustment in milliseconds.",
        category = "Effects & Sync",
        subcategory = "Timing & Sync",
        min = -2000.0f,
        max = 2000.0f,
        step = 25.0f
    )
    public float syncOffsetMs = LyricsConfig.syncOffsetMs;

    @Switch(
        title = "Estimate Word Timings",
        description = "Subdivide line-synced lyrics smoothly into progressive word timings.",
        category = "Effects & Sync",
        subcategory = "Timing & Sync"
    )
    public boolean estimateWordTimings = LyricsConfig.estimateWordTimings;

    @Button(
        title = "Reset HUD Position",
        description = "Snap HUD card back to default bottom-left placement.",
        category = "Effects & Sync",
        subcategory = "Maintenance",
        text = "Reset HUD"
    )
    public void resetHudAction() {
        LyricsConfig.hudX = -1.0f;
        LyricsConfig.hudY = -1.0f;
        LyricsConfig.save();
    }

    @Button(
        title = "Reset All Settings",
        description = "Restore all AirLyrics settings back to defaults.",
        category = "Effects & Sync",
        subcategory = "Maintenance",
        text = "Reset All"
    )
    public void resetAllAction() {
        LyricsConfig.reset();
        LyricsConfig.saveQuiet();
        syncFromCore();
    }

    public AirLyricsOneConfig() {
        super("airlyrics.toml", "AirLyrics", Category.VISUALS);

        Path existing = Paths.get("config/oneconfig/airlyrics.toml");
        boolean hasExistingProfile = Files.exists(existing);

        initialize(false);
        registerListeners();

        if (hasExistingProfile) {
            pushToCore();
        } else {
            syncFromCore();
        }
    }

    private void registerListeners() {
        String[] watched = {
            "enabled", "inWorld", "skyLyrics", "skyScalePercent", "skyElevationDeg",
            "onScreen", "appleStyle", "worldAppleStyle", "showWords", "scatterLayout",
            "scalePercent", "opacityPercent", "fontIndex", "uppercase",
            "glowEnabled", "glowIntensityPercent", "thumbnailGradient", "gradientEnabled", "movingGradient",
            "gradientColorStart", "gradientColorEnd", "gradientSpeedPercent",
            "rainbowPastel", "primaryColor", "syncOffsetMs", "estimateWordTimings"
        };
        for (int i = 0; i < watched.length; i++) {
            try {
                addCallback(watched[i], new Runnable() {
                    @Override
                    public void run() {
                        pushToCore();
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    public void attachHud(AirLyricsHudWrapper hud) {
        this.hudRef = hud;
    }

    public void pushToCore() {
        if (syncing) {
            return;
        }
        syncing = true;
        boolean prevEst = LyricsConfig.estimateWordTimings;
        try {
            LyricsConfig.enabled = enabled;
            LyricsConfig.inWorld = inWorld;
            LyricsConfig.skyLyrics = skyLyrics;
            LyricsConfig.skyScalePercent = skyScalePercent;
            LyricsConfig.skyElevationDeg = skyElevationDeg;
            LyricsConfig.onScreen = onScreen;
            LyricsConfig.appleStyle = appleStyle;
            LyricsConfig.worldAppleStyle = worldAppleStyle;
            LyricsConfig.showWords = showWords;
            LyricsConfig.scatterLayout = scatterLayout;
            LyricsConfig.scalePercent = scalePercent;
            LyricsConfig.opacityPercent = opacityPercent;
            LyricsConfig.fontIndex = fontIndex;
            LyricsConfig.uppercase = uppercase;
            LyricsConfig.glowEnabled = glowEnabled;
            LyricsConfig.glowIntensityPercent = glowIntensityPercent;
            LyricsConfig.thumbnailGradient = thumbnailGradient;
            LyricsConfig.gradientEnabled = gradientEnabled;
            LyricsConfig.movingGradient = movingGradient;
            if (gradientColorStart != null) {
                LyricsConfig.gradientColorStart = gradientColorStart.getRawArgb() & 0x00FFFFFF;
            }
            if (gradientColorEnd != null) {
                LyricsConfig.gradientColorEnd = gradientColorEnd.getRawArgb() & 0x00FFFFFF;
            }
            LyricsConfig.gradientSpeedPercent = gradientSpeedPercent;
            LyricsConfig.rainbowPastel = rainbowPastel;
            if (primaryColor != null) {
                LyricsConfig.primaryColor = primaryColor.getRawArgb() & 0x00FFFFFF;
            }
            LyricsConfig.syncOffsetMs = Math.round(syncOffsetMs);
            LyricsConfig.estimateWordTimings = estimateWordTimings;

            LyricsConfig.clampAll();
            LyricsConfig.saveQuiet();
            save();
            if (prevEst != LyricsConfig.estimateWordTimings) {
                LyricsFetcher.reapplyWordTimings();
            }
        } finally {
            syncing = false;
        }
    }

    public void syncFromCore() {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            LyricsConfig.clampAll();
            enabled = LyricsConfig.enabled;
            inWorld = LyricsConfig.inWorld;
            skyLyrics = LyricsConfig.skyLyrics;
            skyScalePercent = LyricsConfig.skyScalePercent;
            skyElevationDeg = LyricsConfig.skyElevationDeg;
            onScreen = LyricsConfig.onScreen;
            appleStyle = LyricsConfig.appleStyle;
            worldAppleStyle = LyricsConfig.worldAppleStyle;
            showWords = LyricsConfig.showWords;
            scatterLayout = LyricsConfig.scatterLayout;
            scalePercent = LyricsConfig.scalePercent;
            opacityPercent = LyricsConfig.opacityPercent;
            fontIndex = LyricsConfig.fontIndex;
            uppercase = LyricsConfig.uppercase;
            glowEnabled = LyricsConfig.glowEnabled;
            glowIntensityPercent = LyricsConfig.glowIntensityPercent;
            thumbnailGradient = LyricsConfig.thumbnailGradient;
            gradientEnabled = LyricsConfig.gradientEnabled;
            movingGradient = LyricsConfig.movingGradient;
            gradientColorStart = new PolyColor(0xFF000000 | (LyricsConfig.gradientColorStart & 0x00FFFFFF));
            gradientColorEnd = new PolyColor(0xFF000000 | (LyricsConfig.gradientColorEnd & 0x00FFFFFF));
            gradientSpeedPercent = LyricsConfig.gradientSpeedPercent;
            rainbowPastel = LyricsConfig.rainbowPastel;
            primaryColor = new PolyColor(0xFF000000 | (LyricsConfig.primaryColor & 0x00FFFFFF));
            syncOffsetMs = LyricsConfig.syncOffsetMs;
            estimateWordTimings = LyricsConfig.estimateWordTimings;
            save();
        } finally {
            syncing = false;
        }
    }

    public int getLivePrimaryColorRgb() {
        if (primaryColor != null) {
            return primaryColor.getArgb() & 0x00FFFFFF;
        }
        return LyricsConfig.primaryColor & 0x00FFFFFF;
    }

    public int getLiveGradientStartRgb() {
        if (gradientColorStart != null) {
            return gradientColorStart.getArgb() & 0x00FFFFFF;
        }
        return LyricsConfig.gradientColorStart & 0x00FFFFFF;
    }

    public int getLiveGradientEndRgb() {
        if (gradientColorEnd != null) {
            return gradientColorEnd.getArgb() & 0x00FFFFFF;
        }
        return LyricsConfig.gradientColorEnd & 0x00FFFFFF;
    }

    public org.polyfrost.oneconfig.api.config.v1.Property<?> findProperty(String name) {
        return getProperty(name);
    }

    public AirLyricsHudWrapper getHudRef() {
        return hudRef;
    }
}
