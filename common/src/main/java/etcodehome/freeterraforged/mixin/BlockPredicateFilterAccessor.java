package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;

@Mixin(BlockPredicateFilter.class)
public interface BlockPredicateFilterAccessor {

	@Accessor("predicate")
	BlockPredicate freeterraforged$getPredicate();
}
