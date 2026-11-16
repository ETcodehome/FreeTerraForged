package etcodehome.freeterraforged.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import etcodehome.freeterraforged.world.worldgen.structure.OceanMonumentSeaLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;

@Mixin(OceanMonumentPieces.OceanMonumentPiece.class)
abstract class MixinOceanMonumentPiece {
	@WrapOperation(
		method = "generateWaterBox",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/WorldGenLevel;getSeaLevel()I"
		)
	)
	private int ftf$useConfiguredSeaLevel(WorldGenLevel level, Operation<Integer> original) {
		int configured = OceanMonumentSeaLevel.configured(level);
		return configured == Integer.MIN_VALUE ? original.call(level) : configured;
	}
}
