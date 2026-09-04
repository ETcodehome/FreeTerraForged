package raccoonman.reterraforged.world.worldgen.runtime;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseSettings;

public record NoiseFillExtent(
	int minY,
	int height,
	int cellHeight,
	int minCellY,
	int cellCountY
) {
	public static NoiseFillExtent fullConfiguredHeight(
		NoiseSettings configured,
		LevelHeightAccessor generationHeight
	) {
		if (configured == null) {
			throw new NullPointerException("configured");
		}
		if (generationHeight == null) {
			throw new NullPointerException("generationHeight");
		}
		return fromClamped(configured.clampToHeightAccessor(generationHeight));
	}

	public static NoiseFillExtent fromClamped(NoiseSettings settings) {
		if (settings == null) {
			throw new NullPointerException("settings");
		}
		int cellHeight = settings.getCellHeight();
		if (cellHeight <= 0) {
			throw new IllegalArgumentException("Noise cell height must be positive: " + cellHeight);
		}
		int minY = settings.minY();
		int height = Math.max(0, settings.height());
		if (Math.floorMod(minY, cellHeight) != 0 || Math.floorMod(height, cellHeight) != 0) {
			throw new IllegalArgumentException(
				"Noise range [" + minY + ", " + Math.addExact(minY, height)
					+ ") is not aligned to cell height " + cellHeight
			);
		}
		return new NoiseFillExtent(
			minY,
			height,
			cellHeight,
			Math.floorDiv(minY, cellHeight),
			Math.floorDiv(height, cellHeight)
		);
	}

	public NoiseFillExtent {
		if (cellHeight <= 0) {
			throw new IllegalArgumentException("cellHeight must be positive");
		}
		if (height < 0) {
			throw new IllegalArgumentException("height must not be negative");
		}
		Math.addExact(minY, height);
		if (Math.multiplyExact(minCellY, cellHeight) != minY) {
			throw new IllegalArgumentException("minCellY does not describe minY");
		}
		if (Math.multiplyExact(cellCountY, cellHeight) != height) {
			throw new IllegalArgumentException("cellCountY does not describe height");
		}
	}

	public int maxYExclusive() {
		return Math.addExact(this.minY, this.height);
	}

	public boolean empty() {
		return this.cellCountY == 0;
	}
}
