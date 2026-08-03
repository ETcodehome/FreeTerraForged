package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;

@Mixin(RandomOffsetPlacement.class)
public interface RandomOffsetPlacementAccessor {

	@Accessor("xzSpread")
	IntProvider reterraforged$getXzSpread();

	@Accessor("ySpread")
	IntProvider reterraforged$getYSpread();
}
