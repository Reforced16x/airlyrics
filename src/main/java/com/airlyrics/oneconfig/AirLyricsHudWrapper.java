package com.airlyrics.oneconfig;

import com.airlyrics.LyricsConfig;
import org.polyfrost.oneconfig.api.config.v1.Property;
import org.polyfrost.oneconfig.api.hud.v1.HudResize;
import org.polyfrost.oneconfig.api.hud.v1.OneConfigHudWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Integrates AirLyrics' on-screen HUD overlay with OneConfig's visual HUD Editor
 * so users can drag, scale, and toggle HUD lyrics seamlessly.
 */
public class AirLyricsHudWrapper implements OneConfigHudWrapper {
    private final AirLyricsOneConfig config;

    private String id = "airlyrics_hud";
    private String name = "AirLyrics HUD";

    private float unscaledWidth = 220.0f;
    private float unscaledHeight = 28.0f;
    private float defaultX = 16.0f;
    private float defaultY = 180.0f;
    private boolean initializedBounds = false;

    public AirLyricsHudWrapper(AirLyricsOneConfig config) {
        this.config = config;
    }

    public void updateBounds(float defaultX, float defaultY, float unscaledW, float unscaledH) {
        this.defaultX = defaultX;
        this.defaultY = defaultY;
        this.unscaledWidth = Math.max(80.0f, unscaledW);
        this.unscaledHeight = Math.max(20.0f, unscaledH);
        this.initializedBounds = true;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getModId() {
        return AirLyricsOneConfig.MOD_ID;
    }

    @Override
    public float getX() {
        return LyricsConfig.hudX >= 0.0f ? LyricsConfig.hudX : defaultX;
    }

    @Override
    public void setX(float x) {
        LyricsConfig.hudX = Math.max(0.0f, x);
    }

    @Override
    public float getY() {
        return LyricsConfig.hudY >= 0.0f ? LyricsConfig.hudY : defaultY;
    }

    @Override
    public void setY(float y) {
        LyricsConfig.hudY = Math.max(0.0f, y);
    }

    @Override
    public float getScale() {
        return Math.max(0.5f, Math.min(2.5f, LyricsConfig.scalePercent / 100.0f));
    }

    @Override
    public void setScale(float scale) {
        float clamped = Math.max(0.5f, Math.min(2.5f, scale));
        LyricsConfig.scalePercent = clamped * 100.0f;
        config.syncFromCore();
    }

    @Override
    public float getScaledWidth() {
        return unscaledWidth * getScale();
    }

    @Override
    public void setScaledWidth(float width) {
        if (unscaledWidth > 1.0f && width > 1.0f) {
            setScale(width / unscaledWidth);
        }
    }

    @Override
    public float getScaledHeight() {
        return unscaledHeight * getScale();
    }

    @Override
    public void setScaledHeight(float height) {
        if (unscaledHeight > 1.0f && height > 1.0f) {
            setScale(height / unscaledHeight);
        }
    }

    @Override
    public boolean getHidden() {
        return !LyricsConfig.enabled || !LyricsConfig.onScreen;
    }

    @Override
    public void setHidden(boolean hidden) {
        LyricsConfig.onScreen = !hidden;
        if (!hidden && !LyricsConfig.enabled) {
            LyricsConfig.enabled = true;
        }
        config.syncFromCore();
        LyricsConfig.saveQuiet();
    }

    public void syncHiddenFromCore() {
        // Trigger HUD persistence when toggled from Config page.
        LyricsConfig.saveQuiet();
    }

    @Override
    public boolean getSupportsScale() {
        return true;
    }

    @Override
    public boolean getPlacementReady() {
        return initializedBounds || LyricsConfig.hudX >= 0.0f;
    }

    @Override
    public boolean getOwnsPlacement() {
        return true;
    }

    @Override
    public HudResize getResizeAxes() {
        return HudResize.Both;
    }

    @Override
    public List<Property<?>> linkedProperties() {
        List<Property<?>> list = new ArrayList<>();
        if (config.getTree() != null) {
            addIfPresent(list, "onScreen");
            addIfPresent(list, "scalePercent");
            addIfPresent(list, "opacityPercent");
            addIfPresent(list, "importantLyricsLarger");
            addIfPresent(list, "glowEnabled");
            addIfPresent(list, "glowIntensityPercent");
            addIfPresent(list, "syncOffsetMs");
        }
        return list;
    }

    private void addIfPresent(List<Property<?>> list, String name) {
        Property<?> p = config.getTree().getProp(name);
        if (p != null) {
            list.add(p);
        }
    }

    @Override
    public void onDragEnd() {
        LyricsConfig.saveQuiet();
        config.save();
    }

    @Override
    public void save() {
        LyricsConfig.saveQuiet();
        config.save();
    }
}
