package etcodehome.freeterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.world.level.levelgen.SurfaceRules.Context;
import etcodehome.freeterraforged.world.worldgen.surface.ISurfaceContext;

@Mixin(Context.class)
public class MixinContext implements ISurfaceContext {
	@Shadow public int stoneDepthAbove;
	@Shadow public int stoneDepthBelow;

	@Override
	public int freeterraforged$getStoneDepthAbove() {
		return this.stoneDepthAbove;
	}

	@Override
	public int freeterraforged$getStoneDepthBelow() {
		return this.stoneDepthBelow;
	}
}
