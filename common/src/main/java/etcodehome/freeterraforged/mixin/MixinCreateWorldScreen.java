package etcodehome.freeterraforged.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenDimensionInputs;

@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreen {
	// The unique encode call is in a reload lambda whose compiler-generated name is unstable.
	@ModifyArg(method = "*", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/world/level/levelgen/WorldGenSettings;encode(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/world/level/levelgen/WorldDimensions;)Lcom/mojang/serialization/DataResult;"),
		index = 2, require = 1, allow = 1)
	private WorldDimensions freeterraforged$encodeSelectedInputsForReload(WorldDimensions dimensions) {
		return WorldgenDimensionInputs.forReload(dimensions);
	}
}
