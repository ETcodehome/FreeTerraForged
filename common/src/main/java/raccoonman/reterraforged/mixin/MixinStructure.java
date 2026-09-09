package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationStub;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

@Mixin(Structure.class)
public class MixinStructure {

	@Inject(
		at = @At("HEAD"), 
		method = "isValidBiome",
		cancellable = true
	)
	private static void isValidBiome(GenerationStub generationStub, GenerationContext generationContext, CallbackInfoReturnable<Boolean> callback) {
		if (!(generationContext.chunkGenerator() instanceof TerraForgedChunkGenerator generator)) {
			return;
		}
		for (var rule : generator.activeStructurePlan().rules()) {
			if (!rule.value().test(generationContext.randomState(), generationStub.position())) {
				callback.setReturnValue(false);
				return;
			}
		}
	}
}
