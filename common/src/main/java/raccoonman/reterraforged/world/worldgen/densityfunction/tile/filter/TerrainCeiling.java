package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

/**
 * Smoothly compresses exceptionally tall terrain into the configured dimension
 * when mountain variability is enabled, instead of allowing the result to be
 * truncated at the build ceiling.
 */
public record TerrainCeiling(float compressionStart, float linearEnd, float tailStart, float maximum) implements Filter {
	static final int SURFACE_HEADROOM_BLOCKS = 16;
	static final int COMPRESSION_BAND_BLOCKS = 112;
	static final int TAIL_HEADROOM_BLOCKS = 8;

	@Override
	public void apply(Filterable map, int seedX, int seedZ, int iterationsPerChunk) {
		this.iterate(map, (source, cell, dx, dz) ->
			cell.height = compress(cell.height, this.compressionStart, this.linearEnd, this.tailStart, this.maximum)
		);
	}

	static float compress(float height, float compressionStart, float linearEnd, float tailStart, float maximum) {
		if (height <= compressionStart) {
			return height;
		}

		float sourceRange = linearEnd - compressionStart;
		float targetRange = tailStart - compressionStart;
		if (sourceRange <= 0.0F || targetRange <= 0.0F) {
			return Math.min(height, maximum);
		}

		float slope = targetRange / sourceRange;
		if (height <= linearEnd) {
			return compressionStart + (height - compressionStart) * slope;
		}

		float tailRoom = maximum - tailStart;
		float excess = height - linearEnd;
		float softness = tailRoom / slope;
		return tailStart + tailRoom * excess / (softness + excess);
	}

	public static TerrainCeiling make(WorldSettings.Properties properties) {
		int terrainScaler = Math.max(1, Math.min(properties.worldHeight, 256));
		int availableHeight = Math.max(1, properties.worldHeight - properties.seaLevel);
		int surfaceHeadroom = Math.min(SURFACE_HEADROOM_BLOCKS, Math.max(1, availableHeight / 8));
		int compressionBand = Math.min(COMPRESSION_BAND_BLOCKS, Math.max(1, availableHeight / 2));
		int tailHeadroom = Math.min(TAIL_HEADROOM_BLOCKS, Math.max(1, compressionBand / 8));
		float maximum = (properties.worldHeight - surfaceHeadroom) / (float) terrainScaler;
		float compressionStart = (properties.worldHeight - surfaceHeadroom - compressionBand) / (float) terrainScaler;
		float linearEnd = compressionStart + 1.0F;
		float tailStart = (properties.worldHeight - surfaceHeadroom - tailHeadroom) / (float) terrainScaler;
		return new TerrainCeiling(compressionStart, linearEnd, tailStart, maximum);
	}
}
