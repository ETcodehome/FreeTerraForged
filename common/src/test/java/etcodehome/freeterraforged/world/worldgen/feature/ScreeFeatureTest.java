package etcodehome.freeterraforged.world.worldgen.feature;

import com.mojang.serialization.Lifecycle;
import etcodehome.freeterraforged.tags.FTFBlockTags;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import etcodehome.freeterraforged.world.worldgen.geology.GeologySampler;
import etcodehome.freeterraforged.world.worldgen.geology.RockSample;
import etcodehome.freeterraforged.world.worldgen.geology.RockType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreeFeatureTest {
    private static final Map<TagKey<Block>, List<Holder<Block>>> previousTags = new HashMap<>();
    private static Registry<Biome> biomes;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var blocks = BuiltInRegistries.BLOCK;
        blocks.getTags().forEach(tag -> previousTags.put(tag.getFirst(), tag.getSecond().stream().toList()));
        var tags = new HashMap<>(previousTags);
        tags.put(FTFBlockTags.ERODIBLE, List.of(
            blocks.wrapAsHolder(Blocks.GRASS_BLOCK), blocks.wrapAsHolder(Blocks.DIRT)));
        blocks.bindTags(tags);

        var registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        var plains = new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8F).downfall(0.4F)
            .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0).waterFogColor(0).fogColor(0).skyColor(0).build())
            .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build();
        registry.register(Biomes.PLAINS, plains, new RegistrationInfo(Optional.empty(), Lifecycle.stable()));
        biomes = registry.freeze();
    }

    @AfterAll
    static void restoreTags() {
        previousTags.putIfAbsent(FTFBlockTags.ERODIBLE, List.of());
        BuiltInRegistries.BLOCK.bindTags(previousTags);
    }

    @ParameterizedTest
    @ValueSource(ints = {70, 310})
    void exposesEachBedAtItsOwnElevationAndStopsAtIntactRock(int surfaceY) {
        ProtoChunk chunk = makeChunk();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(5, surfaceY, 7);
        chunk.setBlockState(pos, Blocks.GRASS_BLOCK.defaultBlockState(), false);
        for (int depth = 1; depth <= 3; depth++) {
            chunk.setBlockState(pos.setY(surfaceY - depth), Blocks.DIRT.defaultBlockState(), false);
        }
        chunk.setBlockState(pos.setY(surfaceY - 4), Blocks.IRON_ORE.defaultBlockState(), false);
        chunk.setBlockState(pos.setY(surfaceY - 5), Blocks.DIRT.defaultBlockState(), false);
        GeologySampler geology = (x, y, z) -> new RockSample(switch (surfaceY - (int) y) {
            case 0 -> RockType.CALCITE;
            case 1 -> RockType.PACKED_MUD;
            case 2 -> RockType.DRIPSTONE;
            default -> RockType.GRAVEL;
        }, y);

        ScreeFeature.erodeGeologicalCover(chunk, pos, surfaceY, geology, new Cell(), 512, false);

        assertEquals(Blocks.CALCITE.defaultBlockState(), chunk.getBlockState(pos.setY(surfaceY)));
        assertEquals(Blocks.PACKED_MUD.defaultBlockState(), chunk.getBlockState(pos.setY(surfaceY - 1)));
        assertEquals(Blocks.DRIPSTONE_BLOCK.defaultBlockState(), chunk.getBlockState(pos.setY(surfaceY - 2)));
        assertEquals(Blocks.TUFF.defaultBlockState(), chunk.getBlockState(pos.setY(surfaceY - 3)));
        assertEquals(Blocks.IRON_ORE.defaultBlockState(), chunk.getBlockState(pos.setY(surfaceY - 4)));
        assertEquals(Blocks.DIRT.defaultBlockState(), chunk.getBlockState(pos.setY(surfaceY - 5)));
    }

    @Test
    void requiresSupportForGravelAndLeavesCaveOpen() {
        ProtoChunk chunk = makeChunk();
        BlockPos.MutableBlockPos supported = new BlockPos.MutableBlockPos(5, 100, 7);
        chunk.setBlockState(supported, Blocks.DIRT.defaultBlockState(), false);
        chunk.setBlockState(supported.below(), Blocks.STONE.defaultBlockState(), false);
        BlockPos.MutableBlockPos unsupported = new BlockPos.MutableBlockPos(6, 100, 7);
        chunk.setBlockState(unsupported, Blocks.DIRT.defaultBlockState(), false);
        chunk.setBlockState(unsupported.below(2), Blocks.DIRT.defaultBlockState(), false);
        GeologySampler gravel = (x, y, z) -> new RockSample(RockType.GRAVEL, y);

        ScreeFeature.erodeGeologicalCover(chunk, supported, 100, gravel, new Cell(), 512, false);
        ScreeFeature.erodeGeologicalCover(chunk, unsupported, 100, gravel, new Cell(), 512, false);

        assertEquals(Blocks.GRAVEL.defaultBlockState(), chunk.getBlockState(supported.setY(100)));
        assertEquals(Blocks.TUFF.defaultBlockState(), chunk.getBlockState(unsupported.setY(100)));
        assertEquals(Blocks.AIR.defaultBlockState(), chunk.getBlockState(unsupported.below()));
        assertEquals(Blocks.DIRT.defaultBlockState(), chunk.getBlockState(unsupported.below(2)));
    }

    @Test
    void honorsOreCompatibleStoneSetting() {
        ProtoChunk chunk = makeChunk();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(5, 150, 7);
        chunk.setBlockState(pos, Blocks.DIRT.defaultBlockState(), false);
        GeologySampler calcite = (x, y, z) -> new RockSample(RockType.CALCITE, y);

        ScreeFeature.erodeGeologicalCover(chunk, pos, 150, calcite, new Cell(), 512, true);

        assertEquals(Blocks.STONE.defaultBlockState(), chunk.getBlockState(pos.setY(150)));
    }

    private static ProtoChunk makeChunk() {
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), biomes, null);
    }
}
