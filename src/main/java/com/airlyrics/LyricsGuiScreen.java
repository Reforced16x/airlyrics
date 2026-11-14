package com.airlyrics;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Clean paged fallback settings GUI (used when OneConfig v1 is not loaded).
 * Exposes every AirLyrics setting cleanly across two tabs without overlapping.
 */
public class LyricsGuiScreen extends Screen {
    private static final int[][] GRADIENT_PRESETS = {
        {0x66F3FF, 0xFF7BE5},
        {0xFFD166, 0xFF5E7E},
        {0x72FFB6, 0x8B7BFF},
        {0x64B5FF, 0xFFFFFF},
        {0xFF5470, 0xFFAE42},
        {0xD29BFF, 0xFF77B4},
        {0xA8FF78, 0x78FFD6},
        {0x36D1DC, 0x5B86E5}
    };
    private static final String[] GRADIENT_NAMES = {
        "Cyber Cyan-Pink",
        "Sunset Gold-Rose",
        "Aurora Mint-Violet",
        "Ice Blue-White",
        "Crimson-Amber",
        "Lavender-Berry",
        "Lime-Aqua",
        "Ocean Wave"
    };

    private static final int[] COLOR_PRESETS = {
        0x55FFFF, 0xFF77DD, 0x77FF99, 0xFFDD66, 0x99B4FF, 0xFFFFFF
    };

    private final Screen parent;
    private int activeTab = 0;
    private int colorPresetIdx = 0;
    private int gradientPresetIdx = 0;

    public LyricsGuiScreen() {
        this(null);
    }

    public LyricsGuiScreen(Screen parent) {
        this.parent = parent;
        syncPresetIndices();
    }

    private void syncPresetIndices() {
        for (int i = 0; i < COLOR_PRESETS.length; i++) {
            if (COLOR_PRESETS[i] == (LyricsConfig.primaryColor & 0x00FFFFFF)) {
                colorPresetIdx = i;
                break;
            }
        }
        int gs = LyricsConfig.gradientColorStart & 0x00FFFFFF;
        int ge = LyricsConfig.gradientColorEnd & 0x00FFFFFF;
        for (int i = 0; i < GRADIENT_PRESETS.length; i++) {
            if (GRADIENT_PRESETS[i][0] == gs && GRADIENT_PRESETS[i][1] == ge) {
                gradientPresetIdx = i;
                break;
            }
        }
    }

