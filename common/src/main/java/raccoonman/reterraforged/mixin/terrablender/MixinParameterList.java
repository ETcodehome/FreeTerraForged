package raccoonman.reterraforged.mixin.terrablender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.biome.ClimateParameterListComposition;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeSurfaceQuery;
import raccoonman.reterraforged.world.worldgen.biome.PreviewBiomeQueryContext;
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeTags;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderParameterList;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;

@Mixin(
	value = Climate.ParameterList.class,
	priority = 1001
)
class MixinParameterList<T> implements TerraBlenderParameterList<T> {
	private int maxIndex;

	@Shadow
	private List<Pair<Climate.ParameterPoint, T>> values;

	@Unique
	private Preset reterraforged$bandingPreset;
	@Unique
	private Preset reterraforged$previewPreset;
	@Unique
	private long reterraforged$previewSeed;
	@Unique
	private long reterraforged$bandingSeed;
	@Unique
	private List<Pair<Climate.ParameterPoint, T>> reterraforged$baseEntries;
	@Unique
	private List<List<Pair<Climate.ParameterPoint, T>>> reterraforged$pendingRegionalEntries;
	@Unique
	private List<List<Pair<Climate.ParameterPoint, T>>> reterraforged$regionalEntries;
	@Unique
	private volatile List<Pair<Climate.ParameterPoint, T>> reterraforged$composedValuesReference;
	@Unique
	private volatile ClimateParameterListComposition.Snapshot<T> reterraforged$compositionSnapshot;
	@Unique
	private volatile List<Climate.ParameterList<T>> reterraforged$regionalSurfaceTrees;
	@Unique
	private volatile List<UndergroundBiomeBanding.Layout<T>> reterraforged$regionalBanding;
	@Unique
	private volatile List<T> reterraforged$shallowCandidateValues;
	@Unique
	private volatile List<T> reterraforged$deepCandidateValues;
	@Unique
	private volatile int reterraforged$replacedCaveSlotCount;
	@Unique
	private volatile String reterraforged$compositionFallbackReason;
	@Unique
	private boolean reterraforged$bandingInitialized;

	@Inject(
		at = @At("HEAD"),
		method = "initializeForTerraBlender",
		require = 1
	)
	public void initializeForTerraBlender(RegistryAccess registryAccess, RegionType regionType, long seed, CallbackInfo callback) {
		this.maxIndex = Regions.getCount(regionType) - 1;
		if (this.reterraforged$bandingInitialized) {
			return;
		}
		if (this.reterraforged$pendingRegionalEntries == null) {
			this.reterraforged$pendingRegionalEntries = new ArrayList<>();
		}
		if (this.reterraforged$regionalEntries == null) {
			this.reterraforged$regionalEntries = new ArrayList<>();
		}
		this.reterraforged$bandingPreset = this.reterraforged$previewPreset;
		this.reterraforged$bandingSeed = this.reterraforged$previewPreset == null ? seed : this.reterraforged$previewSeed;
		this.reterraforged$baseEntries = List.copyOf(this.values);
		this.reterraforged$pendingRegionalEntries.clear();
		this.reterraforged$regionalEntries.clear();
		this.reterraforged$composedValuesReference = null;
		this.reterraforged$compositionSnapshot = null;
		this.reterraforged$regionalSurfaceTrees = List.of();
		this.reterraforged$regionalBanding = List.of();
		this.reterraforged$shallowCandidateValues = List.of();
		this.reterraforged$deepCandidateValues = List.of();
		this.reterraforged$replacedCaveSlotCount = 0;
		this.reterraforged$compositionFallbackReason = null;
		if (this.reterraforged$bandingPreset == null && regionType == RegionType.OVERWORLD) {
			registryAccess.lookup(RTFRegistries.PRESET)
					.flatMap(registry -> registry.get(Preset.KEY))
					.ifPresent(holder -> this.reterraforged$bandingPreset = holder.value());

			// Fallback to the preview preset stored in the thread-local/preview context
			// if the registry hasn't registered the live unsaved preset yet.
			if (this.reterraforged$bandingPreset == null) {
				this.reterraforged$bandingPreset = this.reterraforged$previewPreset;
			}
		}
//
//    	registryAccess.lookup(RTFRegistries.PRESET).flatMap((registry) -> {
//    		return registry.get(Preset.KEY);
//    	}).ifPresent((holder) -> {
//    		Preset preset = holder.value();
//        	TBCompat.setSurfaceRules(preset, (defaultRules) -> {
//        		return RTFSurfaceRuleData.overworld(preset, registryAccess.lookupOrThrow(Registries.DENSITY_FUNCTION), registryAccess.lookupOrThrow(RTFRegistries.NOISE), defaultRules);
//            });
//    	});
	}

