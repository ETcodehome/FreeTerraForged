package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;

@Mixin(UniformHeight.class)
public interface UniformHeightAccessor {

	@Accessor("minInclusive")
	VerticalAnchor reterraforged$getMinInclusive();

	@Accessor("maxInclusive")
	VerticalAnchor reterraforged$getMaxInclusive();
}
