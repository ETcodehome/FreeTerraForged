package raccoonman.reterraforged.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk {

	@Inject(
			method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
			at = @At("TAIL")
	)
	private void bridgeFlowFieldOnChunkPromotion(ServerLevel serverLevel, ProtoChunk protoChunk, @Nullable LevelChunk.PostLoadProcessor postLoadProcessor, CallbackInfo ci) {
		if ((Object) protoChunk instanceof IFlowFieldHolder protoHolder && (Object) this instanceof IFlowFieldHolder levelHolder) {
			var source = protoHolder.reterraforged$getFlowField();
			if (source != null) {
				levelHolder.reterraforged$getOrCreateFlowField().copyFrom(source);
			}
		}

	}
}
