package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.structure.OceanMonumentSeaLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.WorldGenLevel;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces$OceanMonumentPiece")
abstract class MixinOceanMonumentPiece {
	@Redirect(
		method = "generateWaterBox",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/WorldGenLevel;getSeaLevel()I"
		)
	)
	private int ftf$useConfiguredSeaLevel(WorldGenLevel level) {
		return OceanMonumentSeaLevel.effective(level);
	}
}
