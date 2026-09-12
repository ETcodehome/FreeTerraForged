package etcodehome.freeterraforged.mixin.biolith;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement$ReplacementRequest", remap = false)
public interface BiolithReplacementRequestAccessor {
	@Accessor(value = "biome", remap = false)
	ResourceKey<Biome> freeterraforged$getBiome();

	@Accessor(value = "rate", remap = false)
	double freeterraforged$getRate();

	@Accessor(value = "biomeEntry", remap = false)
	Holder<Biome> freeterraforged$getBiomeEntry();

	@Accessor(value = "start", remap = false)
	double freeterraforged$getStart();

	@Accessor(value = "end", remap = false)
	double freeterraforged$getEnd();

	@Accessor(value = "fromData", remap = false)
	boolean freeterraforged$isFromData();
}
