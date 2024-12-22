package net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline;

import dev.vexor.radium.compat.mojang.math.Mth;
import dev.vexor.radium.compat.mojang.minecraft.BlockColors;
import dev.vexor.radium.compat.mojang.minecraft.IBlockColor;
import dev.vexor.radium.compat.mojang.minecraft.WorldUtil;
import dev.vexor.radium.util.FluidSprites;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.color.ColorProviderRegistry;
import net.caffeinemc.mods.sodium.client.model.color.DefaultColorProviders;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.model.light.LightPipeline;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.model.light.data.QuadLightData;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuad;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadViewMutable;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.util.DirectionUtil;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.block.AbstractFluidBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.BlockView;
import org.apache.commons.lang3.mutable.MutableFloat;
import org.apache.commons.lang3.mutable.MutableInt;
import org.joml.Vector3d;

public class DefaultFluidRenderer {
    // TODO: allow this to be changed by vertex format, WARNING: make sure TranslucentGeometryCollector knows about EPSILON
    // TODO: move fluid rendering to a separate render pass and control glPolygonOffset and glDepthFunc to fix this properly
    public static final float EPSILON = 0.001f;
    private static final float ALIGNED_EQUALS_EPSILON = 0.011f;

    private final BlockPos.Mutable scratchPos = new BlockPos.Mutable();
    private final MutableFloat scratchHeight = new MutableFloat(0);
    private final MutableInt scratchSamples = new MutableInt();

    private final ModelQuadViewMutable quad = new ModelQuad();

    private final LightPipelineProvider lighters;

    private final QuadLightData quadLightData = new QuadLightData();
    private final int[] quadColors = new int[4];

    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
    private final ColorProviderRegistry colorProviderRegistry;

    private final FluidSprites spriteCache = FluidSprites.create();

    public DefaultFluidRenderer(ColorProviderRegistry colorProviderRegistry, LightPipelineProvider lighters) {
        this.quad.setLightFace(Direction.UP);

        this.lighters = lighters;
        this.colorProviderRegistry = colorProviderRegistry;
    }

    private boolean isFluidOccluded(BlockView world, int x, int y, int z, Direction dir, AbstractFluidBlock fluid) {
        BlockPos pos = scratchPos.setPosition(x, y, z);
        BlockState blockState = world.getBlockState(pos);
        BlockPos adjPos = scratchPos.setPosition(x + dir.getOffsetX(), y + dir.getOffsetY(), z + dir.getOffsetZ());
        AbstractFluidBlock adjFluid = WorldUtil.getFluid(world.getBlockState(adjPos));
        boolean temp = fluid == adjFluid;

        if (blockState.getBlock().getMaterial().isOpaque()) {
            return temp || blockState.getBlock().isSideInvisible(world, pos, dir);
            // fluidlogged or next to water, occlude sides that are solid or the same liquid
        }
        return temp;
    }

    private boolean isSideExposed(BlockView world, int x, int y, int z, Direction dir, float height) {
        BlockPos pos = scratchPos.setPosition(x + dir.getOffsetX(), y + dir.getOffsetY(), z + dir.getOffsetZ());
        BlockState blockState = world.getBlockState(pos);
        Block block = blockState.getBlock();

        if (block.getMaterial().isOpaque()) {
            final boolean renderAsFullCube = block.renderAsNormalBlock();

            // Hoist these checks to avoid allocating the shape below
            if (renderAsFullCube) {
                // The top face always be inset, so if the shape above is a full cube it can't possibly occlude
                return dir == Direction.UP;
            } else {
                return true;
            }
        }

        return true;
    }

