package etcodehome.freeterraforged.mixin.terrablender;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.biome.Climate;
import etcodehome.freeterraforged.world.worldgen.terrablender.TBTargetPoint;

@Mixin(Climate.TargetPoint.class)
@Implements(@Interface(iface = TBTargetPoint.class, prefix = "reterraforged$TBTargetPoint$"))
class MixinTargetPoint {
	private double uniqueness = Double.NaN;

	public double reterraforged$TBTargetPoint$getUniqueness() {
		return this.uniqueness;
	}
	
	public void reterraforged$TBTargetPoint$setUniqueness(double uniqueness) {
		this.uniqueness = uniqueness;
	}
}
