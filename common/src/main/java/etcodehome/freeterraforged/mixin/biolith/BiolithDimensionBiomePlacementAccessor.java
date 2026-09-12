package etcodehome.freeterraforged.mixin.biolith;

import java.util.HashMap;

import com.terraformersmc.biolith.impl.noise.OpenSimplexNoise2;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement", remap = false)
public interface BiolithDimensionBiomePlacementAccessor {
	@Accessor(value = "replacementNoise", remap = false)
	OpenSimplexNoise2 freeterraforged$getReplacementNoise();

	@Accessor(value = "seedlets", remap = false)
	int[] freeterraforged$getSeedlets();

	@Accessor(value = "replacementRequests", remap = false)
	HashMap<ResourceKey<Biome>, Object> freeterraforged$getReplacementRequests();

	@Accessor(value = "subBiomeRequests", remap = false)
	HashMap<ResourceKey<Biome>, Object> freeterraforged$getSubBiomeRequests();
}
