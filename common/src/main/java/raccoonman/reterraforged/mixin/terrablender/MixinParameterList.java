package raccoonman.reterraforged.mixin.terrablender;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

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
import raccoonman.reterraforged.world.worldgen.biome.UndergroundBiomeBanding;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.terrablender.TBTargetPoint;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;

@Mixin(
	value = Climate.ParameterList.class,
	priority = 1001
)
class MixinParameterList<T> {
	private int maxIndex;
	@Unique
	private Preset reterraforged$bandingPreset;
	@Unique
	private final List<UndergroundBiomeBanding.Layout<T>> reterraforged$pendingBandings = new ArrayList<>();
	@Unique
	private final List<UndergroundBiomeBanding.Layout<T>> reterraforged$bandedTrees = new ArrayList<>();
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
		this.reterraforged$bandingPreset = null;
		this.reterraforged$pendingBandings.clear();
		this.reterraforged$bandedTrees.clear();
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
	private List<Pair<Climate.ParameterPoint, T>> reterraforged$prepareUndergroundBanding(
		List<Pair<Climate.ParameterPoint, T>> entries
	) {
		if (this.reterraforged$bandingPreset != null) {
			this.reterraforged$pendingBandings.add(UndergroundBiomeBanding.apply(
				this.reterraforged$bandingPreset,
				entries
			));
		}
		return entries;
	}

	@Inject(
		method = "initializeForTerraBlender",
		at = @At("RETURN"),
		require = 1
	)
	private void reterraforged$indexUndergroundBandings(
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
			Field uniqueTreesField = this.getClass().getDeclaredField("uniqueTrees");
			uniqueTreesField.setAccessible(true);
			Object uniqueTrees = uniqueTreesField.get(this);
			int pendingIndex = 0;

			for (int index = 0; index < Array.getLength(uniqueTrees); index++) {
				this.reterraforged$bandedTrees.add(
					Array.get(uniqueTrees, index) == null
						? null
						: this.reterraforged$pendingBandings.get(pendingIndex++)
				);
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			this.reterraforged$bandedTrees.clear();
			RTFCommon.LOGGER.error(
				"Failed to index TerraBlender underground biome banding; preserving TerraBlender's original biome trees",
				exception
			);
		} finally {
			this.reterraforged$pendingBandings.clear();
			this.reterraforged$bandingInitialized = true;
		}
	}

	@Inject(
		method = "findValuePositional",
		at = @At("HEAD"),
		cancellable = true,
		require = 1
	)
	private void reterraforged$selectUndergroundBandedBiome(
		Climate.TargetPoint targetPoint,
		int x,
		int y,
		int z,
		CallbackInfoReturnable<T> callback
	) {
		if (this.reterraforged$bandingPreset == null) {
			return;
		}

		int treeIndex = this.reterraforged$getUniqueness(targetPoint, x, y, z);
		if (treeIndex < 0 || treeIndex >= this.reterraforged$bandedTrees.size()) {
			return;
		}
		UndergroundBiomeBanding.Layout<T> banding = this.reterraforged$bandedTrees.get(treeIndex);
		if (banding == null) {
			return;
		}
		if (!banding.appliesAt(targetPoint)) {
			return;
		}

		T value = banding.findValue(targetPoint);
		if (value instanceof Holder<?> holder
			&& holder.unwrapKey().filter(Region.DEFERRED_PLACEHOLDER::equals).isPresent()) {
			UndergroundBiomeBanding.Layout<T> defaultBanding = this.reterraforged$bandedTrees.getFirst();
			if (defaultBanding == null || !defaultBanding.appliesAt(targetPoint)) {
				return;
			}
			value = defaultBanding.findValue(targetPoint);
		}
		callback.setReturnValue(value);
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
			double uniqueness = tbTargetPoint.getUniqueness();
			if (Double.isNaN(uniqueness)) {
				return this.getUniqueness(x, y, z);
			}
			return NoiseUtil.round(this.maxIndex * (float) uniqueness);
		} else {
			throw new IllegalStateException();
		}
	}

	@Shadow
	public int getUniqueness(int x, int y, int z) {
		throw new UnsupportedOperationException();
	}
}
