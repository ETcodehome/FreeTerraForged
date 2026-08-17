package raccoonman.reterraforged.mixin.biolith;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement$SubBiomeRequestSet", remap = false)
public interface BiolithSubBiomeRequestSetAccessor {
	@Accessor(value = "requests", remap = false)
	List<Object> reterraforged$getRequests();
}
