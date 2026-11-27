package etcodehome.freeterraforged.world.worldgen.surface.rule;

import etcodehome.freeterraforged.data.worldgen.preset.settings.MiscellaneousSettings;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.IChunkCellsHolder;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.geology.RockType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;

/** Replace a generic stone decision without erasing biome soil, snow, or bedrock. */
public final class GeologicalSurfaceRules {
    private GeologicalSurfaceRules() {}

    public static SurfaceRules.SurfaceRule compose(SurfaceRules.SurfaceRule biome, SurfaceRules.SurfaceRule geology) {
        return (x, y, z) -> {
            BlockState surface = biome.tryApply(x, y, z);
            if (surface != null && !surface.is(Blocks.STONE) && !isSoil(surface)) {
                return surface;
            }
            return select(surface, geology.tryApply(x, y, z));
        };
    }

    public static SurfaceRules.SurfaceRule geology(SurfaceRules.Context context, GeneratorContext generator) {
        SurfaceRules.SurfaceRule floor = SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR,
            SurfaceRules.state(Blocks.GRAVEL.defaultBlockState())).apply(context);
        BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
        var chunkPos = context.chunk.getPos();
        Cell[] chunkCells = context.chunk instanceof IChunkCellsHolder holder
            ? holder.freeterraforged$getOrCreateChunkCells(generator) : null;
        boolean oreCompatibleOnly = generator.preset.miscellaneous().oreCompatibleStoneOnly;
        // Rules are context-local; reuse a river column for the vertical surface queries.
        return new SurfaceRules.SurfaceRule() {
            private final Cell fallback = new Cell();
            private Cell column;
            private int lastX;
            private int lastZ;

            @Override
            public BlockState tryApply(int x, int y, int z) {
                if (this.column == null || this.lastX != x || this.lastZ != z) {
                    if (chunkCells != null && (x >> 4) == chunkPos.x && (z >> 4) == chunkPos.z) {
                        this.column = chunkCells[((z & 15) << 4) | (x & 15)];
                    } else {
                        generator.lookup.applyCell(this.fallback.reset(), x, z, true, false);
                        this.column = this.fallback;
                    }
                    this.lastX = x;
                    this.lastZ = z;
                }
                BlockState material = materialFor(generator.surfaceGeology.sample(x, y, z, this.column,
                    generator.levels.terrainScaleFactor).type(), oreCompatibleOnly);
                if (material.is(Blocks.GRAVEL)) {
                    support.set(x, y - 1, z);
                    if (floor.tryApply(x, y, z) == null
                        || !context.chunk.getBlockState(support).isFaceSturdy(context.chunk, support, Direction.UP)) {
                        return Blocks.TUFF.defaultBlockState();
                    }
                }
                return material;
            }
        };
    }

    public static BlockState materialFor(RockType type, boolean oreCompatibleOnly) {
        BlockState state = switch (type) {
            case STONE -> Blocks.STONE.defaultBlockState();
            case GRANITE -> Blocks.GRANITE.defaultBlockState();
            case DIORITE -> Blocks.DIORITE.defaultBlockState();
            case ANDESITE -> Blocks.ANDESITE.defaultBlockState();
            case CALCITE -> Blocks.CALCITE.defaultBlockState();
            case TUFF -> Blocks.TUFF.defaultBlockState();
            case DRIPSTONE -> Blocks.DRIPSTONE_BLOCK.defaultBlockState();
            case PACKED_MUD -> Blocks.PACKED_MUD.defaultBlockState();
            case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
            case BLACKSTONE -> Blocks.BLACKSTONE.defaultBlockState();
            case DEEPSLATE -> Blocks.DEEPSLATE.defaultBlockState();
            case BASALT -> Blocks.BASALT.defaultBlockState();
        };
        return oreCompatibleOnly && !state.is(BlockTags.STONE_ORE_REPLACEABLES)
            ? Blocks.STONE.defaultBlockState() : state;
    }

    /** Extra decorative stones inherit the native stone decision during cave carving. */
    public static BlockState carverSubstrate(BlockState state, MiscellaneousSettings settings) {
        if (settings.strataDecorator && settings.structuredStrata && !settings.oreCompatibleStoneOnly
            && (state.is(Blocks.BLACKSTONE) || state.is(Blocks.BASALT)
                || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.PACKED_MUD))) {
            return Blocks.STONE.defaultBlockState();
        }
        return state;
    }

    private static boolean isSoil(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.DIRT);
    }

    public static BlockState select(@Nullable BlockState biome, BlockState geology) {
        if (biome == null || biome.is(Blocks.STONE)) {
            return geology;
        }
        // A supported gravel lens can be exposed through ordinary soil cover.
        if (geology.is(Blocks.GRAVEL) && isSoil(biome)) {
            return geology;
        }
        return biome;
    }
}
