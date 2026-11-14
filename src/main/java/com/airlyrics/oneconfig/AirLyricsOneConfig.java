package com.airlyrics.oneconfig;

import com.airlyrics.LyricsConfig;
import com.airlyrics.LyricsFetcher;
import com.airlyrics.MediaTracker;
import org.polyfrost.compose.render.PolyColor;
import org.polyfrost.oneconfig.api.config.v1.Config;
import org.polyfrost.oneconfig.api.config.v1.Property;
import org.polyfrost.oneconfig.api.config.v1.annotations.Button;
import org.polyfrost.oneconfig.api.config.v1.annotations.Color;
import org.polyfrost.oneconfig.api.config.v1.annotations.DependsOn;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;

import java.util.Arrays;
import java.util.List;

/**
 * Full OneConfig v1 configuration screen integration for AirLyrics.
 */
public class AirLyricsOneConfig extends Config {
    public static final String MOD_ID = "airlyrics.json";

    @Switch(
        title = "Enable AirLyrics",
        description = "Master toggle for media tracking and lyrics rendering.",
        category = "General",
        subcategory = "Core"
    )
    public boolean enabled = true;

    @Switch(
        title = "Floating 3D World Lyrics",
        description = "Spawn projected billboarded lyrics around your camera in the world.",
        category = "General",
        subcategory = "Display"
    )
    @DependsOn("enabled")
    public boolean inWorld = true;

    @Switch(
        title = "On-Screen HUD Lyrics",
        description = "Display synced karaoke lyrics cleanly on the HUD overlay.",
        category = "General",
        subcategory = "Display"
    )
    @DependsOn("enabled")
    public boolean onScreen = true;

    @Slider(
        title = "Sync Offset (ms)",
        description = "Shift lyric timing earlier (-) or later (+) in milliseconds.",
        category = "Timing",
        subcategory = "Synchronization",
        min = -2000.0f,
        max = 2000.0f,
        step = 25.0f
    )
    public float syncOffsetMs = 0.0f;

    @Switch(
        title = "Estimate Word Timings",
        description = "Estimate syllable-paced karaoke word timings on plain line-synced LRC tracks.",
        category = "Timing",
        subcategory = "Synchronization"
    )
    public boolean estimateWordTimings = true;

    @Switch(
        title = "Bigger Important Lyrics",
        description = "Scale chorus hooks, vocal peaks, and emphasized lyrics larger.",
        category = "Appearance",
        subcategory = "Typography"
    )
    public boolean importantLyricsLarger = true;

    @Switch(
        title = "Shine & Glow Effect",
        description = "Render a soft emissive bloom and shimmer wave safe with shaders and dynamic lighting.",
        category = "Appearance",
        subcategory = "Glow & Opacity"
    )
    public boolean glowEnabled = true;

    @Slider(
        title = "Glow Intensity (%)",
        description = "Control the bloom and shimmer strength of the shine/glow effect.",
        category = "Appearance",
        subcategory = "Glow & Opacity",
        min = 25.0f,
        max = 200.0f,
        step = 5.0f
    )
    @DependsOn("glowEnabled")
    public float glowIntensityPercent = 100.0f;

    @Slider(
        title = "Opacity (%)",
        description = "Overall opacity of both floating world lyrics and on-screen HUD lyrics.",
        category = "Appearance",
        subcategory = "Glow & Opacity",
        min = 10.0f,
        max = 100.0f,
        step = 5.0f
    )
    public float opacityPercent = 100.0f;

    @Slider(
        title = "Global Text Scale (%)",
        description = "Base scale multiplier applied to rendered lyrics.",
        category = "Appearance",
        subcategory = "Typography",
        min = 50.0f,
        max = 250.0f,
        step = 5.0f
    )
    public float scalePercent = 100.0f;

    @Switch(
        title = "Word-by-Word World Mode",
        description = "Spawn each word cleanly on its beat instead of entire lines.",
        category = "World Layout",
        subcategory = "3D Floating Lyrics"
    )
    @DependsOn("inWorld")
    public boolean showWords = true;

    @Switch(
        title = "Scatter 3D Layout",
        description = "Scatter floating world words in space rather than forming a centered arc.",
        category = "World Layout",
        subcategory = "3D Floating Lyrics"
    )
    @DependsOn("inWorld")
    public boolean scatterLayout = true;

    @Switch(
        title = "Uppercase Text",
        description = "Display all lyrics in uppercase characters.",
        category = "Appearance",
        subcategory = "Typography"
    )
    public boolean uppercase = false;

    @Switch(
        title = "Rainbow Pastel Palette",
        description = "Cycle pastel colors per word rather than using a single primary color.",
        category = "Appearance",
        subcategory = "Color"
    )
    public boolean rainbowPastel = true;

    @Color(
        title = "Primary Lyric Color",
        description = "Highlight color used for HUD lyrics and fixed-color world lyrics (supports Chroma).",
        category = "Appearance",
        subcategory = "Color",
        alpha = false
    )
    public PolyColor primaryColor = new PolyColor(0xFF55FFFF, false, 1.0f);

    private transient boolean syncing = false;
    private transient AirLyricsHudWrapper hudWrapper;

    public AirLyricsOneConfig() {
        super(MOD_ID, "AirLyrics", Category.HUD);
    }

    public void attachHud(AirLyricsHudWrapper wrapper) {
        this.hudWrapper = wrapper;
    }

