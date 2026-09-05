package raccoonman.reterraforged.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.material.Fluids;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;

@Mixin(WorldCarver.class)
public class MixinWorldCarver {
	@WrapOperation(
		method = "carveBlock",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/carver/WorldCarver;canReplaceBlock(Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/world/level/block/state/BlockState;)Z"
		)
	)
	private boolean reterraforged$preventWaterCarving(
		WorldCarver<?> carver,
		CarverConfiguration config,
		BlockState state,
		Operation<Boolean> original,
		CarvingContext context
	) {
		if ((Object) context.randomState() instanceof RTFRandomState randomState
			&& randomState.isTerraForged()
			&& (state.getFluidState().isSourceOfType(Fluids.WATER)
				|| state.getFluidState().isSourceOfType(Fluids.FLOWING_WATER))) {
			return false;
		}
		return original.call(carver, config, state);
	}
}