	@Override
	public void reterraforged$preparePreview(Preset preset, long seed) {
		if (!this.reterraforged$bandingInitialized) {
			this.reterraforged$previewPreset = preset;
			this.reterraforged$previewSeed = seed;
		}
	}

	@ModifyArg(
			method = "initializeForTerraBlender",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/biome/Climate$RTree;create(Ljava/util/List;)Lnet/minecraft/world/level/biome/Climate$RTree;"
			),
			index = 0,
			require = 1
	)
	private List<Pair<Climate.ParameterPoint, T>> reterraforged$captureRegionalEntries(
			List<Pair<Climate.ParameterPoint, T>> entries
	) {
		List<Pair<Climate.ParameterPoint, T>> deduplicated = reterraforged$deduplicateEntries(entries);
		if (this.reterraforged$bandingPreset != null) {
			this.reterraforged$pendingRegionalEntries.add(deduplicated);
		}
		return deduplicated;
	}

	@Inject(
		method = "initializeForTerraBlender",
		at = @At("RETURN"),
		require = 1
	)
	private void reterraforged$indexRegionalEntries(
		RegistryAccess registryAccess,
		RegionType regionType,
		long seed,
		CallbackInfo callback
	) {
		if (this.reterraforged$bandingInitialized) {
			return;
		}
		if (this.reterraforged$bandingPreset == null) {
			this.reterraforged$compositionFallbackReason = "missing_overworld_preset";
			this.reterraforged$bandingInitialized = true;
			return;
		}

		try {
			int treeCount = this.getTreeCount();
			int capturedCount = this.reterraforged$pendingRegionalEntries.size();
			if (capturedCount != treeCount) {
				this.reterraforged$regionalEntries.clear();
				this.reterraforged$compositionFallbackReason = "capture_count_mismatch";
				RTFCommon.LOGGER.error(
					"TerraBlender region tree count ({}) does not match captured regional entry sets ({}); underground banding disabled",
					treeCount, capturedCount
				);
			} else {
				this.reterraforged$regionalEntries.addAll(this.reterraforged$pendingRegionalEntries);
			}
		} catch (RuntimeException exception) {
			this.reterraforged$regionalEntries.clear();
			this.reterraforged$compositionFallbackReason = "capture_exception";
			RTFCommon.LOGGER.error(
				"Failed to capture TerraBlender biome entries; underground banding disabled",
				exception
			);
		} finally {
			this.reterraforged$pendingRegionalEntries.clear();
			this.reterraforged$bandingInitialized = true;
		}
	}

	@Inject(
		method = "findValuePositional",
		at = @At("HEAD"),
		cancellable = true,
		require = 1
	)
	private void reterraforged$selectComposedBiome(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z,
		CallbackInfoReturnable<T> callback
	) {
		T banded = this.reterraforged$selectBanded(targetPoint, x, y, z, null, false);
		if (banded != null) {
			callback.setReturnValue(banded);
		}
	}

	@Override
	public T reterraforged$applyUndergroundBanding(Climate.TargetPoint targetPoint, int x, int y, int z, T selected) {
		T banded = this.reterraforged$selectBanded(targetPoint, x, y, z, selected, true);
		return banded == null ? selected : banded;
	}

	@Override
	public T reterraforged$applyUndergroundSurfaceProtection(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z,
		T selected,
		float surfaceCoverageFactor
	) {
		if (surfaceCoverageFactor >= 1.0F
			|| this.reterraforged$bandingPreset == null
			|| !this.reterraforged$ensureComposedTrees()) {
			return selected;
		}

		int treeIndex;
		try {
			treeIndex = this.reterraforged$getUniqueness(targetPoint, x, y, z);
		} catch (RuntimeException exception) {
			return selected;
		}
		List<UndergroundBiomeBanding.Layout<T>> layouts = this.reterraforged$regionalBanding;
		if (treeIndex < 0 || treeIndex >= layouts.size()) {
			return selected;
		}
		UndergroundBiomeBanding.Layout<T> layout = layouts.get(treeIndex);
		if (layout == null || !layout.isCaveCandidate(selected)) {
			return selected;
		}
		if (surfaceCoverageFactor <= 0.0F || !layout.appliesAt(targetPoint)) {
			return layout.backgroundValue(targetPoint);
		}
		return layout.findValue(targetPoint, x, y, z, surfaceCoverageFactor);
	}

	@Unique
	private T reterraforged$selectBanded(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z,
		T requiredOriginal,
		boolean requireOriginalMatch
	) {
		if (this.reterraforged$bandingPreset == null || !this.reterraforged$ensureComposedTrees()) {
			return null;
		}
		int treeIndex;
		try {
			treeIndex = this.reterraforged$getUniqueness(targetPoint, x, y, z);
		} catch (RuntimeException exception) {
			return null;
		}
		ClimateParameterListComposition.Snapshot<T> snapshot = this.reterraforged$compositionSnapshot;
		List<Climate.ParameterList<T>> surfaceTrees = this.reterraforged$regionalSurfaceTrees;
		List<UndergroundBiomeBanding.Layout<T>> bandingTrees = this.reterraforged$regionalBanding;
		if (snapshot == null || !snapshot.usableForRegion(treeIndex)
			|| treeIndex >= surfaceTrees.size() || treeIndex >= bandingTrees.size()) {
			return null;
		}

		Climate.ParameterList<T> original = surfaceTrees.get(treeIndex);
		UndergroundBiomeBanding.Layout<T> banding = bandingTrees.get(treeIndex);
		if (original == null || banding == null) {
			return null;
		}
		T originalValue = original.findValue(targetPoint);
		if (reterraforged$isDeferredPlaceholder(originalValue)) {
			Climate.ParameterList<T> defaultTree = surfaceTrees.getFirst();
			if (defaultTree == null) {
				return null;
			}
			originalValue = defaultTree.findValue(targetPoint);
		}
		if (reterraforged$isDeferredPlaceholder(originalValue)
			|| (requireOriginalMatch && !Objects.equals(requiredOriginal, originalValue))) {
			return null;
		}
		float surfaceCoverageFactor = UndergroundBiomeSurfaceQuery.coverageFactor(targetPoint, x, y, z);
		T backgroundValue = banding.backgroundValue(targetPoint);
		T bandedValue;
		if (banding.appliesAt(targetPoint)) {
			bandedValue = banding.findValue(targetPoint, x, y, z, surfaceCoverageFactor);
		} else if ((surfaceCoverageFactor <= 0.0F
			|| this.reterraforged$bandingPreset.climate().biomeShape.undergroundBiomeCoverage() <= 0.0F)
			&& banding.isCaveCandidate(originalValue)) {
			bandedValue = backgroundValue;
		} else {
			bandedValue = originalValue;
		}
		if (reterraforged$isDeferredPlaceholder(bandedValue)) {
			return null;
		}
		PreviewBiomeQueryContext.record(x, y, z, originalValue, bandedValue);
		return bandedValue;
	}

	@Override
	public TerraBlenderParameterList.SelectionDiagnostics<T> reterraforged$inspectSelection(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z
	) {
		if (this.reterraforged$bandingPreset == null || !this.reterraforged$ensureComposedTrees()) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(
				-1, null, null, this.reterraforged$fallbackReason("composition_unavailable")
			);
		}

		int treeIndex;
		try {
			treeIndex = this.reterraforged$getUniqueness(targetPoint, x, y, z);
		} catch (RuntimeException exception) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(-1, null, null, "region_selection_exception");
		}
		ClimateParameterListComposition.Snapshot<T> snapshot = this.reterraforged$compositionSnapshot;
		if (snapshot == null || !snapshot.usableForRegion(treeIndex)) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "invalid_selected_region");
		}
		List<Climate.ParameterList<T>> surfaceTrees = this.reterraforged$regionalSurfaceTrees;
		List<UndergroundBiomeBanding.Layout<T>> bandingTrees = this.reterraforged$regionalBanding;
		if (treeIndex >= surfaceTrees.size() || treeIndex >= bandingTrees.size()) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "missing_composed_index");
		}
		UndergroundBiomeBanding.Layout<T> banding = bandingTrees.get(treeIndex);
		Climate.ParameterList<T> original = surfaceTrees.get(treeIndex);
		if (banding == null || original == null) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "missing_regional_index");
		}

		T originalValue = original.findValue(targetPoint);
		if (reterraforged$isDeferredPlaceholder(originalValue)) {
			Climate.ParameterList<T> defaultTree = surfaceTrees.getFirst();
			if (defaultTree == null) {
				return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "missing_default_index");
			}
			originalValue = defaultTree.findValue(targetPoint);
			if (reterraforged$isDeferredPlaceholder(originalValue)) {
				return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "deferred_surface_winner");
			}
		}

		T bandedValue = banding.appliesAt(targetPoint)
			? banding.findValue(targetPoint, x, y, z)
			: originalValue;
		if (reterraforged$isDeferredPlaceholder(bandedValue)) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, originalValue, null, "deferred_banded_winner");
		}
		return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, originalValue, bandedValue, null);
	}

	@Override
	public TerraBlenderParameterList.CompositionDiagnostics<T> reterraforged$getCompositionDiagnostics() {
		if (this.reterraforged$bandingInitialized) {
			this.reterraforged$ensureComposedTrees();
		}
		ClimateParameterListComposition.Snapshot<T> snapshot = this.reterraforged$compositionSnapshot;
		if (snapshot == null) {
			return new TerraBlenderParameterList.CompositionDiagnostics<>(
				this.reterraforged$regionalEntries == null ? 0 : this.reterraforged$regionalEntries.size(),
				this.reterraforged$regionalEntries == null
					? List.of()
					: this.reterraforged$regionalEntries.stream().map(entries -> entries == null ? -1 : entries.size()).toList(),
				0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, List.of(), List.of(), 0, 0,
				this.reterraforged$fallbackReason("composition_not_built")
			);
		}
		return new TerraBlenderParameterList.CompositionDiagnostics<>(
			snapshot.effectiveRegions().size(),
			snapshot.effectiveRegions().stream().map(List::size).toList(),
			snapshot.canonicalEntries().size(),
			snapshot.duplicateEntryCount(),
			snapshot.globalAdditions().size(),
			snapshot.excludedEntryCount(),
			snapshot.invalidEntryCount(),
			snapshot.invalidRegions().stream().sorted().toList(),
			snapshot.alternativePointCount(),
			this.reterraforged$replacedCaveSlotCount,
			this.reterraforged$shallowCandidateValues.size(),
			this.reterraforged$deepCandidateValues.size(),
			this.reterraforged$shallowCandidateValues,
			this.reterraforged$deepCandidateValues,
			0,
			0,
			this.reterraforged$compositionFallbackReason
		);
	}

	@Unique
	private String reterraforged$fallbackReason(String defaultReason) {
		return this.reterraforged$compositionFallbackReason == null
			? defaultReason
			: this.reterraforged$compositionFallbackReason;
	}

	@Inject(method = "getTree", at = @At("HEAD"), require = 1)
	private void reterraforged$composeBeforeTreeLookup(int uniqueness, CallbackInfoReturnable<Climate.RTree<T>> callback) {
		if (this.reterraforged$bandingInitialized) {
			this.reterraforged$ensureComposedTrees();
		}
	}

	@Inject(method = "getUniqueness", at = @At("HEAD"), cancellable = true, require = 1)
	private void reterraforged$skipRedundantUniqueness(int x, int y, int z, CallbackInfoReturnable<Integer> callback) {
		if (this.maxIndex <= 0) {
			callback.setReturnValue(0);
		}
	}

	@Unique
	private boolean reterraforged$ensureComposedTrees() {
		List<Pair<Climate.ParameterPoint, T>> currentValues = this.values;
		if (this.reterraforged$composedValuesReference == currentValues) {
			return this.reterraforged$compositionSnapshot != null
				&& this.reterraforged$compositionSnapshot.usable()
				&& !this.reterraforged$regionalSurfaceTrees.isEmpty()
				&& !this.reterraforged$regionalBanding.isEmpty();
		}

		synchronized (this) {
			currentValues = this.values;
			if (this.reterraforged$composedValuesReference == currentValues) {
				return this.reterraforged$compositionSnapshot != null
					&& this.reterraforged$compositionSnapshot.usable()
					&& !this.reterraforged$regionalSurfaceTrees.isEmpty()
					&& !this.reterraforged$regionalBanding.isEmpty();
			}
			if (this.reterraforged$regionalEntries.isEmpty()) {
				this.reterraforged$compositionFallbackReason = this.reterraforged$fallbackReason("no_captured_regions");
				this.reterraforged$composedValuesReference = currentValues;
				return false;
			}

			try {
				ClimateParameterListComposition.Snapshot<T> snapshot = ClimateParameterListComposition.snapshot(
					this.reterraforged$baseEntries,
					currentValues,
					this.reterraforged$regionalEntries,
					MixinParameterList::reterraforged$isDeferredPlaceholder
				);
				if (!snapshot.usable()) {
					this.reterraforged$compositionSnapshot = snapshot;
					this.reterraforged$regionalSurfaceTrees = List.of();
					this.reterraforged$regionalBanding = List.of();
					this.reterraforged$compositionFallbackReason = snapshot.fallbackReason();
					this.reterraforged$composedValuesReference = currentValues;
					RTFCommon.LOGGER.error(
						"TerraBlender composition unavailable ({}); preserving original biome selection",
						snapshot.fallbackReason()
					);
					return false;
				}

				List<Climate.ParameterList<T>> surfaceTrees = new ArrayList<>(this.reterraforged$regionalEntries.size());
				List<UndergroundBiomeBanding.Layout<T>> bandingTrees = new ArrayList<>(this.reterraforged$regionalEntries.size());
				Set<T> shallowCandidates = new LinkedHashSet<>();
				Set<T> deepCandidates = new LinkedHashSet<>();
				int replacedCaveSlots = 0;

				for (int index = 0; index < this.reterraforged$regionalEntries.size(); index++) {
					List<Pair<Climate.ParameterPoint, T>> captured = this.reterraforged$regionalEntries.get(index);
					if (captured == null) {
						surfaceTrees.add(null);
						bandingTrees.add(null);
						continue;
					}

					List<Pair<Climate.ParameterPoint, T>> rawEffectiveEntries = index == 0
							? currentValues
							: ClimateParameterListComposition.append(captured, snapshot.globalAdditions());

					// Filter duplicate parameter-to-biome pairs before building search trees
					List<Pair<Climate.ParameterPoint, T>> effectiveEntries = reterraforged$deduplicateEntries(rawEffectiveEntries);

					ClimateParameterListComposition.CandidateOverlay<T> overlay =
							ClimateParameterListComposition.overlayUndergroundCandidates(
									currentValues,
									index == 0 ? List.of() : captured,
									snapshot.globalAdditions(),
									(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
									MixinParameterList::reterraforged$isDeferredPlaceholder
							);
					if (!overlay.usable()) {
						surfaceTrees.add(null);
						bandingTrees.add(null);
						continue;
					}

					UndergroundBiomeBanding.Layout<T> layout = UndergroundBiomeBanding.apply(
							this.reterraforged$bandingPreset,
							effectiveEntries,
							overlay.entries(),
							this.reterraforged$bandingSeed,
							(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value))
					);
					surfaceTrees.add(new Climate.ParameterList<>(effectiveEntries));
					bandingTrees.add(layout);
					shallowCandidates.addAll(layout.shallowCandidateValues());
					deepCandidates.addAll(layout.deepCandidateValues());
					replacedCaveSlots += overlay.replacedDefaultSlotCount();
				}

				this.reterraforged$compositionSnapshot = snapshot;
				this.reterraforged$regionalSurfaceTrees = Collections.unmodifiableList(surfaceTrees);
				this.reterraforged$regionalBanding = Collections.unmodifiableList(bandingTrees);
				this.reterraforged$shallowCandidateValues = List.copyOf(shallowCandidates);
				this.reterraforged$deepCandidateValues = List.copyOf(deepCandidates);
				this.reterraforged$replacedCaveSlotCount = replacedCaveSlots;
				this.reterraforged$compositionFallbackReason = null;
				this.reterraforged$composedValuesReference = currentValues;
				RTFCommon.LOGGER.info(
					"Composed TerraBlender biome snapshot: {} weighted regional surface trees ({} invalid), {} diagnostic canonical entries ({} exact duplicates), {} late global entries, {} regional cave-slot replacements, {} shallow / {} deep-stage cave variants",
					snapshot.effectiveRegions().size(),
					snapshot.invalidRegions().size(),
					snapshot.canonicalEntries().size(),
					snapshot.duplicateEntryCount(),
					snapshot.globalAdditions().size(),
					replacedCaveSlots,
					shallowCandidates.size(),
					deepCandidates.size()
				);
				return true;
			} catch (RuntimeException exception) {
				this.reterraforged$compositionSnapshot = null;
				this.reterraforged$regionalSurfaceTrees = List.of();
				this.reterraforged$regionalBanding = List.of();
				this.reterraforged$shallowCandidateValues = List.of();
				this.reterraforged$deepCandidateValues = List.of();
				this.reterraforged$replacedCaveSlotCount = 0;
				this.reterraforged$compositionFallbackReason = "composition_exception";
				this.reterraforged$composedValuesReference = currentValues;
				RTFCommon.LOGGER.error(
					"Failed to compose TerraBlender underground biome trees; preserving TerraBlender's original biome trees",
					exception
				);
				return false;
			}
		}
	}

	@Unique
	private static boolean reterraforged$isDeferredPlaceholder(Object value) {
		return value instanceof Holder<?> holder
			&& holder.unwrapKey().filter(Region.DEFERRED_PLACEHOLDER::equals).isPresent();
	}

	@Redirect(
		method = "findValuePositional",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Climate$ParameterList;getUniqueness(III)I"
		),
		require = 0
	)
	public int getUniqueness(Climate.ParameterList<T> parameterList, int x, int y, int z, Climate.TargetPoint targetPoint) {
		return this.reterraforged$getUniqueness(targetPoint, x, y, z);
	}

	@Unique
	private int reterraforged$getUniqueness(Climate.TargetPoint targetPoint, int x, int y, int z) {
		// TerraBlender's initialized uniqueness area owns coherent region shape and weighting.
		// FTF climate values select a biome inside that region; they must not replace the region API.
		return this.getUniqueness(x, y, z);
	}

	@Override
	public boolean reterraforged$isTerraBlenderInitialized() {
		return this.reterraforged$bandingInitialized;
	}

	@Shadow
	public int getTreeCount() {
		throw new UnsupportedOperationException();
	}

	@Shadow
	public int getUniqueness(int x, int y, int z) {
		throw new UnsupportedOperationException();
	}

	@Unique
	private static <T> List<Pair<Climate.ParameterPoint, T>> reterraforged$deduplicateEntries(
			List<Pair<Climate.ParameterPoint, T>> entries
	) {
		if (entries == null || entries.size() <= 1) {
			return entries;
		}
		Set<Pair<Climate.ParameterPoint, T>> uniqueSet = new LinkedHashSet<>(entries);
		return uniqueSet.size() == entries.size() ? entries : List.copyOf(uniqueSet);
	}

}
