package raccoonman.reterraforged.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;

@Mixin(ServerLevel.class)
public class MixinServerLevel {
	@Inject(method = "close", at = @At("HEAD"))
	private void reterraforged$closeWorldgenEpoch(CallbackInfo ci) {
		ServerLevel level = (ServerLevel) (Object) this;
		if (level.getChunkSource().getGenerator() instanceof TerraForgedChunkGenerator generator) {
			try {
				generator.close();
			} catch (Exception error) {
				throw new IllegalStateException("Failed to close FTF worldgen epoch", error);
			}
		}
	}
}
