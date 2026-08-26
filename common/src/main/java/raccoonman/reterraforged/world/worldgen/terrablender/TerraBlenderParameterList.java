package raccoonman.reterraforged.world.worldgen.terrablender;

import java.util.List;

import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;

/**
 * Source-local marker used by the ordinary biome-source path without linking it to TerraBlender.
 */
public interface TerraBlenderParameterList<T> {
	void reterraforged$preparePreview(Preset preset, long seed);

	boolean reterraforged$isTerraBlenderInitialized();

	T reterraforged$applyUndergroundBanding(Climate.TargetPoint target, int x, int y, int z, T selected);

	T reterraforged$applyUndergroundSurfaceProtection(
		Climate.TargetPoint target,
		int x,
		int y,
		int z,
		T selected,
		float surfaceCoverageFactor
	);

	CompositionDiagnostics<T> reterraforged$getCompositionDiagnostics();

	SelectionDiagnostics<T> reterraforged$inspectSelection(Climate.TargetPoint target, int x, int y, int z);

	record CompositionDiagnostics<T>(
		int regionCount,
		List<Integer> sourceEntryCounts,
		int canonicalEntryCount,
		int duplicateEntryCount,
		int lateGlobalEntryCount,
		int excludedEntryCount,
		int invalidEntryCount,
		List<Integer> invalidRegions,
		int alternativePointCount,
		int replacedCaveSlotCount,
		int shallowCandidateCount,
		int deepCandidateCount,
		List<T> shallowCandidates,
		List<T> deepCandidates,
		int unknownEntryCount,
		int classificationFailureCount,
		String fallbackReason
	) {
	}

	record SelectionDiagnostics<T>(
		int selectedRegion,
		T original,
		T banded,
		String fallbackReason
	) {
		public boolean usedFallback() {
			return this.fallbackReason != null;
		}
	}
}
