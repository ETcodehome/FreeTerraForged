package etcodehome.freeterraforged.world.worldgen.densityfunction.tile.filter;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;

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
		int terrainScaler = terrainScaler(properties);
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

	public static boolean isEnabled(Preset preset) {
		return preset.terrain().general.mountainVariety > 0.0F;
	}

	/**
	 * Block height above which terrain has gone over the build limit. Without the ceiling this is the
	 * limit itself. With it, tall terrain is compressed below the limit instead of being cut off, so this
	 * is the height that terrain reaching exactly the limit is compressed to; compression is monotonic,
	 * so anything above it would otherwise have exceeded the limit.
	 */
	public static int buildLimitHeight(WorldSettings.Properties properties, boolean enabled) {
		if (!enabled) {
			return properties.worldHeight;
		}
		TerrainCeiling ceiling = make(properties);
		int terrainScaler = terrainScaler(properties);
		float limit = compress(properties.worldHeight / (float) terrainScaler, ceiling.compressionStart, ceiling.linearEnd, ceiling.tailStart, ceiling.maximum);
		return (int) (limit * terrainScaler);
	}

	private static int terrainScaler(WorldSettings.Properties properties) {
		return Math.max(1, Math.min(properties.worldHeight, 256));
	}
}
