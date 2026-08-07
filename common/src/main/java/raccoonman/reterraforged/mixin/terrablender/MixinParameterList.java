package raccoonman.reterraforged.mixin.terrablender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
import raccoonman.reterraforged.world.worldgen.terrablender.TBTargetPoint;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderParameterList;
import raccoonman.reterraforged.world.worldgen.terrablender.TerraBlenderRegionSelector;
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
	private List<Pair<Climate.ParameterPoint, T>> reterraforged$baseEntries;
	@Unique
	private List<List<Pair<Climate.ParameterPoint, T>>> reterraforged$pendingRegionalEntries;
	@Unique
	private List<List<Pair<Climate.ParameterPoint, T>>> reterraforged$regionalEntries;
	@Unique
	private volatile List<Pair<Climate.ParameterPoint, T>> reterraforged$composedValuesReference;
	@Unique
	private volatile List<Climate.ParameterList<T>> reterraforged$composedOriginalTrees;
	@Unique
	private volatile List<UndergroundBiomeBanding.Layout<T>> reterraforged$bandedTrees;
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
		this.reterraforged$bandingPreset = null;
		this.reterraforged$baseEntries = List.copyOf(this.values);
		this.reterraforged$pendingRegionalEntries.clear();
		this.reterraforged$regionalEntries.clear();
		this.reterraforged$composedValuesReference = null;
		this.reterraforged$composedOriginalTrees = List.of();
		this.reterraforged$bandedTrees = List.of();
		if (regionType == RegionType.OVERWORLD) {
			registryAccess.lookup(RTFRegistries.PRESET)
				.flatMap(registry -> registry.get(Preset.KEY))
				.ifPresent(holder -> this.reterraforged$bandingPreset = holder.value());
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
		if (this.reterraforged$bandingPreset != null) {
			this.reterraforged$pendingRegionalEntries.add(List.copyOf(entries));
		}
		return entries;
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
			this.reterraforged$bandingInitialized = true;
			return;
		}

		try {
			int treeCount = this.getTreeCount();
			int capturedCount = this.reterraforged$pendingRegionalEntries.size();
			if (capturedCount != treeCount) {
				this.reterraforged$regionalEntries.clear();
				RTFCommon.LOGGER.error(
					"TerraBlender region tree count ({}) does not match captured regional entry sets ({}); underground banding disabled",
					treeCount, capturedCount
				);
			} else {
				this.reterraforged$regionalEntries.addAll(this.reterraforged$pendingRegionalEntries);
			}
		} catch (RuntimeException exception) {
			this.reterraforged$regionalEntries.clear();
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
		Selection<T> selection = this.reterraforged$select(targetPoint, x, y, z);
		if (selection != null) {
			callback.setReturnValue(selection.banded());
		}
	}

	@Override
	public T reterraforged$applyUndergroundBanding(Climate.TargetPoint targetPoint, int x, int y, int z, T selected) {
		Selection<T> selection = this.reterraforged$select(targetPoint, x, y, z);
		if (selection == null || !Objects.equals(selected, selection.original())) {
			return selected;
		}
		return selection.banded();
	}

	@Unique
	private Selection<T> reterraforged$select(Climate.TargetPoint targetPoint, int x, int y, int z) {
		if (this.reterraforged$bandingPreset == null || !this.reterraforged$ensureComposedTrees()) {
			return null;
		}

		int treeIndex = this.reterraforged$getUniqueness(targetPoint, x, y, z);
		if (treeIndex < 0 || treeIndex >= this.reterraforged$bandedTrees.size()) {
			return null;
		}
		UndergroundBiomeBanding.Layout<T> banding = this.reterraforged$bandedTrees.get(treeIndex);
		Climate.ParameterList<T> original = this.reterraforged$composedOriginalTrees.get(treeIndex);
		if (banding == null || original == null) {
			return null;
		}

		T originalValue = original.findValue(targetPoint);
		if (reterraforged$isDeferredPlaceholder(originalValue)) {
			Climate.ParameterList<T> defaultOriginal = this.reterraforged$composedOriginalTrees.getFirst();
			if (defaultOriginal == null) {
				return null;
			}
			originalValue = defaultOriginal.findValue(targetPoint);
		}

		T bandedValue = banding.appliesAt(targetPoint)
			? banding.findValue(targetPoint)
			: originalValue;
		if (reterraforged$isDeferredPlaceholder(bandedValue)) {
			UndergroundBiomeBanding.Layout<T> defaultBanding = this.reterraforged$bandedTrees.getFirst();
			Climate.ParameterList<T> defaultOriginal = this.reterraforged$composedOriginalTrees.getFirst();
			if (defaultBanding == null || defaultOriginal == null) {
				return null;
			}
			bandedValue = defaultBanding.appliesAt(targetPoint)
				? defaultBanding.findValue(targetPoint)
				: defaultOriginal.findValue(targetPoint);
		}
		return new Selection<>(originalValue, bandedValue);
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
			return this.reterraforged$bandedTrees != null && !this.reterraforged$bandedTrees.isEmpty();
		}

		synchronized (this) {
			currentValues = this.values;
			if (this.reterraforged$composedValuesReference == currentValues) {
				return this.reterraforged$bandedTrees != null && !this.reterraforged$bandedTrees.isEmpty();
			}
			if (this.reterraforged$regionalEntries.isEmpty()) {
				this.reterraforged$composedValuesReference = currentValues;
				return false;
			}

			try {
				List<Pair<Climate.ParameterPoint, T>> globalAdditions = ClimateParameterListComposition.additions(
					this.reterraforged$baseEntries,
					currentValues
				);
				List<Climate.ParameterList<T>> originalTrees = new ArrayList<>(this.reterraforged$regionalEntries.size());
				List<UndergroundBiomeBanding.Layout<T>> bandedTrees = new ArrayList<>(this.reterraforged$regionalEntries.size());

				for (int index = 0; index < this.reterraforged$regionalEntries.size(); index++) {
					List<Pair<Climate.ParameterPoint, T>> regional = this.reterraforged$regionalEntries.get(index);
					if (regional == null) {
						originalTrees.add(null);
						bandedTrees.add(null);
						continue;
					}
					List<Pair<Climate.ParameterPoint, T>> effectiveEntries = index == 0
						? List.copyOf(currentValues)
						: ClimateParameterListComposition.append(regional, globalAdditions);
					originalTrees.add(new Climate.ParameterList<>(effectiveEntries));
					bandedTrees.add(UndergroundBiomeBanding.apply(this.reterraforged$bandingPreset, effectiveEntries));
				}

				this.reterraforged$composedOriginalTrees = Collections.unmodifiableList(originalTrees);
				this.reterraforged$bandedTrees = Collections.unmodifiableList(bandedTrees);
				this.reterraforged$composedValuesReference = currentValues;
				RTFCommon.LOGGER.info(
					"Composed TerraBlender underground biome trees: {} regions, {} late global parameter points",
					bandedTrees.stream().filter(tree -> tree != null).count(),
					globalAdditions.size()
				);
				return true;
			} catch (RuntimeException exception) {
				this.reterraforged$composedOriginalTrees = List.of();
				this.reterraforged$bandedTrees = List.of();
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

	private record Selection<T>(T original, T banded) {
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
		if ((Object) targetPoint instanceof TBTargetPoint tbTargetPoint) {
			return TerraBlenderRegionSelector.select(
				this.maxIndex,
				tbTargetPoint.getUniqueness(),
				() -> this.getUniqueness(x, y, z)
			);
		} else {
			throw new IllegalStateException();
		}
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
}
