package etcodehome.freeterraforged.mixin;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.FTFWorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.datafixers.DataFixer;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.apache.commons.lang3.mutable.MutableObject;
import etcodehome.freeterraforged.network.FlowFieldSync;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;

import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkMap.class)
public class MixinChunkMap {
	@Shadow
    private RandomState randomState;

	@Redirect(
			method = "<init>",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerLevel;getSeed()J"
			)
	)
	private long beforeChunkMapInit(ServerLevel serverLevel) {
		FTFWorldGenContext.IS_VANILLA_OVERWORLD.set(serverLevel.dimension() == Level.OVERWORLD);
		return serverLevel.getSeed();
	}

	@Inject(
		at = @At("TAIL"),
		method = "<init>"
	)
	public void ChunkMap(ServerLevel serverLevel, LevelStorageSource.LevelStorageAccess storageAccess, DataFixer dataFixer, StructureTemplateManager templateLoader, Executor executor, BlockableEventLoop<Runnable> eventLoop, LightChunkGetter lightChunkGetter, ChunkGenerator chunkGenerator, ChunkProgressListener chunkProgressListener, ChunkStatusUpdateListener chunkStatusListener, Supplier<DimensionDataStorage> dimensionStorage, int viewDistance, boolean syncChunkWrites, CallbackInfo callback) {
		if((Object) this.randomState instanceof FTFRandomState ftfRandomState) {
			ftfRandomState.initialize(serverLevel.registryAccess());
		}
		FTFWorldGenContext.IS_VANILLA_OVERWORLD.remove();
	}

	@Inject(
		at = @At("TAIL"),
		method = "playerLoadedChunk"
	)
	private void onPlayerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk, CallbackInfo ci) {
		if (chunk instanceof IFlowFieldHolder holder) {
			ChunkFlowField flowField = holder.freeterraforged$getFlowField();
			if (flowField != null && flowField.hasRivers()) {
				FlowFieldSync.sendToPlayer(player, chunk.getPos(), flowField.getRawGrid());
			}
		}
	}
}
