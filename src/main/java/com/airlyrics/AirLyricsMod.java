package com.airlyrics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;
import org.apache.commons.lang3.ArrayUtils;
import org.lwjgl.input.Keyboard;

public class AirLyricsMod {
    public static KeyBinding configKey;
    private static boolean initialized = false;

    public static void onClientTick(Minecraft client) {
        if (!initialized) {
            initialized = true;
            LyricsConfig.load();
            MediaTracker.start();
        }

        if (configKey == null && client.options != null) {
            configKey = new KeyBinding("Lyrics Settings", Keyboard.KEY_L, "AirLyrics");
            client.options.keyBindings = ArrayUtils.add(client.options.keyBindings, configKey);
        }

        if (configKey != null && configKey.consumeClick()) {
            client.openScreen(new LyricsGuiScreen());
        }

        LyricsRenderer.tick(client);
    }
}
