package dev.vexor.radium.mixin.sodium.features.options.world;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.dimension.Dimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Dimension.class)
public class DimensionMixin {
    /**
     * @reason Cloud height setting
     * @author Decencies
     */
    @Overwrite
    @Environment(EnvType.CLIENT)
    public float getCloudHeight() {
        return SodiumClientMod.options().quality.cloudHeight;
    }
}
