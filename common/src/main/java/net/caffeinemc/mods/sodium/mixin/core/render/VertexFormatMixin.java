package net.caffeinemc.mods.sodium.mixin.core.render;

import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import it.unimi.dsi.fastutil.ints.IntList;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatExtensions;
import net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(VertexFormat.class)
public class VertexFormatMixin implements VertexFormatExtensions {
    @Unique
    private int sodium$globalId;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void afterInit(List<VertexFormatElement> elements, List<String> names, IntList offsets, int vertexSize, CallbackInfo ci) {
        this.sodium$globalId = VertexFormatRegistry.instance()
                .allocateGlobalId((VertexFormat) (Object) this);
    }

    @Unique
    public int sodium$getGlobalId() {
        return this.sodium$globalId;
    }
}