    @Override
    public void init() {
        this.buttons.clear();
        int cx = this.width / 2;
        int top = Math.max(28, (this.height - 216) / 2);
        int leftCol = cx - 156;
        int rightCol = cx + 4;

        // Top tab switcher
        this.buttons.add(new ButtonWidget(90, leftCol, top, 152, 18,
                (activeTab == 0 ? "\u00A7b> " : "") + "Display, Sky & Sync"));
        this.buttons.add(new ButtonWidget(91, rightCol, top, 152, 18,
                (activeTab == 1 ? "\u00A7b> " : "") + "Font, Glow & Light"));

        int rowY = top + 24;
        int step = 22;

        if (activeTab == 0) {
            this.buttons.add(new ButtonWidget(1, leftCol, rowY, 152, 20, "Mod: " + onOff(LyricsConfig.enabled)));
            this.buttons.add(new ButtonWidget(2, rightCol, rowY, 152, 20, "In-World: " + onOff(LyricsConfig.inWorld)));

            this.buttons.add(new ButtonWidget(3, leftCol, rowY + step, 152, 20, "Sky Lyrics: " + onOff(LyricsConfig.skyLyrics)));
            this.buttons.add(new ButtonWidget(4, rightCol, rowY + step, 152, 20, "On-Screen HUD: " + onOff(LyricsConfig.onScreen)));

            this.buttons.add(new ButtonWidget(5, leftCol, rowY + step * 2, 152, 20, "Apple Style: " + onOff(LyricsConfig.appleStyle)));
            this.buttons.add(new ButtonWidget(6, rightCol, rowY + step * 2, 152, 20, "Word Popups: " + onOff(LyricsConfig.showWords)));

            this.buttons.add(new ButtonWidget(7, leftCol, rowY + step * 3, 152, 20, "Scatter: " + onOff(LyricsConfig.scatterLayout)));
            this.buttons.add(new ButtonWidget(8, rightCol, rowY + step * 3, 152, 20, "Uppercase: " + onOff(LyricsConfig.uppercase)));

            this.buttons.add(new ButtonWidget(9, leftCol, rowY + step * 4, 152, 20,
                    "Scale: " + Math.round(LyricsConfig.scalePercent) + "%"));
            this.buttons.add(new ButtonWidget(10, rightCol, rowY + step * 4, 152, 20,
                    "Opacity: " + Math.round(LyricsConfig.opacityPercent) + "%"));

            this.buttons.add(new ButtonWidget(11, leftCol, rowY + step * 5, 152, 20,
                    "Sky Scale: " + Math.round(LyricsConfig.skyScalePercent) + "%"));
            this.buttons.add(new ButtonWidget(12, rightCol, rowY + step * 5, 152, 20,
                    "Sky Pitch: " + Math.round(LyricsConfig.skyElevationDeg) + "\u00B0"));

            this.buttons.add(new ButtonWidget(13, leftCol, rowY + step * 6, 74, 20, "Sync -50ms"));
            this.buttons.add(new ButtonWidget(14, leftCol + 78, rowY + step * 6, 74, 20, "Sync +50ms"));
            this.buttons.add(new ButtonWidget(15, rightCol, rowY + step * 6, 152, 20, "Reset Defaults"));
        } else {
            this.buttons.add(new ButtonWidget(20, leftCol, rowY, 312, 20,
                    "Font Style: " + LyricsConfig.getFontOptionName()));

            this.buttons.add(new ButtonWidget(21, leftCol, rowY + step, 152, 20,
                    "Glow: " + onOff(LyricsConfig.glowEnabled)));
            this.buttons.add(new ButtonWidget(22, rightCol, rowY + step, 152, 20,
                    "Glow Power: " + Math.round(LyricsConfig.glowIntensityPercent) + "%"));

            this.buttons.add(new ButtonWidget(23, leftCol, rowY + step * 2, 152, 20,
                    "Gradient: " + onOff(LyricsConfig.gradientEnabled)));
            this.buttons.add(new ButtonWidget(24, rightCol, rowY + step * 2, 152, 20,
                    "Moving Grad: " + onOff(LyricsConfig.movingGradient)));

            this.buttons.add(new ButtonWidget(25, leftCol, rowY + step * 3, 152, 20,
                    "Grad: " + GRADIENT_NAMES[gradientPresetIdx]));
            this.buttons.add(new ButtonWidget(26, rightCol, rowY + step * 3, 152, 20,
                    "Grad Speed: " + Math.round(LyricsConfig.gradientSpeedPercent) + "%"));

            this.buttons.add(new ButtonWidget(27, leftCol, rowY + step * 4, 152, 20,
                    "Emit Light: " + onOff(LyricsConfig.lyricsEmitLight)));
            this.buttons.add(new ButtonWidget(28, rightCol, rowY + step * 4, 152, 20,
                    "Light Level: " + LyricsConfig.emitLightLevel));

            this.buttons.add(new ButtonWidget(29, leftCol, rowY + step * 5, 152, 20,
                    "Pastel World: " + onOff(LyricsConfig.rainbowPastel)));
            this.buttons.add(new ButtonWidget(30, rightCol, rowY + step * 5, 152, 20,
                    String.format("Accent: #%06X", LyricsConfig.primaryColor)));

            this.buttons.add(new ButtonWidget(31, leftCol, rowY + step * 6, 152, 20,
                    "Word Est.: " + onOff(LyricsConfig.estimateWordTimings)));
            this.buttons.add(new ButtonWidget(32, rightCol, rowY + step * 6, 152, 20,
                    "Reset HUD Pos"));
        }

        this.buttons.add(new ButtonWidget(100, cx - 100, rowY + step * 7 + 4, 200, 20, "Done"));
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        switch (button.id) {
            case 90:
                activeTab = 0;
                break;
            case 91:
                activeTab = 1;
                break;
            case 1:
                LyricsConfig.enabled = !LyricsConfig.enabled;
                break;
            case 2:
                LyricsConfig.inWorld = !LyricsConfig.inWorld;
                break;
            case 3:
                LyricsConfig.skyLyrics = !LyricsConfig.skyLyrics;
                break;
            case 4:
                LyricsConfig.onScreen = !LyricsConfig.onScreen;
                break;
            case 5:
                LyricsConfig.appleStyle = !LyricsConfig.appleStyle;
                break;
            case 6:
                LyricsConfig.showWords = !LyricsConfig.showWords;
                break;
            case 7:
                LyricsConfig.scatterLayout = !LyricsConfig.scatterLayout;
                break;
            case 8:
                LyricsConfig.uppercase = !LyricsConfig.uppercase;
                break;
            case 9:
                LyricsConfig.scalePercent += 25.0f;
                if (LyricsConfig.scalePercent > 200.0f) LyricsConfig.scalePercent = 75.0f;
                break;
            case 10:
                LyricsConfig.opacityPercent += 15.0f;
                if (LyricsConfig.opacityPercent > 100.0f) LyricsConfig.opacityPercent = 25.0f;
                break;
            case 11:
                LyricsConfig.skyScalePercent += 25.0f;
                if (LyricsConfig.skyScalePercent > 245.0f) LyricsConfig.skyScalePercent = 95.0f;
                break;
            case 12:
                LyricsConfig.skyElevationDeg += 8.0f;
                if (LyricsConfig.skyElevationDeg > 66.0f) LyricsConfig.skyElevationDeg = 22.0f;
                break;
            case 13:
                LyricsConfig.syncOffsetMs -= 50;
                break;
            case 14:
                LyricsConfig.syncOffsetMs += 50;
                break;
            case 15:
                LyricsConfig.reset();
                syncPresetIndices();
                break;
            case 20:
                LyricsConfig.fontIndex = (LyricsConfig.fontIndex + 1) % LyricsConfig.FONT_OPTIONS.length;
                break;
            case 21:
                LyricsConfig.glowEnabled = !LyricsConfig.glowEnabled;
                break;
            case 22:
                LyricsConfig.glowIntensityPercent += 25.0f;
                if (LyricsConfig.glowIntensityPercent > 200.0f) LyricsConfig.glowIntensityPercent = 50.0f;
                break;
            case 23:
                LyricsConfig.gradientEnabled = !LyricsConfig.gradientEnabled;
                break;
            case 24:
                LyricsConfig.movingGradient = !LyricsConfig.movingGradient;
                break;
            case 25:
                gradientPresetIdx = (gradientPresetIdx + 1) % GRADIENT_PRESETS.length;
                LyricsConfig.gradientColorStart = GRADIENT_PRESETS[gradientPresetIdx][0];
                LyricsConfig.gradientColorEnd = GRADIENT_PRESETS[gradientPresetIdx][1];
                break;
            case 26:
                LyricsConfig.gradientSpeedPercent += 40.0f;
                if (LyricsConfig.gradientSpeedPercent > 260.0f) LyricsConfig.gradientSpeedPercent = 60.0f;
                break;
            case 27:
                LyricsConfig.lyricsEmitLight = !LyricsConfig.lyricsEmitLight;
                if (!LyricsConfig.lyricsEmitLight) {
                    DynamicLightManager.clear(this.minecraft);
                }
                break;
            case 28:
                LyricsConfig.emitLightLevel += 2;
                if (LyricsConfig.emitLightLevel > 15) LyricsConfig.emitLightLevel = 6;
                break;
            case 29:
                LyricsConfig.rainbowPastel = !LyricsConfig.rainbowPastel;
                break;
            case 30:
                colorPresetIdx = (colorPresetIdx + 1) % COLOR_PRESETS.length;
                LyricsConfig.primaryColor = COLOR_PRESETS[colorPresetIdx];
                break;
            case 31:
                LyricsConfig.estimateWordTimings = !LyricsConfig.estimateWordTimings;
                break;
            case 32:
                LyricsConfig.hudX = -1.0f;
                LyricsConfig.hudY = -1.0f;
                break;
            case 100:
                LyricsConfig.save();
                OneConfigCompat.syncFromCore();
                this.minecraft.openScreen(parent);
                return;
            default:
                return;
        }

        LyricsConfig.save();
        OneConfigCompat.syncFromCore();
        init();
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        this.renderBackground();
        int top = Math.max(12, (this.height - 216) / 2 - 14);
        drawCenteredString(this.textRenderer, "AirLyrics Settings", this.width / 2, top, 0x55FFFF);
        String trackLine = MediaTracker.hasMedia
                ? (MediaTracker.currentArtist + " - " + MediaTracker.currentTitle)
                : "No active Windows media detected";
        String offsetInfo = String.format("Sync Offset: %+dms  |  Font: %s",
                LyricsConfig.syncOffsetMs, LyricsConfig.getFontOptionName());
        drawCenteredString(this.textRenderer, trackLine, this.width / 2, top + 12, 0xCCCCCC);
        super.render(mouseX, mouseY, tickDelta);
        drawCenteredString(this.textRenderer, offsetInfo, this.width / 2, Math.min(this.height - 14, top + 218), 0xAAAAAA);
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    private static String onOff(boolean v) {
        return v ? "\u00A7aON" : "\u00A7cOFF";
    }
}
