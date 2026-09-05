package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import raccoonman.reterraforged.world.worldgen.structure.OceanMonumentSeaLevel;

@Mixin(OceanMonumentPieces.OceanMonumentPiece.class)
abstract class MixinOceanMonumentPiece {
	@Redirect(
			method = "generateWaterBox",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/WorldGenLevel;getSeaLevel()I"
			)
	)
	private int rtf$useConfiguredSeaLevel(WorldGenLevel level) {
		return OceanMonumentSeaLevel.effective(level);
	}
}