package raccoonman.reterraforged.mixin;

import java.util.concurrent.Executor;
import java.util.Objects;
import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.datafixers.DataFixer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.FlowSettingsSnapshot;
import raccoonman.reterraforged.world.worldgen.IFlowSettingsHolder;
import raccoonman.reterraforged.world.worldgen.runtime.TagEpoch;
import raccoonman.reterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenEpoch;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenFingerprints;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenResourceRevision;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionRevision;

@Mixin(ChunkMap.class)
public class MixinChunkMap {
	@Shadow
    private RandomState randomState;

	@Inject(
		at = @At("TAIL"),
		method = "<init>"
	)
	public void ChunkMap(ServerLevel serverLevel, LevelStorageSource.LevelStorageAccess storageAccess, DataFixer dataFixer, StructureTemplateManager templateLoader, Executor executor, BlockableEventLoop<Runnable> eventLoop, LightChunkGetter lightChunkGetter, ChunkGenerator chunkGenerator, ChunkProgressListener chunkProgressListener, ChunkStatusUpdateListener chunkStatusListener, Supplier<DimensionDataStorage> dimensionStorage, int viewDistance, boolean syncChunkWrites, CallbackInfo callback) {
		if (!((Object) this.randomState instanceof RTFRandomState rtfRandomState)) {
			throw new IllegalStateException("RandomState does not expose the FTF ownership contract");
		}
		if (!(chunkGenerator instanceof TerraForgedChunkGenerator terraForged)) {
			if (rtfRandomState.isTerraForged()) {
				throw new IllegalStateException(
					"FTF density functions require the registered TerraForged generator root"
				);
			}
			return;
		}
		LevelStem selectedStem = new LevelStem(serverLevel.dimensionTypeRegistration(), chunkGenerator);
		String settingsIdentity = raccoonman.reterraforged.world.worldgen.runtime.WorldgenSettingsIdentity
			.describe(chunkGenerator);
		long resourceRevision = ((WorldgenResourceRevision) serverLevel.getServer())
			.worldgenResourceRevision();
		var providerCatalog = terraForged.acquireProviderCatalog();
		var dimension = Registries.levelToLevelStem(serverLevel.dimension());
		WorldgenEpoch epoch = WorldgenEpoch.create(
			dimension,
			serverLevel.getSeed(),
			serverLevel.registryAccess(),
			selectedStem,
			settingsIdentity,
			resourceRevision,
			WorldgenFingerprints.resourceLayers(
				serverLevel.getServer(),
				resourceRevision
			),
			new TagEpoch(0L, WorldgenFingerprints.tags(serverLevel.registryAccess())),
			WorldgenContributionRevision.snapshot(dimension, providerCatalog)
		);
		try {
			terraForged.initializeEpoch(epoch, rtfRandomState, providerCatalog);
			((IFlowSettingsHolder) serverLevel).reterraforged$setFlowSettings(
				FlowSettingsSnapshot.from(Objects.requireNonNull(
					rtfRandomState.preset(),
					"FTF worldgen initialized without its selected preset"
				).flow())
			);
		} catch (Exception error) {
			throw new IllegalStateException("Failed to initialize FTF worldgen epoch", error);
		}
	}
}
