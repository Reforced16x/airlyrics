package com.airlyrics.oneconfig;

import com.airlyrics.LyricsConfig;
import org.polyfrost.oneconfig.api.config.v1.Property;
import org.polyfrost.oneconfig.api.hud.v1.HudResize;
import org.polyfrost.oneconfig.api.hud.v1.OneConfigHudWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposes AirLyrics' on-screen HUD card to OneConfig v1's visual HUD editor
 * so it can be dragged, scaled, toggled, and configured live.
 */
public final class AirLyricsHudWrapper implements OneConfigHudWrapper {
    private static final String[] LINKED_KEYS = {
        "enabled", "onScreen", "appleStyle", "fontIndex", "scalePercent", "opacityPercent",
        "glowEnabled", "glowIntensityPercent", "gradientEnabled", "movingGradient",
        "gradientColorStart", "gradientColorEnd", "gradientSpeedPercent",
        "rainbowPastel", "primaryColor", "uppercase"
    };

    private final AirLyricsOneConfig config;
    private String id = "airlyrics_hud";
    private String name = "AirLyrics HUD";

    private float defaultX = 16.0f;
    private float defaultY = 260.0f;
    private float unscaledW = 248.0f;
    private float unscaledH = 64.0f;

    public AirLyricsHudWrapper(AirLyricsOneConfig config) {
        this.config = config;
        this.config.attachHud(this);
    }

    public void updateCoreBounds(float defX, float defY, float rawW, float rawH) {
        this.defaultX = defX;
        this.defaultY = defY;
        this.unscaledW = Math.max(40.0f, rawW);
        this.unscaledH = Math.max(16.0f, rawH);
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
        return "airlyrics";
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
        float clamped = Math.max(50.0f, Math.min(250.0f, Math.round(scale * 20.0f) * 5.0f));
        LyricsConfig.scalePercent = clamped;
        config.scalePercent = clamped;
    }

    @Override
    public float getScaledWidth() {
        return unscaledW * getScale();
    }

    @Override
    public void setScaledWidth(float width) {
        if (unscaledW > 1.0f) {
            setScale(width / unscaledW);
        }
    }

    @Override
    public float getScaledHeight() {
        return unscaledH * getScale();
    }

    @Override
    public void setScaledHeight(float height) {
        if (unscaledH > 1.0f) {
            setScale(height / unscaledH);
        }
    }

    @Override
    public boolean getHidden() {
        return !LyricsConfig.enabled || !LyricsConfig.onScreen;
    }

    @Override
    public void setHidden(boolean hidden) {
        LyricsConfig.onScreen = !hidden;
        config.onScreen = !hidden;
        LyricsConfig.save();
        config.save();
    }

    @Override
    public boolean getSupportsScale() {
        return true;
    }

    @Override
    public boolean getPlacementReady() {
        return true;
    }

    @Override
    public boolean getOwnsPlacement() {
        return false;
    }

    @Override
    public HudResize getResizeAxes() {
        return HudResize.Both;
    }

    @Override
    public void onDragEnd() {
        save();
    }

    @Override
    public List<Property<?>> linkedProperties() {
        List<Property<?>> props = new ArrayList<Property<?>>();
        for (int i = 0; i < LINKED_KEYS.length; i++) {
            Property<?> p = config.findProperty(LINKED_KEYS[i]);
            if (p != null) {
                props.add(p);
            }
        }
        return props;
    }

    @Override
    public void save() {
        LyricsConfig.save();
        config.save();
    }
}
