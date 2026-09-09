package etcodehome.freeterraforged.mixin.terrablender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.world.worldgen.terrablender.TerraBlenderParameterList;
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
import etcodehome.freeterraforged.world.worldgen.biome.ClimateParameterListComposition;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeSurfaceQuery;
import etcodehome.freeterraforged.world.worldgen.biome.PreviewBiomeQueryContext;
import etcodehome.freeterraforged.world.worldgen.biome.UndergroundBiomeTags;
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
	private Preset freeterraforged$bandingPreset;
	@Unique
	private Preset freeterraforged$previewPreset;
	@Unique
	private long freeterraforged$previewSeed;
	@Unique
	private long freeterraforged$bandingSeed;
	@Unique
	private List<Pair<Climate.ParameterPoint, T>> freeterraforged$baseEntries;
	@Unique
	private List<List<Pair<Climate.ParameterPoint, T>>> freeterraforged$pendingRegionalEntries;
	@Unique
	private List<List<Pair<Climate.ParameterPoint, T>>> freeterraforged$regionalEntries;
	@Unique
	private volatile List<Pair<Climate.ParameterPoint, T>> freeterraforged$composedValuesReference;
	@Unique
	private volatile ClimateParameterListComposition.Snapshot<T> freeterraforged$compositionSnapshot;
	@Unique
	private volatile List<Climate.ParameterList<T>> freeterraforged$regionalSurfaceTrees;
	@Unique
	private volatile List<UndergroundBiomeBanding.Layout<T>> freeterraforged$regionalBanding;
	@Unique
	private volatile List<T> freeterraforged$shallowCandidateValues;
	@Unique
	private volatile List<T> freeterraforged$deepCandidateValues;
	@Unique
	private volatile int freeterraforged$replacedCaveSlotCount;
	@Unique
	private volatile String freeterraforged$compositionFallbackReason;
	@Unique
	private boolean freeterraforged$bandingInitialized;

	@Inject(
		at = @At("HEAD"),
		method = "initializeForTerraBlender",
		require = 1
	)
	public void initializeForTerraBlender(RegistryAccess registryAccess, RegionType regionType, long seed, CallbackInfo callback) {
		this.maxIndex = Regions.getCount(regionType) - 1;
		if (this.freeterraforged$bandingInitialized) {
			return;
		}
		if (this.freeterraforged$pendingRegionalEntries == null) {
			this.freeterraforged$pendingRegionalEntries = new ArrayList<>();
		}
		if (this.freeterraforged$regionalEntries == null) {
			this.freeterraforged$regionalEntries = new ArrayList<>();
		}
		this.freeterraforged$bandingPreset = this.freeterraforged$previewPreset;
		this.freeterraforged$bandingSeed = this.freeterraforged$previewPreset == null ? seed : this.freeterraforged$previewSeed;
		this.freeterraforged$baseEntries = List.copyOf(this.values);
		this.freeterraforged$pendingRegionalEntries.clear();
		this.freeterraforged$regionalEntries.clear();
		this.freeterraforged$composedValuesReference = null;
		this.freeterraforged$compositionSnapshot = null;
		this.freeterraforged$regionalSurfaceTrees = List.of();
		this.freeterraforged$regionalBanding = List.of();
		this.freeterraforged$shallowCandidateValues = List.of();
		this.freeterraforged$deepCandidateValues = List.of();
		this.freeterraforged$replacedCaveSlotCount = 0;
		this.freeterraforged$compositionFallbackReason = null;
		if (this.freeterraforged$bandingPreset == null && regionType == RegionType.OVERWORLD) {
			registryAccess.lookup(FTFRegistries.PRESET)
				.flatMap(registry -> registry.get(Preset.KEY))
				.ifPresent(holder -> this.freeterraforged$bandingPreset = holder.value());
		}
//
//    	registryAccess.lookup(FTFRegistries.PRESET).flatMap((registry) -> {
//    		return registry.get(Preset.KEY);
//    	}).ifPresent((holder) -> {
//    		Preset preset = holder.value();
//        	TBCompat.setSurfaceRules(preset, (defaultRules) -> {
//        		return FTFSurfaceRuleData.overworld(preset, registryAccess.lookupOrThrow(Registries.DENSITY_FUNCTION), registryAccess.lookupOrThrow(FTFRegistries.NOISE), defaultRules);
//            });
//    	});
	}

	@Override
	public void freeterraforged$preparePreview(Preset preset, long seed) {
		if (!this.freeterraforged$bandingInitialized) {
			this.freeterraforged$previewPreset = preset;
			this.freeterraforged$previewSeed = seed;
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
	private List<Pair<Climate.ParameterPoint, T>> freeterraforged$captureRegionalEntries(
			List<Pair<Climate.ParameterPoint, T>> entries
	) {
		List<Pair<Climate.ParameterPoint, T>> deduplicated = freeterraforged$deduplicateEntries(entries);
		if (this.freeterraforged$bandingPreset != null) {
			this.freeterraforged$pendingRegionalEntries.add(deduplicated);
		}
		return deduplicated;
	}

	@Inject(
		method = "initializeForTerraBlender",
		at = @At("RETURN"),
		require = 1
	)
	private void freeterraforged$indexRegionalEntries(
		RegistryAccess registryAccess,
		RegionType regionType,
		long seed,
		CallbackInfo callback
	) {
		if (this.freeterraforged$bandingInitialized) {
			return;
		}
		if (this.freeterraforged$bandingPreset == null) {
			this.freeterraforged$compositionFallbackReason = "missing_overworld_preset";
			this.freeterraforged$bandingInitialized = true;
			return;
		}

		try {
			int treeCount = this.getTreeCount();
			int capturedCount = this.freeterraforged$pendingRegionalEntries.size();
			if (capturedCount != treeCount) {
				this.freeterraforged$regionalEntries.clear();
				this.freeterraforged$compositionFallbackReason = "capture_count_mismatch";
				FTFCommon.LOGGER.error(
					"TerraBlender region tree count ({}) does not match captured regional entry sets ({}); underground banding disabled",
					treeCount, capturedCount
				);
			} else {
				this.freeterraforged$regionalEntries.addAll(this.freeterraforged$pendingRegionalEntries);
			}
		} catch (RuntimeException exception) {
			this.freeterraforged$regionalEntries.clear();
			this.freeterraforged$compositionFallbackReason = "capture_exception";
			FTFCommon.LOGGER.error(
				"Failed to capture TerraBlender biome entries; underground banding disabled",
				exception
			);
		} finally {
			this.freeterraforged$pendingRegionalEntries.clear();
			this.freeterraforged$bandingInitialized = true;
		}
	}

	@Inject(
		method = "findValuePositional",
		at = @At("HEAD"),
		cancellable = true,
		require = 1
	)
	private void freeterraforged$selectComposedBiome(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z,
		CallbackInfoReturnable<T> callback
	) {
		T banded = this.freeterraforged$selectBanded(targetPoint, x, y, z, null, false);
		if (banded != null) {
			callback.setReturnValue(banded);
		}
	}

	@Override
	public T freeterraforged$applyUndergroundBanding(Climate.TargetPoint targetPoint, int x, int y, int z, T selected) {
		T banded = this.freeterraforged$selectBanded(targetPoint, x, y, z, selected, true);
		return banded == null ? selected : banded;
	}

	@Override
	public T freeterraforged$applyUndergroundSurfaceProtection(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z,
		T selected,
		float surfaceCoverageFactor
	) {
		if (surfaceCoverageFactor >= 1.0F
			|| this.freeterraforged$bandingPreset == null
			|| !this.freeterraforged$ensureComposedTrees()) {
			return selected;
		}

		int treeIndex;
		try {
			treeIndex = this.freeterraforged$getUniqueness(targetPoint, x, y, z);
		} catch (RuntimeException exception) {
			return selected;
		}
		List<UndergroundBiomeBanding.Layout<T>> layouts = this.freeterraforged$regionalBanding;
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
	private T freeterraforged$selectBanded(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z,
		T requiredOriginal,
		boolean requireOriginalMatch
	) {
		if (this.freeterraforged$bandingPreset == null || !this.freeterraforged$ensureComposedTrees()) {
			return null;
		}
		int treeIndex;
		try {
			treeIndex = this.freeterraforged$getUniqueness(targetPoint, x, y, z);
		} catch (RuntimeException exception) {
			return null;
		}
		ClimateParameterListComposition.Snapshot<T> snapshot = this.freeterraforged$compositionSnapshot;
		List<Climate.ParameterList<T>> surfaceTrees = this.freeterraforged$regionalSurfaceTrees;
		List<UndergroundBiomeBanding.Layout<T>> bandingTrees = this.freeterraforged$regionalBanding;
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
		if (freeterraforged$isDeferredPlaceholder(originalValue)) {
			Climate.ParameterList<T> defaultTree = surfaceTrees.getFirst();
			if (defaultTree == null) {
				return null;
			}
			originalValue = defaultTree.findValue(targetPoint);
		}
		if (freeterraforged$isDeferredPlaceholder(originalValue)
			|| (requireOriginalMatch && !Objects.equals(requiredOriginal, originalValue))) {
			return null;
		}
		float surfaceCoverageFactor = UndergroundBiomeSurfaceQuery.coverageFactor(targetPoint, x, y, z);
		T backgroundValue = banding.backgroundValue(targetPoint);
		T bandedValue;
		if (banding.appliesAt(targetPoint)) {
			bandedValue = banding.findValue(targetPoint, x, y, z, surfaceCoverageFactor);
		} else if ((surfaceCoverageFactor <= 0.0F
			|| this.freeterraforged$bandingPreset.climate().biomeShape.undergroundBiomeCoverage() <= 0.0F)
			&& banding.isCaveCandidate(originalValue)) {
			bandedValue = backgroundValue;
		} else {
			bandedValue = originalValue;
		}
		if (freeterraforged$isDeferredPlaceholder(bandedValue)) {
			return null;
		}
		PreviewBiomeQueryContext.record(x, y, z, originalValue, bandedValue);
		return bandedValue;
	}

	@Override
	public TerraBlenderParameterList.SelectionDiagnostics<T> freeterraforged$inspectSelection(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z
	) {
		if (this.freeterraforged$bandingPreset == null || !this.freeterraforged$ensureComposedTrees()) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(
				-1, null, null, this.freeterraforged$fallbackReason("composition_unavailable")
			);
		}

		int treeIndex;
		try {
			treeIndex = this.freeterraforged$getUniqueness(targetPoint, x, y, z);
		} catch (RuntimeException exception) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(-1, null, null, "region_selection_exception");
		}
		ClimateParameterListComposition.Snapshot<T> snapshot = this.freeterraforged$compositionSnapshot;
		if (snapshot == null || !snapshot.usableForRegion(treeIndex)) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "invalid_selected_region");
		}
		List<Climate.ParameterList<T>> surfaceTrees = this.freeterraforged$regionalSurfaceTrees;
		List<UndergroundBiomeBanding.Layout<T>> bandingTrees = this.freeterraforged$regionalBanding;
		if (treeIndex >= surfaceTrees.size() || treeIndex >= bandingTrees.size()) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "missing_composed_index");
		}
		UndergroundBiomeBanding.Layout<T> banding = bandingTrees.get(treeIndex);
		Climate.ParameterList<T> original = surfaceTrees.get(treeIndex);
		if (banding == null || original == null) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "missing_regional_index");
		}

		T originalValue = original.findValue(targetPoint);
		if (freeterraforged$isDeferredPlaceholder(originalValue)) {
			Climate.ParameterList<T> defaultTree = surfaceTrees.getFirst();
			if (defaultTree == null) {
				return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "missing_default_index");
			}
			originalValue = defaultTree.findValue(targetPoint);
			if (freeterraforged$isDeferredPlaceholder(originalValue)) {
				return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, null, null, "deferred_surface_winner");
			}
		}

		T bandedValue = banding.appliesAt(targetPoint)
			? banding.findValue(targetPoint, x, y, z)
			: originalValue;
		if (freeterraforged$isDeferredPlaceholder(bandedValue)) {
			return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, originalValue, null, "deferred_banded_winner");
		}
		return new TerraBlenderParameterList.SelectionDiagnostics<>(treeIndex, originalValue, bandedValue, null);
	}

	@Override
	public TerraBlenderParameterList.CompositionDiagnostics<T> freeterraforged$getCompositionDiagnostics() {
		if (this.freeterraforged$bandingInitialized) {
			this.freeterraforged$ensureComposedTrees();
		}
		ClimateParameterListComposition.Snapshot<T> snapshot = this.freeterraforged$compositionSnapshot;
		if (snapshot == null) {
			return new TerraBlenderParameterList.CompositionDiagnostics<>(
				this.freeterraforged$regionalEntries == null ? 0 : this.freeterraforged$regionalEntries.size(),
				this.freeterraforged$regionalEntries == null
					? List.of()
					: this.freeterraforged$regionalEntries.stream().map(entries -> entries == null ? -1 : entries.size()).toList(),
				0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, List.of(), List.of(), 0, 0,
				this.freeterraforged$fallbackReason("composition_not_built")
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
			this.freeterraforged$replacedCaveSlotCount,
			this.freeterraforged$shallowCandidateValues.size(),
			this.freeterraforged$deepCandidateValues.size(),
			this.freeterraforged$shallowCandidateValues,
			this.freeterraforged$deepCandidateValues,
			0,
			0,
			this.freeterraforged$compositionFallbackReason
		);
	}

	@Unique
	private String freeterraforged$fallbackReason(String defaultReason) {
		return this.freeterraforged$compositionFallbackReason == null
			? defaultReason
			: this.freeterraforged$compositionFallbackReason;
	}

	@Inject(method = "getTree", at = @At("HEAD"), require = 1)
	private void freeterraforged$composeBeforeTreeLookup(int uniqueness, CallbackInfoReturnable<Climate.RTree<T>> callback) {
		if (this.freeterraforged$bandingInitialized) {
			this.freeterraforged$ensureComposedTrees();
		}
	}

	@Inject(method = "getUniqueness", at = @At("HEAD"), cancellable = true, require = 1)
	private void freeterraforged$skipRedundantUniqueness(int x, int y, int z, CallbackInfoReturnable<Integer> callback) {
		if (this.maxIndex <= 0) {
			callback.setReturnValue(0);
		}
	}

	@Unique
	private boolean freeterraforged$ensureComposedTrees() {
		List<Pair<Climate.ParameterPoint, T>> currentValues = this.values;
		if (this.freeterraforged$composedValuesReference == currentValues) {
			return this.freeterraforged$compositionSnapshot != null
				&& this.freeterraforged$compositionSnapshot.usable()
				&& !this.freeterraforged$regionalSurfaceTrees.isEmpty()
				&& !this.freeterraforged$regionalBanding.isEmpty();
		}

		synchronized (this) {
			currentValues = this.values;
			if (this.freeterraforged$composedValuesReference == currentValues) {
				return this.freeterraforged$compositionSnapshot != null
					&& this.freeterraforged$compositionSnapshot.usable()
					&& !this.freeterraforged$regionalSurfaceTrees.isEmpty()
					&& !this.freeterraforged$regionalBanding.isEmpty();
			}
			if (this.freeterraforged$regionalEntries.isEmpty()) {
				this.freeterraforged$compositionFallbackReason = this.freeterraforged$fallbackReason("no_captured_regions");
				this.freeterraforged$composedValuesReference = currentValues;
				return false;
			}

			try {
				ClimateParameterListComposition.Snapshot<T> snapshot = ClimateParameterListComposition.snapshot(
					this.freeterraforged$baseEntries,
					currentValues,
					this.freeterraforged$regionalEntries,
					MixinParameterList::freeterraforged$isDeferredPlaceholder
				);
				if (!snapshot.usable()) {
					this.freeterraforged$compositionSnapshot = snapshot;
					this.freeterraforged$regionalSurfaceTrees = List.of();
					this.freeterraforged$regionalBanding = List.of();
					this.freeterraforged$compositionFallbackReason = snapshot.fallbackReason();
					this.freeterraforged$composedValuesReference = currentValues;
					FTFCommon.LOGGER.error(
						"TerraBlender composition unavailable ({}); preserving original biome selection",
						snapshot.fallbackReason()
					);
					return false;
				}

				List<Climate.ParameterList<T>> surfaceTrees = new ArrayList<>(this.freeterraforged$regionalEntries.size());
				List<UndergroundBiomeBanding.Layout<T>> bandingTrees = new ArrayList<>(this.freeterraforged$regionalEntries.size());
				Set<T> shallowCandidates = new LinkedHashSet<>();
				Set<T> deepCandidates = new LinkedHashSet<>();
				int replacedCaveSlots = 0;

				for (int index = 0; index < this.freeterraforged$regionalEntries.size(); index++) {
					List<Pair<Climate.ParameterPoint, T>> captured = this.freeterraforged$regionalEntries.get(index);
					if (captured == null) {
						surfaceTrees.add(null);
						bandingTrees.add(null);
						continue;
					}

					List<Pair<Climate.ParameterPoint, T>> rawEffectiveEntries = index == 0
							? currentValues
							: ClimateParameterListComposition.append(captured, snapshot.globalAdditions());

					// Filter duplicate parameter-to-biome pairs before building search trees
					List<Pair<Climate.ParameterPoint, T>> effectiveEntries = freeterraforged$deduplicateEntries(rawEffectiveEntries);

					ClimateParameterListComposition.CandidateOverlay<T> overlay =
							ClimateParameterListComposition.overlayUndergroundCandidates(
									currentValues,
									index == 0 ? List.of() : captured,
									snapshot.globalAdditions(),
									(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value)),
									MixinParameterList::freeterraforged$isDeferredPlaceholder
							);
					if (!overlay.usable()) {
						surfaceTrees.add(null);
						bandingTrees.add(null);
						continue;
					}

					UndergroundBiomeBanding.Layout<T> layout = UndergroundBiomeBanding.apply(
							this.freeterraforged$bandingPreset,
							effectiveEntries,
							overlay.entries(),
							this.freeterraforged$bandingSeed,
							(point, value) -> UndergroundBiomeBanding.classify(point, UndergroundBiomeTags.isCave(value))
					);
					surfaceTrees.add(new Climate.ParameterList<>(effectiveEntries));
					bandingTrees.add(layout);
					shallowCandidates.addAll(layout.shallowCandidateValues());
					deepCandidates.addAll(layout.deepCandidateValues());
					replacedCaveSlots += overlay.replacedDefaultSlotCount();
				}

				this.freeterraforged$compositionSnapshot = snapshot;
				this.freeterraforged$regionalSurfaceTrees = Collections.unmodifiableList(surfaceTrees);
				this.freeterraforged$regionalBanding = Collections.unmodifiableList(bandingTrees);
				this.freeterraforged$shallowCandidateValues = List.copyOf(shallowCandidates);
				this.freeterraforged$deepCandidateValues = List.copyOf(deepCandidates);
				this.freeterraforged$replacedCaveSlotCount = replacedCaveSlots;
				this.freeterraforged$compositionFallbackReason = null;
				this.freeterraforged$composedValuesReference = currentValues;
				FTFCommon.LOGGER.info(
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
				this.freeterraforged$compositionSnapshot = null;
				this.freeterraforged$regionalSurfaceTrees = List.of();
				this.freeterraforged$regionalBanding = List.of();
				this.freeterraforged$shallowCandidateValues = List.of();
				this.freeterraforged$deepCandidateValues = List.of();
				this.freeterraforged$replacedCaveSlotCount = 0;
				this.freeterraforged$compositionFallbackReason = "composition_exception";
				this.freeterraforged$composedValuesReference = currentValues;
				FTFCommon.LOGGER.error(
					"Failed to compose TerraBlender underground biome trees; preserving TerraBlender's original biome trees",
					exception
				);
				return false;
			}
		}
	}

	@Unique
	private static boolean freeterraforged$isDeferredPlaceholder(Object value) {
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
		return this.freeterraforged$getUniqueness(targetPoint, x, y, z);
	}

	@Unique
	private int freeterraforged$getUniqueness(Climate.TargetPoint targetPoint, int x, int y, int z) {
		// TerraBlender's initialized uniqueness area owns coherent region shape and weighting.
		// FTF climate values select a biome inside that region; they must not replace the region API.
		return this.getUniqueness(x, y, z);
	}

	@Override
	public boolean freeterraforged$isTerraBlenderInitialized() {
		return this.freeterraforged$bandingInitialized;
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
	private static <T> List<Pair<Climate.ParameterPoint, T>> freeterraforged$deduplicateEntries(
			List<Pair<Climate.ParameterPoint, T>> entries
	) {
		if (entries == null || entries.size() <= 1) {
			return entries;
		}
		Set<Pair<Climate.ParameterPoint, T>> uniqueSet = new LinkedHashSet<>(entries);
		return uniqueSet.size() == entries.size() ? entries : List.copyOf(uniqueSet);
	}

}
