package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;

@Mixin(UniformHeight.class)
public interface UniformHeightAccessor {

	@Accessor("minInclusive")
	VerticalAnchor freeterraforged$getMinInclusive();

	@Accessor("maxInclusive")
	VerticalAnchor freeterraforged$getMaxInclusive();
}
