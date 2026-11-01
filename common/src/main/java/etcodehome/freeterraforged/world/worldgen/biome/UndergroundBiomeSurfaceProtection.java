package etcodehome.freeterraforged.world.worldgen.biome;

import java.lang.ref.WeakReference;

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
	private static final float EXTRA_SAFETY_MARGIN_BLOCKS = 16.0F;

	private static final class CoverageMru {
		int quartX = Integer.MIN_VALUE;
		int quartY = Integer.MIN_VALUE;
		int quartZ = Integer.MIN_VALUE;
		WeakReference<Climate.Sampler> samplerRef;
		float coverage;
	}
	private static final ThreadLocal<CoverageMru> COVERAGE_MRU = ThreadLocal.withInitial(CoverageMru::new);

	private UndergroundBiomeSurfaceProtection() {
	}

	public static float coverageFactor(
			Climate.Sampler sampler,
			Climate.TargetPoint target,
			int quartX,
			int quartY,
			int quartZ
	) {
		CoverageMru mru = COVERAGE_MRU.get();
		Climate.Sampler cachedSampler = mru.samplerRef != null ? mru.samplerRef.get() : null;
		if (mru.quartX == quartX && mru.quartY == quartY && mru.quartZ == quartZ && cachedSampler == sampler) {
			return mru.coverage;
		}
		float coverage = computeCoverageFactor(sampler, target, quartX, quartY, quartZ);
		mru.quartX = quartX;
		mru.quartY = quartY;
		mru.quartZ = quartZ;
		mru.samplerRef = new WeakReference<>(sampler);
		mru.coverage = coverage;
		return coverage;
	}

	public static void clearThreadCaches() {
		CoverageMru mru = COVERAGE_MRU.get();
		mru.samplerRef = null;
		mru.quartX = Integer.MIN_VALUE;
		mru.quartY = Integer.MIN_VALUE;
		mru.quartZ = Integer.MIN_VALUE;
	}

	private static float computeCoverageFactor(
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

		if (localClearance <= EXTRA_SAFETY_MARGIN_BLOCKS + REQUIRED_CLEARANCE_BLOCKS) {
			return 0.0F;
		}
		if ((Object) sampler instanceof FTFClimateSampler ftfSampler) {
			GeneratorContext context = ftfSampler.climateQuerySemantics().surfaceContext();
			if (context != null) {
				int minimumSurfaceY = context.biomeSurfaceEnvelope.minimumSurfaceY(context, quartX, quartZ);
				float envelopeClearance = minimumSurfaceY - QuartPos.toBlock(quartY)
					- BiomeSurfaceEnvelope.MAX_VERTICAL_OFFSET;
				localClearance = Math.min(localClearance, envelopeClearance);
			}
		}

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
