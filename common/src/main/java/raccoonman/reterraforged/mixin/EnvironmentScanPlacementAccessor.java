package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;

@Mixin(EnvironmentScanPlacement.class)
public interface EnvironmentScanPlacementAccessor {

	@Accessor("directionOfSearch")
	Direction reterraforged$getDirectionOfSearch();

	@Accessor("targetCondition")
	BlockPredicate reterraforged$getTargetCondition();

	@Accessor("allowedSearchCondition")
	BlockPredicate reterraforged$getAllowedSearchCondition();
}
