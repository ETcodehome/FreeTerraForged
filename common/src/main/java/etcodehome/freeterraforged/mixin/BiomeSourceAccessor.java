package etcodehome.freeterraforged.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BiomeSource.class)
public interface BiomeSourceAccessor {
	@Invoker("codec")
	Codec<? extends BiomeSource> ftf$codec();
}
