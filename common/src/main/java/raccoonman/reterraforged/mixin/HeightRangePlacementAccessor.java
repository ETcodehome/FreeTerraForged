package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;

@Mixin(HeightRangePlacement.class)
public interface HeightRangePlacementAccessor {

	@Accessor("height")
	HeightProvider reterraforged$getHeightProvider();
}
