package raccoonman.reterraforged.fabric.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement$ReplacementRequestSet", remap = false)
public interface BiolithReplacementRequestSetAccessor {
	@Accessor(value = "requests", remap = false)
	List<Object> reterraforged$getRequests();
}
