package dev.vexor.radium.mixin.extra.sky_colors;

import dev.vexor.radium.extra.client.SodiumExtraClientMod;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public class MixinBiome {
    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true)
    private void differentSkyColor(CallbackInfoReturnable<Integer> cir) {
        if (!SodiumExtraClientMod.options().detailSettings.skyColors) {
            cir.setReturnValue(7907327);
        }
    }
}