package etcodehome.freeterraforged.mixin;

import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A tree may produce foliage without any recorded logs to support a bee nest.
 * Inspired by Norboc's ServerFix-Tweaks; attribution is in META-INF/licenses/serverfix-tweaks.txt.
 */
@Mixin(BeehiveDecorator.class)
abstract class MixinBeehiveDecorator {
	// Guard the log-dependent work after vanilla's probability draw. A HEAD cancellation would
	// change the random stream even when vanilla would have declined to place a nest.
	@Inject(method = "place", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/world/level/levelgen/feature/treedecorators/TreeDecorator$Context;logs()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"),
		cancellable = true, require = 1, allow = 1)
	private void freeterraforged$requireNestSupport(TreeDecorator.Context context, CallbackInfo callback) {
		if (context.logs().isEmpty()) {
			callback.cancel();
		}
	}
}
