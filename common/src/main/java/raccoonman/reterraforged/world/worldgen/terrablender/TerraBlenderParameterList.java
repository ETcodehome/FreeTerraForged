package raccoonman.reterraforged.world.worldgen.terrablender;

import net.minecraft.world.level.biome.Climate;

/**
 * Source-local marker used by the ordinary biome-source path without linking it to TerraBlender.
 */
public interface TerraBlenderParameterList<T> {
	boolean reterraforged$isTerraBlenderInitialized();

	T reterraforged$applyUndergroundBanding(Climate.TargetPoint target, int x, int y, int z, T selected);
}