    public void render(LevelSlice level, BlockState fluidState, BlockPos blockPos, BlockPos offset, TranslucentGeometryCollector collector, ChunkModelBuilder meshBuilder, Material material) {
        int posX = blockPos.getX();
        int posY = blockPos.getY();
        int posZ = blockPos.getZ();

        AbstractFluidBlock fluid = (AbstractFluidBlock) fluidState.getBlock();

        boolean sfUp = this.isFluidOccluded(level, posX, posY, posZ, Direction.UP, fluid);
        boolean sfDown = this.isFluidOccluded(level, posX, posY, posZ, Direction.DOWN, fluid) ||
                !this.isSideExposed(level, posX, posY, posZ, Direction.DOWN, 0.8888889F);
        boolean sfNorth = this.isFluidOccluded(level, posX, posY, posZ, Direction.NORTH, fluid);
        boolean sfSouth = this.isFluidOccluded(level, posX, posY, posZ, Direction.SOUTH, fluid);
        boolean sfWest = this.isFluidOccluded(level, posX, posY, posZ, Direction.WEST, fluid);
        boolean sfEast = this.isFluidOccluded(level, posX, posY, posZ, Direction.EAST, fluid);

        if (sfUp && sfDown && sfEast && sfWest && sfNorth && sfSouth) {
            return;
        }

        boolean isWater = fluid.getColor() != 0xffffffff;

        final ColorProvider<BlockState> colorProvider = this.getColorProvider(fluid, BlockColors.INSTANCE.sodium$getProviders().get(fluid));

        Sprite[] sprites = spriteCache.forFluid(fluid);

        float fluidHeight = this.fluidHeight(level, fluid, blockPos, Direction.UP);
        float northWestHeight, southWestHeight, southEastHeight, northEastHeight;
        if (fluidHeight >= 1.0f) {
            northWestHeight = 1.0f;
            southWestHeight = 1.0f;
            southEastHeight = 1.0f;
            northEastHeight = 1.0f;
        } else {
            var scratchPos = new BlockPos.Mutable();
            float heightNorth = this.fluidHeight(level, fluid, scratchPos.setPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ()).offset(Direction.NORTH), Direction.NORTH);
            float heightSouth = this.fluidHeight(level, fluid, scratchPos.setPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ()).offset(Direction.SOUTH), Direction.SOUTH);
            float heightEast = this.fluidHeight(level, fluid, scratchPos.setPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ()).offset(Direction.EAST), Direction.EAST);
            float heightWest = this.fluidHeight(level, fluid, scratchPos.setPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ()).offset(Direction.WEST), Direction.WEST);
            northWestHeight = this.fluidCornerHeight(level, fluid, fluidHeight, heightNorth, heightWest, scratchPos.setPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ())
                    .offset(Direction.NORTH)
                    .offset(Direction.WEST));
            southWestHeight = this.fluidCornerHeight(level, fluid, fluidHeight, heightSouth, heightWest, scratchPos.setPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ())
                    .offset(Direction.SOUTH)
                    .offset(Direction.WEST));
            southEastHeight = this.fluidCornerHeight(level, fluid, fluidHeight, heightSouth, heightEast, scratchPos.setPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ())
                    .offset(Direction.SOUTH)
                    .offset(Direction.EAST));
            northEastHeight = this.fluidCornerHeight(level, fluid, fluidHeight, heightNorth, heightEast, scratchPos.setPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ())
                    .offset(Direction.NORTH)
                    .offset(Direction.EAST));
        }
        float yOffset = sfDown ? 0.0F : EPSILON;

        final ModelQuadViewMutable quad = this.quad;

        LightMode lightMode = isWater && MinecraftClient.isAmbientOcclusionEnabled() ? LightMode.SMOOTH : LightMode.FLAT;
        LightPipeline lighter = this.lighters.getLighter(lightMode);

        quad.setFlags(0);

        if (!sfUp && this.isSideExposed(level, posX, posY, posZ, Direction.UP, Math.min(Math.min(northWestHeight, southWestHeight), Math.min(southEastHeight, northEastHeight)))) {
            northWestHeight -= EPSILON;
            southWestHeight -= EPSILON;
            southEastHeight -= EPSILON;
            northEastHeight -= EPSILON;

            Vector3d velocity = WorldUtil.getVelocity(level, blockPos, fluidState);

            Sprite sprite;
            float u1, u2, u3, u4;
            float v1, v2, v3, v4;

            if (velocity.x == 0.0D && velocity.z == 0.0D) {
                sprite = sprites[0];
                u1 = sprite.getFrameU(0.0f);
                v1 = sprite.getFrameV(0.0f);
                u2 = u1;
                v2 = sprite.getFrameV(1.0f);
                u3 = sprite.getFrameU(1.0f);
                v3 = v2;
                u4 = u3;
                v4 = v1;
            } else {
                sprite = sprites[1];
                float dir = (float) MathHelper.atan2(velocity.z, velocity.x) - (1.5707964f);
                float sin = MathHelper.sin(dir) * 0.25F;
                float cos = MathHelper.cos(dir) * 0.25F;
                u1 = sprite.getFrameU(0.5F + (-cos - sin));
                v1 = sprite.getFrameV(0.5F + -cos + sin);
                u2 = sprite.getFrameU(0.5F + -cos + sin);
                v2 = sprite.getFrameV(0.5F + cos + sin);
                u3 = sprite.getFrameU(0.5F + cos + sin);
                v3 = sprite.getFrameV(0.5F + (cos - sin));
                u4 = sprite.getFrameU(0.5F + (cos - sin));
                v4 = sprite.getFrameV(0.5F + (-cos - sin));
            }

            float uAvg = (u1 + u2 + u3 + u4) / 4.0F;
            float vAvg = (v1 + v2 + v3 + v4) / 4.0F;

            float s1 = (float) sprites[0].getWidth() / (sprites[0].getMaxU() - sprites[0].getMinU());
            float s2 = (float) sprites[0].getHeight() / (sprites[0].getMaxV() - sprites[0].getMinV());
            float s3 = 4.0F / Math.max(s2, s1);

            u1 = Mth.lerp(s3, u1, uAvg);
            u2 = Mth.lerp(s3, u2, uAvg);
            u3 = Mth.lerp(s3, u3, uAvg);
            u4 = Mth.lerp(s3, u4, uAvg);
            v1 = Mth.lerp(s3, v1, vAvg);
            v2 = Mth.lerp(s3, v2, vAvg);
            v3 = Mth.lerp(s3, v3, vAvg);
            v4 = Mth.lerp(s3, v4, vAvg);

            quad.setSprite(sprite);

            // top surface alignedness is calculated with a more relaxed epsilon
            boolean aligned = isAlignedEquals(northEastHeight, northWestHeight)
                    && isAlignedEquals(northWestHeight, southEastHeight)
                    && isAlignedEquals(southEastHeight, southWestHeight)
                    && isAlignedEquals(southWestHeight, northEastHeight);

            boolean creaseNorthEastSouthWest = aligned
                    || northEastHeight > northWestHeight && northEastHeight > southEastHeight
                    || northEastHeight < northWestHeight && northEastHeight < southEastHeight
                    || southWestHeight > northWestHeight && southWestHeight > southEastHeight
                    || southWestHeight < northWestHeight && southWestHeight < southEastHeight;

            if (creaseNorthEastSouthWest) {
                setVertex(quad, 1, 0.0f, northWestHeight, 0.0f, u1, v1);
                setVertex(quad, 2, 0.0f, southWestHeight, 1.0F, u2, v2);
                setVertex(quad, 3, 1.0F, southEastHeight, 1.0F, u3, v3);
                setVertex(quad, 0, 1.0F, northEastHeight, 0.0f, u4, v4);
            } else {
                setVertex(quad, 0, 0.0f, northWestHeight, 0.0f, u1, v1);
                setVertex(quad, 1, 0.0f, southWestHeight, 1.0F, u2, v2);
                setVertex(quad, 2, 1.0F, southEastHeight, 1.0F, u3, v3);
                setVertex(quad, 3, 1.0F, northEastHeight, 0.0f, u4, v4);
            }

            this.updateQuad(quad, level, blockPos, lighter, Direction.UP, 1.0F, colorProvider, fluidState);
            this.writeQuad(meshBuilder, collector, material, offset, quad, aligned ? ModelQuadFacing.POS_Y : ModelQuadFacing.UNASSIGNED, false);

            if (WorldUtil.method_15756(level, this.scratchPos.setPosition(posX, posY + 1, posZ), fluid)) {
                this.writeQuad(meshBuilder, collector, material, offset, quad,
                        aligned ? ModelQuadFacing.NEG_Y : ModelQuadFacing.UNASSIGNED, true);
            }
        }

        if (!sfDown) {
            Sprite sprite = sprites[0];

            float minU = sprite.getMinU();
            float maxU = sprite.getMaxU();
            float minV = sprite.getMinV();
            float maxV = sprite.getMaxV();

            quad.setSprite(sprite);

            setVertex(quad, 0, 0.0f, yOffset, 1.0F, minU, maxV);
            setVertex(quad, 1, 0.0f, yOffset, 0.0f, minU, minV);
            setVertex(quad, 2, 1.0F, yOffset, 0.0f, maxU, minV);
            setVertex(quad, 3, 1.0F, yOffset, 1.0F, maxU, maxV);

            this.updateQuad(quad, level, blockPos, lighter, Direction.DOWN, 1.0F, colorProvider, fluidState);
            this.writeQuad(meshBuilder, collector, material, offset, quad, ModelQuadFacing.NEG_Y, false);
        }

        quad.setFlags(ModelQuadFlags.IS_PARALLEL | ModelQuadFlags.IS_ALIGNED);

        for (Direction dir : DirectionUtil.HORIZONTAL_DIRECTIONS) {
            float c1;
            float c2;
            float x1;
            float z1;
            float x2;
            float z2;

            switch (dir) {
                case NORTH -> {
                    if (sfNorth) {
                        continue;
                    }
                    c1 = northWestHeight;
                    c2 = northEastHeight;
                    x1 = 0.0f;
                    x2 = 1.0F;
                    z1 = EPSILON;
                    z2 = z1;
                }
                case SOUTH -> {
                    if (sfSouth) {
                        continue;
                    }
                    c1 = southEastHeight;
                    c2 = southWestHeight;
                    x1 = 1.0F;
                    x2 = 0.0f;
                    z1 = 1.0f - EPSILON;
                    z2 = z1;
                }
                case WEST -> {
                    if (sfWest) {
                        continue;
                    }
                    c1 = southWestHeight;
                    c2 = northWestHeight;
                    x1 = EPSILON;
                    x2 = x1;
                    z1 = 1.0F;
                    z2 = 0.0f;
                }
                case EAST -> {
                    if (sfEast) {
                        continue;
                    }
                    c1 = northEastHeight;
                    c2 = southEastHeight;
                    x1 = 1.0f - EPSILON;
                    x2 = x1;
                    z1 = 0.0f;
                    z2 = 1.0F;
                }
                default -> {
                    continue;
                }
            }

            if (this.isSideExposed(level, posX, posY, posZ, dir, Math.max(c1, c2))) {
                int adjX = posX + dir.getOffsetX();
                int adjY = posY + dir.getOffsetY();
                int adjZ = posZ + dir.getOffsetZ();

                Sprite sprite = sprites[1];

                boolean isOverlay = false;

                float u1 = sprite.getFrameU(0.0F);
                float u2 = sprite.getFrameU(0.5F);
                float v1 = sprite.getFrameV((1.0F - c1) * 0.5F);
                float v2 = sprite.getFrameV((1.0F - c2) * 0.5F);
                float v3 = sprite.getFrameV(0.5F);

                quad.setSprite(sprite);

                setVertex(quad, 0, x2, c2, z2, u2, v2);
                setVertex(quad, 1, x2, yOffset, z2, u2, v3);
                setVertex(quad, 2, x1, yOffset, z1, u1, v3);
                setVertex(quad, 3, x1, c1, z1, u1, v1);

                float br = dir.getAxis() == Direction.Axis.Z ? 0.8F : 0.6F;

                ModelQuadFacing facing = ModelQuadFacing.fromDirection(dir);

                this.updateQuad(quad, level, blockPos, lighter, dir, br, colorProvider, fluidState);
                this.writeQuad(meshBuilder, collector, material, offset, quad, facing, false);

                if (!isOverlay) {
                    this.writeQuad(meshBuilder, collector, material, offset, quad, facing.getOpposite(), true);
                }

            }
        }
    }

    private static boolean isAlignedEquals(float a, float b) {
        return Math.abs(a - b) <= ALIGNED_EQUALS_EPSILON;
    }

    private ColorProvider<BlockState> getColorProvider(AbstractFluidBlock fluid, IBlockColor handler) {
        var override = this.colorProviderRegistry.getColorProvider(fluid);

        if (override != null) {
            return override;
        }

        return DefaultColorProviders.adapt(handler);
    }

    private void updateQuad(ModelQuadView quad, LevelSlice level, BlockPos pos, LightPipeline lighter, Direction dir, float brightness,
                            ColorProvider<BlockState> colorProvider, BlockState fluidState) {
        QuadLightData light = this.quadLightData;
        lighter.calculate(quad, pos, light, null, dir, false, true);

        colorProvider.getColors(level, pos, scratchPos, fluidState, quad, this.quadColors);

        // multiply the per-vertex color against the combined brightness
        // the combined brightness is the per-vertex brightness multiplied by the block's brightness
        for (int i = 0; i < 4; i++) {
            this.quadColors[i] = ColorABGR.withAlpha(this.quadColors[i], light.br[i] * brightness);
        }
    }

    private void writeQuad(ChunkModelBuilder builder, TranslucentGeometryCollector collector, Material material, BlockPos offset, ModelQuadView quad,
                           ModelQuadFacing facing, boolean flip) {
        var vertices = this.vertices;

        for (int i = 0; i < 4; i++) {
            var out = vertices[flip ? (3 - i + 1) & 0b11 : i];
            out.x = offset.getX() + quad.getX(i);
            out.y = offset.getY() + quad.getY(i);
            out.z = offset.getZ() + quad.getZ(i);

            out.color = this.quadColors[i];
            out.u = quad.getTexU(i);
            out.v = quad.getTexV(i);
            out.light = this.quadLightData.lm[i];
        }

        Sprite sprite = quad.getSprite();

        if (sprite != null) {
            builder.addSprite(sprite);
        }

        if (material.isTranslucent() && collector != null) {
            int normal;
            if (facing.isAligned()) {
                normal = facing.getPackedAlignedNormal();
            } else {
                normal = quad.calculateNormal();
            }
            if (flip) {
                normal = NormI8.flipPacked(normal);
            }
            collector.appendQuad(normal, vertices, facing);
        }

        var vertexBuffer = builder.getVertexBuffer(facing);
        vertexBuffer.push(vertices, material);
    }

    private static void setVertex(ModelQuadViewMutable quad, int i, float x, float y, float z, float u, float v) {
        quad.setX(i, x);
        quad.setY(i, y);
        quad.setZ(i, z);
        quad.setTexU(i, u);
        quad.setTexV(i, v);
    }

    private float fluidCornerHeight(BlockView world, AbstractFluidBlock fluid, float fluidHeight, float fluidHeightX, float fluidHeightY, BlockPos blockPos) {
        if (fluidHeightY >= 1.0f || fluidHeightX >= 1.0f) {
            return 1.0f;
        }

        if (fluidHeightY > 0.0f || fluidHeightX > 0.0f) {
            float height = this.fluidHeight(world, fluid, blockPos, Direction.UP);

            if (height >= 1.0f) {
                return 1.0f;
            }

            this.modifyHeight(this.scratchHeight, this.scratchSamples, height);
        }

        this.modifyHeight(this.scratchHeight, this.scratchSamples, fluidHeight);
        this.modifyHeight(this.scratchHeight, this.scratchSamples, fluidHeightY);
        this.modifyHeight(this.scratchHeight, this.scratchSamples, fluidHeightX);

        float result = this.scratchHeight.floatValue() / this.scratchSamples.intValue();
        this.scratchHeight.setValue(0);
        this.scratchSamples.setValue(0);

        return result;
    }

    private void modifyHeight(MutableFloat totalHeight, MutableInt samples, float target) {
        if (target >= 0.8f) {
            totalHeight.add(target * 10.0f);
            samples.add(10);
        } else if (target >= 0.0f) {
            totalHeight.add(target);
            samples.increment();
        }
    }

    private float fluidHeight(BlockView world, AbstractFluidBlock fluid, BlockPos blockPos, Direction direction) {
        BlockState blockState = world.getBlockState(blockPos);

        return WorldUtil.getFluidHeight(fluid, fluid.getMeta(blockState));
    }
}