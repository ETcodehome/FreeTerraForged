package raccoonman.reterraforged.mixin;

import net.minecraft.server.level.ServerLevel;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

@Mixin(ServerLevel.class)
public class MixinServerLevel {
	@WrapMethod(method = "close")
	private void reterraforged$closeWorldgenEpoch(Operation<Void> original) {
		ServerLevel level = (ServerLevel) (Object) this;
		TerraForgedChunkGenerator generator = level.getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator terra
			? terra
			: null;
		RuntimeException runtimeFailure = null;
		Error errorFailure = null;
		try {
			original.call();
		} catch (RuntimeException failure) {
			runtimeFailure = failure;
			throw failure;
		} catch (Error failure) {
			errorFailure = failure;
			throw failure;
		} finally {
			if (generator != null) {
				try {
					generator.close();
				} catch (RuntimeException | Error cleanupFailure) {
					if (runtimeFailure != null) {
						runtimeFailure.addSuppressed(cleanupFailure);
					} else if (errorFailure != null) {
						errorFailure.addSuppressed(cleanupFailure);
					} else {
						throw cleanupFailure;
					}
				}
			}
		}
	}
}
