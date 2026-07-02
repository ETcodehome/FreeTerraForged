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

			// Check state BEFORE copying
			boolean protoHadRivers = protoHolder.reterraforged$getFlowField().hasRivers();

			// Copy the data over
			levelHolder.reterraforged$getFlowField().copyFrom(protoHolder.reterraforged$getFlowField());

			// Check state AFTER copying
			boolean levelHasRivers = levelHolder.reterraforged$getFlowField().hasRivers();

			// Count actual non-zero values to see if data is real
			int nonZeroBytes = 0;
			for (byte b : levelHolder.reterraforged$getFlowField().getRawGrid()) {
				if (b != 0) nonZeroBytes++;
			}

			System.out.println("[RTF-DEBUG] Promotion at " + protoChunk.getPos() +
					" | Proto Has Rivers: " + protoHadRivers +
					" | Level Has Rivers: " + levelHasRivers +
					" | Non-Zero Cells: " + nonZeroBytes);
		}
	}
}