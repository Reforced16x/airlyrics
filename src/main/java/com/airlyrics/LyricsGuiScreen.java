package com.airlyrics;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.Locale;

/**
 * Fallback vanilla settings screen used when OneConfig is not installed.
 */
public class LyricsGuiScreen extends Screen {
    private static final int ID_ENABLED = 1;
    private static final int ID_IN_WORLD = 2;
    private static final int ID_ON_SCREEN = 3;
    private static final int ID_SHOW_WORDS = 4;
    private static final int ID_SCATTER = 5;
    private static final int ID_UPPERCASE = 6;
    private static final int ID_GLOW = 7;
    private static final int ID_GLOW_INTENSITY = 8;
    private static final int ID_IMPORTANT_LARGER = 9;
    private static final int ID_WORD_ESTIMATOR = 10;
    private static final int ID_OPACITY_DOWN = 11;
    private static final int ID_OPACITY_UP = 12;
    private static final int ID_SYNC_DOWN = 13;
    private static final int ID_SYNC_UP = 14;
    private static final int ID_SCALE_DOWN = 15;
    private static final int ID_SCALE_UP = 16;
    private static final int ID_REFETCH = 17;
    private static final int ID_DONE = 99;

    @Override
    public void init() {
        this.buttons.clear();
        int leftX = this.width / 2 - 155;
        int rightX = this.width / 2 + 5;
        int startY = Math.max(46, this.height / 2 - 96);
        int rowH = 22;

        this.buttons.add(new ButtonWidget(ID_ENABLED, leftX, startY, 150, 20, labelEnabled()));
        this.buttons.add(new ButtonWidget(ID_IN_WORLD, rightX, startY, 150, 20, labelInWorld()));

        this.buttons.add(new ButtonWidget(ID_ON_SCREEN, leftX, startY + rowH, 150, 20, labelOnScreen()));
        this.buttons.add(new ButtonWidget(ID_SHOW_WORDS, rightX, startY + rowH, 150, 20, labelShowWords()));

        this.buttons.add(new ButtonWidget(ID_SCATTER, leftX, startY + rowH * 2, 150, 20, labelScatter()));
        this.buttons.add(new ButtonWidget(ID_UPPERCASE, rightX, startY + rowH * 2, 150, 20, labelUppercase()));

        this.buttons.add(new ButtonWidget(ID_GLOW, leftX, startY + rowH * 3, 150, 20, labelGlow()));
        this.buttons.add(new ButtonWidget(ID_GLOW_INTENSITY, rightX, startY + rowH * 3, 150, 20, labelGlowIntensity()));

        this.buttons.add(new ButtonWidget(ID_IMPORTANT_LARGER, leftX, startY + rowH * 4, 150, 20, labelImportant()));
        this.buttons.add(new ButtonWidget(ID_WORD_ESTIMATOR, rightX, startY + rowH * 4, 150, 20, labelEstimator()));

        this.buttons.add(new ButtonWidget(ID_OPACITY_DOWN, leftX, startY + rowH * 5, 72, 20, "Opacity -10%"));
        this.buttons.add(new ButtonWidget(ID_OPACITY_UP, leftX + 78, startY + rowH * 5, 72, 20, "Opacity +10%"));
        this.buttons.add(new ButtonWidget(ID_SYNC_DOWN, rightX, startY + rowH * 5, 72, 20, "Sync -50ms"));
        this.buttons.add(new ButtonWidget(ID_SYNC_UP, rightX + 78, startY + rowH * 5, 72, 20, "Sync +50ms"));

        this.buttons.add(new ButtonWidget(ID_SCALE_DOWN, leftX, startY + rowH * 6, 72, 20, "Scale -10%"));
        this.buttons.add(new ButtonWidget(ID_SCALE_UP, leftX + 78, startY + rowH * 6, 72, 20, "Scale +10%"));
        this.buttons.add(new ButtonWidget(ID_REFETCH, rightX, startY + rowH * 6, 150, 20, "Refetch Lyrics"));

        this.buttons.add(new ButtonWidget(ID_DONE, this.width / 2 - 75, startY + rowH * 7 + 4, 150, 20, "Done"));
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        switch (button.id) {
            case ID_ENABLED:
                LyricsConfig.enabled = !LyricsConfig.enabled;
                button.message = labelEnabled();
                break;
            case ID_IN_WORLD:
                LyricsConfig.inWorld = !LyricsConfig.inWorld;
                button.message = labelInWorld();
                break;
            case ID_ON_SCREEN:
                LyricsConfig.onScreen = !LyricsConfig.onScreen;
                button.message = labelOnScreen();
                break;
            case ID_SHOW_WORDS:
                LyricsConfig.showWords = !LyricsConfig.showWords;
                button.message = labelShowWords();
                break;
            case ID_SCATTER:
                LyricsConfig.scatterLayout = !LyricsConfig.scatterLayout;
                button.message = labelScatter();
                break;
            case ID_UPPERCASE:
                LyricsConfig.uppercase = !LyricsConfig.uppercase;
                button.message = labelUppercase();
                break;
            case ID_GLOW:
                LyricsConfig.glowEnabled = !LyricsConfig.glowEnabled;
                button.message = labelGlow();
                break;
            case ID_GLOW_INTENSITY:
                LyricsConfig.glowIntensityPercent += 25.0f;
                if (LyricsConfig.glowIntensityPercent > 200.0f) {
                    LyricsConfig.glowIntensityPercent = 25.0f;
                }
                button.message = labelGlowIntensity();
                break;
            case ID_IMPORTANT_LARGER:
                LyricsConfig.importantLyricsLarger = !LyricsConfig.importantLyricsLarger;
                button.message = labelImportant();
                break;
            case ID_WORD_ESTIMATOR:
                LyricsConfig.estimateWordTimings = !LyricsConfig.estimateWordTimings;
                button.message = labelEstimator();
                LyricsFetcher.refetchCurrentTrack();
                break;
            case ID_OPACITY_DOWN:
                LyricsConfig.opacityPercent = Math.max(10.0f, LyricsConfig.opacityPercent - 10.0f);
                break;
            case ID_OPACITY_UP:
                LyricsConfig.opacityPercent = Math.min(100.0f, LyricsConfig.opacityPercent + 10.0f);
                break;
            case ID_SYNC_DOWN:
                LyricsConfig.syncOffsetMs -= 50;
                break;
            case ID_SYNC_UP:
                LyricsConfig.syncOffsetMs += 50;
                break;
            case ID_SCALE_DOWN:
                LyricsConfig.scalePercent = Math.max(50.0f, LyricsConfig.scalePercent - 10.0f);
                break;
            case ID_SCALE_UP:
                LyricsConfig.scalePercent = Math.min(250.0f, LyricsConfig.scalePercent + 10.0f);
                break;
            case ID_REFETCH:
                LyricsFetcher.refetchCurrentTrack();
                MediaTracker.pollAsyncNow();
                return;
            case ID_DONE:
                LyricsConfig.save();
                this.minecraft.openScreen(null);
                return;
            default:
                return;
        }
        LyricsConfig.save();
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        this.renderBackground();
        int topY = Math.max(10, this.height / 2 - 134);
        this.drawCenteredString(this.textRenderer, "AirLyrics Settings", this.width / 2, topY, 0xFFFFFF);

        String trackInfo = MediaTracker.hasMedia
                ? (MediaTracker.currentArtist.isEmpty() ? MediaTracker.currentTitle : MediaTracker.currentArtist + " - " + MediaTracker.currentTitle)
                : "No active media session";
        this.drawCenteredString(this.textRenderer, "Track: " + trackInfo, this.width / 2, topY + 12, 0xA8F5DF);

        String infoLine = String.format(Locale.ROOT, "Opacity: %.0f%% | Scale: %.0f%% | Offset: %+dms | %s (%s)",
                LyricsConfig.opacityPercent,
                LyricsConfig.scalePercent,
                LyricsConfig.syncOffsetMs,
                LyricsFetcher.statusMessage,
                MediaTracker.currentBackend);
        this.drawCenteredString(this.textRenderer, infoLine, this.width / 2, topY + 24, 0xCCCCCC);

        super.render(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    private String labelEnabled() {
        return "AirLyrics: " + (LyricsConfig.enabled ? "ON" : "OFF");
    }

    private String labelInWorld() {
        return "3D World Lyrics: " + (LyricsConfig.inWorld ? "ON" : "OFF");
    }

    private String labelOnScreen() {
        return "HUD Lyrics: " + (LyricsConfig.onScreen ? "ON" : "OFF");
    }

    private String labelShowWords() {
        return "Word-by-Word: " + (LyricsConfig.showWords ? "ON" : "OFF");
    }

    private String labelScatter() {
        return "Scatter 3D: " + (LyricsConfig.scatterLayout ? "ON" : "OFF");
    }

    private String labelUppercase() {
        return "Uppercase: " + (LyricsConfig.uppercase ? "ON" : "OFF");
    }

    private String labelGlow() {
        return "Shine/Glow: " + (LyricsConfig.glowEnabled ? "ON" : "OFF");
    }

    private String labelGlowIntensity() {
        return String.format(Locale.ROOT, "Glow Power: %.0f%%", LyricsConfig.glowIntensityPercent);
    }

    private String labelImportant() {
        return "Important Bigger: " + (LyricsConfig.importantLyricsLarger ? "ON" : "OFF");
    }

    private String labelEstimator() {
        return "Beat Estimator: " + (LyricsConfig.estimateWordTimings ? "ON" : "OFF");
    }
}
