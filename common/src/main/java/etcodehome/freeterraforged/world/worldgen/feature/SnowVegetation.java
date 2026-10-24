package etcodehome.freeterraforged.world.worldgen.feature;

import etcodehome.freeterraforged.tags.FTFBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class SnowVegetation {
	private SnowVegetation() {
	}

	/** Allows worldgen snow to replace tagged plants using vanilla's snow conditions. */
	public static boolean canCover(Biome biome, LevelReader level, BlockPos pos) {
		if (biome.warmEnoughToRain(pos)
			|| pos.getY() < level.getMinBuildHeight()
			|| pos.getY() >= level.getMaxBuildHeight()
			|| level.getBrightness(LightLayer.BLOCK, pos) >= 10) {
			return false;
		}

		BlockState state = level.getBlockState(pos);
		// Never leave the upper half of a plant behind, remove a block entity, or drain fluid.
		return state.is(FTFBlockTags.SNOW_COVERABLE)
			&& !(state.getBlock() instanceof DoublePlantBlock)
			&& !state.hasBlockEntity()
			&& state.getFluidState().isEmpty()
			&& Blocks.SNOW.defaultBlockState().canSurvive(level, pos);
	}
}
