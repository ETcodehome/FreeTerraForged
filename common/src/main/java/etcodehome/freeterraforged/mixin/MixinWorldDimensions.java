package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.core.Registry;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenDimensionInputs;

@Mixin(WorldDimensions.class)
class MixinWorldDimensions {
	@ModifyVariable(method = "bake", at = @At("HEAD"), argsOnly = true)
	private Registry<LevelStem> freeterraforged$acquireDimensionInputs(Registry<LevelStem> declarations) {
		return WorldgenDimensionInputs.forBake((WorldDimensions)(Object)this, declarations);
	}
}
