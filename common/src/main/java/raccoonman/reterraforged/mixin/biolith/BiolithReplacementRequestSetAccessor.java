package raccoonman.reterraforged.mixin.biolith;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement$ReplacementRequestSet", remap = false)
public interface BiolithReplacementRequestSetAccessor {
	@Accessor(value = "finalized", remap = false)
	boolean reterraforged$isFinalized();

	@Accessor(value = "requests", remap = false)
	List<Object> reterraforged$getRequests();
}
