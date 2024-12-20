package net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline;

import dev.vexor.radium.compat.mojang.minecraft.random.SingleThreadedRandomSource;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.color.ColorProviderRegistry;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadOrientation;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.parameters.MaterialParameters;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.frapi.mesh.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import net.caffeinemc.mods.sodium.client.services.PlatformModelAccess;
import net.caffeinemc.mods.sodium.client.services.SodiumModelData;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.material.ShadeMode;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.legacyfabric.fabric.api.util.TriState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

public class BlockRenderer extends AbstractBlockRenderContext {
    private final ColorProviderRegistry colorProviderRegistry;
    private final int[] vertexColors = new int[4];
    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

    private ChunkBuildBuffers buffers;

    private final Vector3f posOffset = new Vector3f();
    private final BlockPos.Mutable scratchPos = new BlockPos.Mutable();
    @Nullable
    private ColorProvider<BlockState> colorProvider;
    private TranslucentGeometryCollector collector;

    public BlockRenderer(ColorProviderRegistry colorRegistry, LightPipelineProvider lighters) {
        this.colorProviderRegistry = colorRegistry;
        this.lighters = lighters;

        this.random = new SingleThreadedRandomSource(42L);
    }

    public void prepare(ChunkBuildBuffers buffers, LevelSlice level, TranslucentGeometryCollector collector) {
        this.buffers = buffers;
        this.level = level;
        this.collector = collector;
        this.slice = level;
    }

    public void release() {
        this.buffers = null;
        this.level = null;
        this.collector = null;
        this.slice = null;
    }

    public void renderModel(BakedModel model, BlockState state, BlockPos pos, BlockPos origin) {
        this.state = state;
        this.pos = pos;

        this.randomSeed = 42L;

        this.posOffset.set(origin.getX(), origin.getY(), origin.getZ());

        Block.OffsetType offsetType = state.getBlock().getOffsetType();

        if (offsetType != Block.OffsetType.NONE) {
            int x = origin.getX();
            int z = origin.getZ();
            // Taken from MathHelper.hashCode()
            long i = (x * 3129871L) ^ z * 116129781L;
            i = i * i * 42317861L + i * 11L;

            double fx = (((i >> 16 & 15L) / 15.0F) - 0.5f) * 0.5f;
            double fz = (((i >> 24 & 15L) / 15.0F) - 0.5f) * 0.5f;
            double fy = 0;

            if (offsetType == Block.OffsetType.XYZ) {
                fy += (((i >> 20 & 15L) / 15.0F) - 1.0f) * 0.2f;
            }

            posOffset.add((float)fx, (float)fy, (float)fz);
        }

        this.colorProvider = this.colorProviderRegistry.getColorProvider(state.getBlock());

        type = state.getBlock().getRenderLayerType();

        this.prepareCulling(true);
        this.prepareAoInfo(model.useAmbientOcclusion());

        modelData = null;

        Iterable<RenderLayer> renderTypes = PlatformModelAccess.getInstance().getModelRenderTypes(level, model, state, pos, random, modelData);

        for (RenderLayer type : renderTypes) {
            this.type = type;
            ((FabricBakedModel) model).emitBlockQuads(getEmitter(), this.level, state, pos, this.randomSupplier, this::isFaceCulled);
        }

        type = null;
        modelData = SodiumModelData.EMPTY;
    }

    /**
     * Process quad, after quad transforms and the culling check have been applied.
     */
    @Override
    protected void processQuad(MutableQuadViewImpl quad) {
        final RenderMaterial mat = quad.material();
        final TriState aoMode = mat.ambientOcclusion();
        final ShadeMode shadeMode = mat.shadeMode();
        final LightMode lightMode;
        if (aoMode == TriState.DEFAULT) {
            lightMode = this.defaultLightMode;
        } else {
            lightMode = this.useAmbientOcclusion && aoMode.get() ? LightMode.SMOOTH : LightMode.FLAT;
        }
        final boolean emissive = mat.emissive();

        Material material;

        final BlendMode blendMode = mat.blendMode();
        if (blendMode == BlendMode.DEFAULT) {
            material = DefaultMaterials.forRenderLayer(type);
        } else {
            material = DefaultMaterials.forRenderLayer(blendMode.blockRenderLayer == null ? type : blendMode.blockRenderLayer);
        }

        this.tintQuad(quad);
        this.shadeQuad(quad, lightMode, emissive, shadeMode);
        this.bufferQuad(quad, this.quadLightData.br, material);
    }

