package raccoonman.reterraforged.mixin.compat;

import com.terraformersmc.biolith.api.biome.sub.Criterion;
import com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement;
import com.terraformersmc.biolith.impl.config.BiolithState;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.world.worldgen.biolith.BiolithPlacementBridge;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenLifecycle;

@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement", remap = false)
public abstract class MixinBiolithDimensionBiomePlacement {
	@Inject(method = "addPlacement", at = @At("TAIL"), remap = false, require = 1)
	private void rtf$biolithPlacement(
		ResourceKey<Biome> biome,
		Climate.ParameterPoint point,
		boolean fromData,
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addPlacement((DimensionBiomePlacement) (Object) this, biome, point, fromData);
	}

	@Inject(method = "addRemoval", at = @At("TAIL"), remap = false, require = 1)
	private void rtf$biolithRemoval(
		ResourceKey<Biome> biome,
		boolean fromData,
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addRemoval((DimensionBiomePlacement) (Object) this, biome, fromData);
	}

	@Inject(method = "addReplacement", at = @At("TAIL"), remap = false, require = 1)
	private void rtf$biolithReplacement(
		ResourceKey<Biome> target,
		ResourceKey<Biome> biome,
		double proportion,
		boolean fromData,
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addReplacement(
			(DimensionBiomePlacement) (Object) this, target, biome, proportion, fromData
		);
	}

	@Inject(method = "addSubBiome", at = @At("TAIL"), remap = false, require = 1)
	private void rtf$biolithSubBiome(
		ResourceKey<Biome> target,
		ResourceKey<Biome> biome,
		Criterion criterion,
		boolean fromData,
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addSubBiome(
			(DimensionBiomePlacement) (Object) this, target, biome, criterion, fromData
		);
	}

	@Inject(method = "clearFromData", at = @At("TAIL"), remap = false, require = 1)
	private void rtf$biolithDataCleared(CallbackInfo callback) {
		BiolithPlacementBridge.clearFromData((DimensionBiomePlacement) (Object) this);
	}

	@Inject(method = "serverReplaced", at = @At("TAIL"), remap = false, require = 1)
	private void rtf$biolithFinalized(
		BiolithState state,
		ServerLevel world,
		CallbackInfo callback
	) {
		WorldgenLifecycle.contributionsFinalized(world);
	}
}
