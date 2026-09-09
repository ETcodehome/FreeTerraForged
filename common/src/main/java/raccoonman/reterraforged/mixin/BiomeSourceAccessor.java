package raccoonman.reterraforged.mixin;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BiomeSource.class)
public interface BiomeSourceAccessor {
	@Invoker("codec")
	MapCodec<? extends BiomeSource> rtf$codec();
}
