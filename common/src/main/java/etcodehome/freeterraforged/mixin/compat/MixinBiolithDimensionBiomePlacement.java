package etcodehome.freeterraforged.mixin.compat;

import com.terraformersmc.biolith.api.biome.sub.Criterion;
import com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import etcodehome.freeterraforged.world.worldgen.biolith.BiolithPlacementBridge;

@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement", remap = false)
public abstract class MixinBiolithDimensionBiomePlacement {
	@Inject(
		method = "addPlacement",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/Collection;add(Ljava/lang/Object;)Z",
			shift = At.Shift.AFTER
		),
		remap = false,
		require = 1,
		allow = 1
	)
	private void ftf$biolithPlacement(
		ResourceKey<Biome> biome,
		Climate.ParameterPoint point,
		boolean fromData,
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addPlacement((DimensionBiomePlacement) (Object) this, biome, point, fromData);
	}

	@Inject(
		method = "addRemoval",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/Collection;add(Ljava/lang/Object;)Z",
			shift = At.Shift.AFTER
		),
		remap = false,
		require = 1,
		allow = 1
	)
	private void ftf$biolithRemoval(
		ResourceKey<Biome> biome,
		boolean fromData,
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addRemoval((DimensionBiomePlacement) (Object) this, biome, fromData);
	}

	@Inject(
		method = "addReplacement",
		at = @At(
			value = "INVOKE",
			target = "Lcom/terraformersmc/biolith/impl/biome/DimensionBiomePlacement$ReplacementRequestSet;addRequest(Lnet/minecraft/resources/ResourceKey;DZ)V",
			shift = At.Shift.AFTER,
			remap = true
		),
		remap = false,
		require = 1,
		allow = 1
	)
	private void ftf$biolithReplacement(
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

	@Inject(
		method = "addSubBiome",
		at = @At(
			value = "INVOKE",
			target = "Lcom/terraformersmc/biolith/impl/biome/DimensionBiomePlacement$SubBiomeRequestSet;addRequest(Lnet/minecraft/resources/ResourceKey;Lcom/terraformersmc/biolith/api/biome/sub/Criterion;Z)V",
			shift = At.Shift.AFTER,
			remap = true
		),
		remap = false,
		require = 1,
		allow = 1
	)
	private void ftf$biolithSubBiome(
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
	private void ftf$biolithDataCleared(CallbackInfo callback) {
		BiolithPlacementBridge.clearFromData((DimensionBiomePlacement) (Object) this);
	}

}
