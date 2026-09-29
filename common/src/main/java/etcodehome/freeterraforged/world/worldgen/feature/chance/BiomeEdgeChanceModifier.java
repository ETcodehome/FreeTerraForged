package etcodehome.freeterraforged.world.worldgen.feature.chance;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.densityfunction.tile.Tile;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

class BiomeEdgeChanceModifier extends RangeChanceModifier {
	public static final MapCodec<BiomeEdgeChanceModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.FLOAT.fieldOf("from").forGetter((o) -> o.from),
		Codec.FLOAT.fieldOf("to").forGetter((o) -> o.to),
		Codec.BOOL.fieldOf("exclusive").forGetter((o) -> o.exclusive)
	).apply(instance, BiomeEdgeChanceModifier::new));
	
	public BiomeEdgeChanceModifier(float from, float to, boolean exclusive) {
		super(from, to, exclusive);
	}

	@Override
	public MapCodec<BiomeEdgeChanceModifier> codec() {
		return CODEC;
	}

	@Override
	protected float getValue(ChanceContext chanceCtx, FeaturePlaceContext<?> placeCtx) {
		BlockPos pos = placeCtx.origin();
		if((Object) placeCtx.level().getLevel().getChunkSource().randomState() instanceof FTFRandomState ftfRandomState) {
			GeneratorContext generatorContext = ftfRandomState.generatorContext();
			if (generatorContext != null && generatorContext.cache != null) {
				int x = pos.getX();
				int z = pos.getZ();
				int chunkX = SectionPos.blockToSectionCoord(x);
				int chunkZ = SectionPos.blockToSectionCoord(z);
				Tile tile = generatorContext.cache.provideAtChunk(chunkX, chunkZ);
				if (tile != null) {
					Tile.Chunk chunk = tile.getChunkReader(chunkX, chunkZ);
					if (chunk != null) {
						return chunk.getCell(x, z).biomeRegionEdge;
					}
				}
			}
		}
		return 1.0F;
	}
}
