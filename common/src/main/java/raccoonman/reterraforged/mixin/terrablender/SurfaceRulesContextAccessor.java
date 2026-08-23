package raccoonman.reterraforged.mixin.terrablender;

import java.util.function.Supplier;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SurfaceRules.Context.class)
public interface SurfaceRulesContextAccessor {
	@Accessor("biome")
	Supplier<Holder<Biome>> reterraforged$getBiome();
}
