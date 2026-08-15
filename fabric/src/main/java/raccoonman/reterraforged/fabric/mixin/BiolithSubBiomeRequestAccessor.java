package raccoonman.reterraforged.fabric.mixin;

import com.terraformersmc.biolith.api.biome.sub.Criterion;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement$SubBiomeRequest", remap = false)
public interface BiolithSubBiomeRequestAccessor {
	@Accessor(value = "biome", remap = false)
	ResourceKey<Biome> reterraforged$getBiome();

	@Accessor(value = "criterion", remap = false)
	Criterion reterraforged$getCriterion();
}
