package etcodehome.freeterraforged.world.worldgen.feature.chance;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.jetbrains.annotations.Nullable;

class ElevationChanceModifier extends RangeChanceModifier {
	public static final MapCodec<ElevationChanceModifier> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.FLOAT.fieldOf("from").forGetter((o) -> o.from),
		Codec.FLOAT.fieldOf("to").forGetter((o) -> o.to),
		Codec.BOOL.fieldOf("exclusive").forGetter((o) -> o.exclusive)
	).apply(instance, ElevationChanceModifier::new));
	
	public ElevationChanceModifier(float from, float to, boolean exclusive) {
		super(from, to, exclusive);
	}

	@Override
	public MapCodec<ElevationChanceModifier> codec() {
		return CODEC;
	}

	@Override
	protected float getValue(ChanceContext chanceCtx, FeaturePlaceContext<?> placeCtx) {
		BlockPos pos = placeCtx.origin();
		@Nullable
        GeneratorContext generatorContext;
		if((Object) placeCtx.level().getLevel().getChunkSource().randomState() instanceof FTFRandomState ftfRandomState && (generatorContext = ftfRandomState.generatorContext()) != null) {
			int x = pos.getX();
			int z = pos.getZ();
			Cell cell = new Cell();
			generatorContext.lookup.applyCell(cell, x, z, false, false);
			return generatorContext.heightmap.levels().elevation(cell.height);
		} else {
			throw new UnsupportedOperationException();
		}
	}
}
