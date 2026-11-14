package com.airlyrics;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;

/**
 * Main client entrypoint and tick coordinator for AirLyrics.
 */
public class AirLyricsMod implements ClientModInitializer {
    private static boolean coreInitialized = false;
    private static boolean keybindRegistered = false;
    private static KeyBinding configKey;

    @Override
    public void onInitializeClient() {
        ensureCoreInitialized();
    }

    public static synchronized void ensureCoreInitialized() {
        if (coreInitialized) {
            return;
        }
        coreInitialized = true;
        LyricsConfig.load();
        OneConfigCompat.init();
        MediaTracker.start();
    }

    public static void onClientTick(Minecraft client) {
        ensureCoreInitialized();

        if (!keybindRegistered && client != null && client.options != null) {
            registerKeybind(client);
        }

        if (configKey != null && configKey.consumeClick()) {
            if (!OneConfigCompat.openConfigScreen() && client != null) {
                client.openScreen(new LyricsGuiScreen());
            }
        }

        LyricsRenderer.tick(client);
    }

    private static synchronized void registerKeybind(Minecraft client) {
        if (keybindRegistered || client == null || client.options == null) {
            return;
        }
        keybindRegistered = true;
        configKey = new KeyBinding("Lyrics Settings", Keyboard.KEY_L, "AirLyrics");
        KeyBinding[] existing = client.options.keyBindings;
        KeyBinding[] updated = Arrays.copyOf(existing, existing.length + 1);
        updated[existing.length] = configKey;
        client.options.keyBindings = updated;
        KeyBinding.resetMapping();
    }
}
