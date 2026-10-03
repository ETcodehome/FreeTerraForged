package etcodehome.freeterraforged.mixin;

import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BeehiveDecorator.class)
abstract class MixinBeehiveDecorator {
	// Inject after the probability draw to preserve vanilla's random stream.
	@Inject(method = "place", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context;logs()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"),
		cancellable = true, require = 1, allow = 1)
	private void freeterraforged$requireNestSupport(TreeDecorator.Context context, CallbackInfo callback) {
		if (context.logs().isEmpty()) {
			callback.cancel();
		}
	}
}
