package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.world.worldgen.FTFChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk implements FTFChunk {

	@Inject(
			method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
			at = @At("TAIL")
	)
	private void bridgeFlowFieldOnChunkPromotion(ServerLevel serverLevel, ProtoChunk protoChunk, @Nullable LevelChunk.PostLoadProcessor postLoadProcessor, CallbackInfo ci) {
		FTFChunk rtfChunk = (FTFChunk) protoChunk;
		rtfChunk.getMaxHeight().ifPresent(this::setMaxHeight);
		if ((Object) protoChunk instanceof IFlowFieldHolder protoHolder && (Object) this instanceof IFlowFieldHolder levelHolder) {
			levelHolder.reterraforged$getFlowField().copyFrom(protoHolder.reterraforged$getFlowField());
		}

	}
}