package com.airlyrics;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

public class LyricsGuiScreen extends Screen {

    @Override
    public void init() {
        this.buttons.clear();
        int cx = this.width / 2 - 155;
        int cy = this.height / 2 - 70;

        this.buttons.add(new ButtonWidget(1, cx, cy, 150, 20, getToggleText("Enabled", LyricsConfig.enabled)));
        this.buttons.add(new ButtonWidget(2, cx, cy + 24, 150, 20, getToggleText("In the world", LyricsConfig.inWorld)));
        this.buttons.add(new ButtonWidget(3, cx, cy + 48, 150, 20, getToggleText("On screen", LyricsConfig.onScreen)));
        this.buttons.add(new ButtonWidget(4, cx, cy + 72, 73, 20, "Offset -50ms"));
        this.buttons.add(new ButtonWidget(5, cx + 77, cy + 72, 73, 20, "Offset +50ms"));

        int rx = this.width / 2 + 5;
        this.buttons.add(new ButtonWidget(6, rx, cy, 150, 20, "Show: " + (LyricsConfig.showWords ? "Words" : "Lines")));
        this.buttons.add(new ButtonWidget(7, rx, cy + 24, 150, 20, "Layout: " + (LyricsConfig.scatterLayout ? "Scatter" : "Centered")));
        this.buttons.add(new ButtonWidget(8, rx, cy + 48, 150, 20, getToggleText("Uppercase", LyricsConfig.uppercase)));
        this.buttons.add(new ButtonWidget(9, rx, cy + 72, 73, 20, "Size -10%"));
        this.buttons.add(new ButtonWidget(10, rx + 77, cy + 72, 73, 20, "Size +10%"));

        this.buttons.add(new ButtonWidget(11, cx, cy + 100, 150, 20, getToggleText("Pastel Colors", LyricsConfig.rainbowPastel)));
        this.buttons.add(new ButtonWidget(12, rx, cy + 100, 150, 20, "Reset"));
    }

    private String getToggleText(String label, boolean state) {
        return label + ": " + (state ? "\u00A7bON" : "\u00A77OFF");
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        switch (button.id) {
            case 1: LyricsConfig.enabled = !LyricsConfig.enabled; break;
            case 2: LyricsConfig.inWorld = !LyricsConfig.inWorld; break;
            case 3: LyricsConfig.onScreen = !LyricsConfig.onScreen; break;
            case 4: LyricsConfig.syncOffsetMs -= 50; break;
            case 5: LyricsConfig.syncOffsetMs += 50; break;
            case 6: LyricsConfig.showWords = !LyricsConfig.showWords; break;
            case 7: LyricsConfig.scatterLayout = !LyricsConfig.scatterLayout; break;
            case 8: LyricsConfig.uppercase = !LyricsConfig.uppercase; break;
            case 9: LyricsConfig.scalePercent = Math.max(50f, LyricsConfig.scalePercent - 10f); break;
            case 10: LyricsConfig.scalePercent = Math.min(250f, LyricsConfig.scalePercent + 10f); break;
            case 11: LyricsConfig.rainbowPastel = !LyricsConfig.rainbowPastel; break;
            case 12: LyricsConfig.reset(); break;
        }
        LyricsConfig.save();
        this.init();
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        this.renderBackground();
        int panelX = this.width / 2 - 165;
        int panelY = this.height / 2 - 105;
        fill(panelX, panelY, panelX + 330, panelY + 240, 0xCC121318);

        this.drawCenteredString(this.textRenderer, "\u00A7b\u266B \u00A7f\u00A7lAirLyrics \u00A77(lrclib.net & NetEase)", this.width / 2, panelY + 10, 0xFFFFFF);

        String trackInfo = MediaTracker.currentTitle.isEmpty()
            ? "No active media detected"
            : MediaTracker.currentTitle + " - " + MediaTracker.currentArtist;
        this.drawCenteredString(this.textRenderer, "\u00A77Now Playing: \u00A7a" + trackInfo, this.width / 2, panelY + 23, 0xFFFFFF);

        String stats = String.format("Sync Offset: %d ms   |   Size: %.0f%%", LyricsConfig.syncOffsetMs, LyricsConfig.scalePercent);
        this.drawCenteredString(this.textRenderer, stats, this.width / 2, panelY + 165, 0xAAAAAA);

        String preview = LyricsConfig.uppercase ? "YOUR LYRICS SHOW UP HERE" : "Your lyrics show up here";
        this.drawCenteredString(this.textRenderer, preview, this.width / 2, panelY + 185, 0x55AAFF);

        super.render(mouseX, mouseY, tickDelta);
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }
}
