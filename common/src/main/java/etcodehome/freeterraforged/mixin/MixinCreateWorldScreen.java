package etcodehome.freeterraforged.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenDimensionInputs;

@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreen {
	// The encode call lives in applyNewPackConfig's synthetic reload lambda. Anchor its exact
	// public signature rather than a compiler-generated lambda ordinal. This call is unique.
	@ModifyArg(method = "*", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/world/level/levelgen/WorldGenSettings;encode(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/world/level/levelgen/WorldDimensions;)Lcom/mojang/serialization/DataResult;"),
		index = 2, require = 1, allow = 1)
	private WorldDimensions freeterraforged$encodeSelectedInputsForReload(WorldDimensions dimensions) {
		return WorldgenDimensionInputs.forReload(dimensions);
	}
}