    @Button(
        title = "Refetch Current Lyrics",
        description = "Force a fresh lookup across lrc.red, lrclib.net, KuGou, and NetEase for the current track.",
        category = "General",
        subcategory = "Actions",
        text = "Refetch"
    )
    public void refetchLyrics() {
        if (!MediaTracker.currentTitle.isEmpty()) {
            LyricsFetcher.fetchAsync(
                MediaTracker.currentTitle,
                MediaTracker.currentArtist,
                MediaTracker.currentDurationMs,
                true
            );
        }
    }

    @Button(
        title = "Reset Settings",
        description = "Restore all AirLyrics settings and HUD position to defaults.",
        category = "General",
        subcategory = "Actions",
        text = "Reset"
    )
    public void resetSettings() {
        LyricsConfig.reset();
        syncFromCore();
        LyricsConfig.saveQuiet();
        save();
    }

    @Override
    protected void initialize(boolean isProfileSwitch) {
        super.initialize(isProfileSwitch);
        // Push loaded OneConfig values into core config and attach live listeners.
        pushToCore();

        List<String> keys = Arrays.asList(
            "enabled", "inWorld", "onScreen", "syncOffsetMs", "estimateWordTimings",
            "importantLyricsLarger", "glowEnabled", "glowIntensityPercent", "opacityPercent",
            "scalePercent", "showWords", "scatterLayout", "uppercase", "rainbowPastel", "primaryColor"
        );
        for (String key : keys) {
            addCallback(key, this::onOptionChanged);
        }
    }

    private void onOptionChanged() {
        if (syncing) return;
        pushToCore();
        LyricsConfig.saveQuiet();
        if (hudWrapper != null) {
            hudWrapper.syncHiddenFromCore();
        }
    }

    public void pushToCore() {
        LyricsConfig.enabled = enabled;
        LyricsConfig.inWorld = inWorld;
        LyricsConfig.onScreen = onScreen;
        LyricsConfig.syncOffsetMs = Math.round(syncOffsetMs);
        LyricsConfig.estimateWordTimings = estimateWordTimings;
        LyricsConfig.importantLyricsLarger = importantLyricsLarger;
        LyricsConfig.glowEnabled = glowEnabled;
        LyricsConfig.glowIntensityPercent = glowIntensityPercent;
        LyricsConfig.opacityPercent = opacityPercent;
        LyricsConfig.scalePercent = scalePercent;
        LyricsConfig.showWords = showWords;
        LyricsConfig.scatterLayout = scatterLayout;
        LyricsConfig.uppercase = uppercase;
        LyricsConfig.rainbowPastel = rainbowPastel;
        if (primaryColor != null) {
            LyricsConfig.primaryColor = primaryColor.getRawArgb() & 0x00FFFFFF;
        }
        LyricsConfig.clampAll();
    }

    @SuppressWarnings("unchecked")
    public void syncFromCore() {
        syncing = true;
        try {
            setProp("enabled", LyricsConfig.enabled);
            setProp("inWorld", LyricsConfig.inWorld);
            setProp("onScreen", LyricsConfig.onScreen);
            setProp("syncOffsetMs", (float) LyricsConfig.syncOffsetMs);
            setProp("estimateWordTimings", LyricsConfig.estimateWordTimings);
            setProp("importantLyricsLarger", LyricsConfig.importantLyricsLarger);
            setProp("glowEnabled", LyricsConfig.glowEnabled);
            setProp("glowIntensityPercent", LyricsConfig.glowIntensityPercent);
            setProp("opacityPercent", LyricsConfig.opacityPercent);
            setProp("scalePercent", LyricsConfig.scalePercent);
            setProp("showWords", LyricsConfig.showWords);
            setProp("scatterLayout", LyricsConfig.scatterLayout);
            setProp("uppercase", LyricsConfig.uppercase);
            setProp("rainbowPastel", LyricsConfig.rainbowPastel);

            enabled = LyricsConfig.enabled;
            inWorld = LyricsConfig.inWorld;
            onScreen = LyricsConfig.onScreen;
            syncOffsetMs = LyricsConfig.syncOffsetMs;
            estimateWordTimings = LyricsConfig.estimateWordTimings;
            importantLyricsLarger = LyricsConfig.importantLyricsLarger;
            glowEnabled = LyricsConfig.glowEnabled;
            glowIntensityPercent = LyricsConfig.glowIntensityPercent;
            opacityPercent = LyricsConfig.opacityPercent;
            scalePercent = LyricsConfig.scalePercent;
            showWords = LyricsConfig.showWords;
            scatterLayout = LyricsConfig.scatterLayout;
            uppercase = LyricsConfig.uppercase;
            rainbowPastel = LyricsConfig.rainbowPastel;

            int argb = 0xFF000000 | (LyricsConfig.primaryColor & 0x00FFFFFF);
            if (primaryColor == null) {
                primaryColor = new PolyColor(argb, false, 1.0f);
            } else {
                primaryColor.setArgb(argb);
            }
            Property<?> colorProp = getProperty("primaryColor");
            if (colorProp != null) {
                ((Property<Object>) colorProp).set(primaryColor);
            }
        } finally {
            syncing = false;
        }
    }

    @SuppressWarnings("unchecked")
    private void setProp(String name, Object value) {
        Property<?> prop = getProperty(name);
        if (prop != null) {
            ((Property<Object>) prop).set(value);
        }
    }

    public int getLivePrimaryColorRgb() {
        if (primaryColor != null) {
            return primaryColor.getArgb() & 0x00FFFFFF;
        }
        return LyricsConfig.primaryColor & 0x00FFFFFF;
    }
}
