package com.airlyrics.mixin;

import com.airlyrics.DynamicLightManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects AirLyrics dynamic block light contributions into chunk rebuild regions
 * so nearby terrain, leaves, stone, and water light up smoothly under vanilla and shaders.
 */
@Mixin(WorldRegion.class)
public abstract class WorldRegionLightMixin {
    @Shadow
    protected World world;

    @Inject(method = "getLightColor", at = @At("RETURN"), cancellable = true)
    private void airlyrics$applyRegionDynamicLight(BlockPos pos, int minBlockLight, CallbackInfoReturnable<Integer> cir) {
        if (this.world == null || !this.world.isClient || !DynamicLightManager.isActive()) {
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