    private void tintQuad(MutableQuadViewImpl quad) {
        int tintIndex = quad.tintIndex();

        if (tintIndex != -1) {
            ColorProvider<BlockState> colorProvider = this.colorProvider;

            if (colorProvider != null) {
                int[] vertexColors = this.vertexColors;
                colorProvider.getColors(this.slice, this.pos, this.scratchPos, this.state, quad, vertexColors);

                for (int i = 0; i < 4; i++) {
                    quad.color(i, ColorMixer.mulComponentWise(vertexColors[i], quad.color(i)));
                }
            }
        }
    }

    private void bufferQuad(MutableQuadViewImpl quad, float[] brightnesses, Material material) {
        // TODO: Find a way to reimplement quad reorientation
        ModelQuadOrientation orientation = ModelQuadOrientation.NORMAL;
        ChunkVertexEncoder.Vertex[] vertices = this.vertices;
        Vector3f offset = this.posOffset;

        for (int dstIndex = 0; dstIndex < 4; dstIndex++) {
            int srcIndex = orientation.getVertexIndex(dstIndex);

            ChunkVertexEncoder.Vertex out = vertices[dstIndex];
            out.x = quad.x(srcIndex) + offset.x;
            out.y = quad.y(srcIndex) + offset.y;
            out.z = quad.z(srcIndex) + offset.z;

            // FRAPI uses ARGB color format; convert to ABGR.
            out.color = ColorARGB.toABGR(quad.color(srcIndex));
            out.ao = brightnesses[srcIndex];

            out.u = quad.u(srcIndex);
            out.v = quad.v(srcIndex);

            out.light = 15;
        }

        var atlasSprite = quad.sprite(SpriteFinderCache.forBlockAtlas());
        var materialBits = material.bits();
        ModelQuadFacing normalFace = quad.normalFace();

        // attempt render pass downgrade if possible
        var pass = material.pass;

        var downgradedPass = attemptPassDowngrade(atlasSprite, pass);
        if (downgradedPass != null) {
            pass = downgradedPass;
        }

        // collect all translucent quads into the translucency sorting system if enabled
        if (pass.isTranslucent() && this.collector != null) {
            this.collector.appendQuad(quad.getFaceNormal(), vertices, normalFace);
        }

        // if there was a downgrade from translucent to cutout, the material bits' alpha cutoff needs to be updated
        if (downgradedPass != null && material == DefaultMaterials.TRANSLUCENT && pass == DefaultTerrainRenderPasses.CUTOUT) {
            // ONE_TENTH and HALF are functionally the same so it doesn't matter which one we take here
            materialBits = MaterialParameters.pack(AlphaCutoffParameter.ONE_TENTH, material.mipped);
        }

        ChunkModelBuilder builder = this.buffers.get(pass);
        ChunkMeshBufferBuilder vertexBuffer = builder.getVertexBuffer(normalFace);
        vertexBuffer.push(vertices, materialBits);

        builder.addSprite(atlasSprite);
    }

    private boolean validateQuadUVs(Sprite atlasSprite) {
        // sanity check that the quad's UVs are within the sprite's bounds
        var spriteUMin = atlasSprite.getMinU();
        var spriteUMax = atlasSprite.getMaxU();
        var spriteVMin = atlasSprite.getMinV();
        var spriteVMax = atlasSprite.getMaxV();

        for (int i = 0; i < 4; i++) {
            var u = this.vertices[i].u;
            var v = this.vertices[i].v;
            if (u < spriteUMin || u > spriteUMax || v < spriteVMin || v > spriteVMax) {
                return false;
            }
        }

        return true;
    }

    private @Nullable TerrainRenderPass attemptPassDowngrade(Sprite sprite, TerrainRenderPass pass) {
        // TODO: Make this a setting
        return null;

        /*
        boolean attemptDowngrade = true;
        boolean hasNonOpaqueVertex = false;

        for (int i = 0; i < 4; i++) {
            hasNonOpaqueVertex |= ColorABGR.unpackAlpha(this.vertices[i].color) != 0xFF;
        }

        // don't do downgrade if some vertex is not fully opaque
        if (pass.isTranslucent() && hasNonOpaqueVertex) {
            attemptDowngrade = false;
        }

        if (attemptDowngrade) {
            attemptDowngrade = validateQuadUVs(sprite);
        }

        if (attemptDowngrade) {
            return getDowngradedPass(sprite, pass);
        }

        return null;
         */
    }
}