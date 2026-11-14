package com.airlyrics.mixin;

import com.airlyrics.LyricsRenderer;
import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameGui.class)
public class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderHud(float tickDelta, CallbackInfo ci) {
        LyricsRenderer.renderHudLyrics(tickDelta);
    }
}
