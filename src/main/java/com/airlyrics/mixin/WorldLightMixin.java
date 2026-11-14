package com.airlyrics.mixin;

import com.airlyrics.DynamicLightManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects AirLyrics dynamic block light contributions into client World lightmap queries
 * for entities, particles, and shader lighting.
 */
@Mixin(World.class)
public abstract class WorldLightMixin {
    @Shadow
    @Final
    public boolean isClient;

    @Inject(method = "getLightColor", at = @At("RETURN"), cancellable = true)
    private void airlyrics$applyWorldDynamicLight(BlockPos pos, int minBlockLight, CallbackInfoReturnable<Integer> cir) {
        if (!this.isClient || !DynamicLightManager.isActive()) {
            return;
        }
        int dyn = DynamicLightManager.getLightAt(pos);
        if (dyn <= 0) {
            return;
        }
        int light = cir.getReturnValueI();
        int curBlock = (light >> 4) & 0xF;
        if (dyn > curBlock) {
            cir.setReturnValue((light & 0xFFFFFF0F) | ((dyn & 0xF) << 4));
        }
    }
}
