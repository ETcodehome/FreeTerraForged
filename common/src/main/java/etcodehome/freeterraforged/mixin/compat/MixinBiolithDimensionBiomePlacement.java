package etcodehome.freeterraforged.mixin.compat;

import com.terraformersmc.biolith.api.biome.SubBiomeMatcher;
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
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addPlacement((DimensionBiomePlacement) (Object) this, biome, point, false);
	}

	@Inject(
		method = "addReplacement",
		at = @At(
			value = "INVOKE",
			target = "Lcom/terraformersmc/biolith/impl/biome/DimensionBiomePlacement$ReplacementRequestSet;addRequest(Lnet/minecraft/resources/ResourceKey;D)V",
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
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addReplacement(
			(DimensionBiomePlacement) (Object) this, target, biome, proportion, false
		);
	}

	@Inject(
		method = "addSubBiome",
		at = @At(
			value = "INVOKE",
			target = "Lcom/terraformersmc/biolith/impl/biome/DimensionBiomePlacement$SubBiomeRequestSet;addRequest(Lnet/minecraft/resources/ResourceKey;Lcom/terraformersmc/biolith/api/biome/SubBiomeMatcher;)V",
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
		SubBiomeMatcher matcher,
		CallbackInfo callback
	) {
		BiolithPlacementBridge.addSubBiome(
			(DimensionBiomePlacement) (Object) this, target, biome, matcher, false
		);
	}
}
