package etcodehome.freeterraforged.world.worldgen.biome;

import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseRouterData;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;

public final class UndergroundBiomeSurfaceProtection {
	public static final int HARD_SHELL_BLOCKS = QuartPos.SIZE;
	public static final int TRANSITION_BLOCKS = 24;
	static final int REQUIRED_CLEARANCE_BLOCKS = QuartPos.SIZE + HARD_SHELL_BLOCKS;

	private static final float DEPTH_UNITS_PER_BLOCK = 1.0F / 128.0F;
	private static final float SURFACE_DEPTH = NoiseRouterData.GLOBAL_OFFSET + 0.5F;
	private static final float EXTRA_SAFETY_MARGIN_BLOCKS = 8.0F;

	private UndergroundBiomeSurfaceProtection() {
	}

	public static float coverageFactor(
			Climate.Sampler sampler,
			Climate.TargetPoint target,
			int quartX,
			int quartY,
			int quartZ
	) {
		// Base unquantized clearance from Minecraft's NoiseRouter depth
		float localClearance = (
				Climate.unquantizeCoord(target.depth()) - SURFACE_DEPTH
		) / DEPTH_UNITS_PER_BLOCK;

		// Shift clearance downward to prevent surface breakthrough
		float paddedClearance = localClearance - EXTRA_SAFETY_MARGIN_BLOCKS;

		return coverageFactor(paddedClearance);
	}

	static float coverageFactor(float minimumSurfaceClearanceBlocks) {
		return Math.clamp(
				(minimumSurfaceClearanceBlocks - REQUIRED_CLEARANCE_BLOCKS) / TRANSITION_BLOCKS,
				0.0F,
				1.0F
		);
	}

	public static int sampleSurfaceY(
			GeneratorContext context,
			Cell cell,
			int blockX,
			int blockZ
	) {
		context.lookup.applyCell(
				cell.reset(),
				blockX,
				blockZ,
				false
		);
		return context.levels.scale(cell.height);
	}
}