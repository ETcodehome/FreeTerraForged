package etcodehome.freeterraforged.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.datafixers.DataFixer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.FlowSettingsSnapshot;
import etcodehome.freeterraforged.world.worldgen.IFlowSettingsHolder;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfigStore;
import etcodehome.freeterraforged.world.worldgen.runtime.IFTFChunkMap;
import etcodehome.freeterraforged.world.worldgen.runtime.TagEpoch;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenContributionRevision;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenEpoch;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenFingerprints;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenResourceRevision;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenSettingsIdentity;

@Mixin(ChunkMap.class)
public abstract class MixinChunkMap implements IFTFChunkMap {
	@Shadow @Final ServerLevel level;
	@Shadow private RandomState randomState;
	@Shadow private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
	@Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
	@Shadow private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
	@Shadow @Final LongSet toDrop;
	@Shadow private Long2ByteMap chunkTypeCache;
	@Shadow private Long2LongMap chunkSaveCooldowns;
	@Shadow private List<ChunkGenerationTask> pendingGenerationTasks;
	@Shadow private Queue<Runnable> unloadQueue;
	@Shadow private PoiManager poiManager;

	@Shadow abstract void updatePlayerStatus(ServerPlayer player, boolean track);
	@Shadow public abstract DistanceManager getDistanceManager();

	@Unique
	private LevelStorageSource.LevelStorageAccess freeterraforged$storageAccess;

	@Inject(
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;createState(Lnet/minecraft/core/HolderLookup;Lnet/minecraft/world/level/levelgen/RandomState;J)Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"
		),
		method = "<init>"
	)
	public void ChunkMap(ServerLevel serverLevel, LevelStorageSource.LevelStorageAccess storageAccess, DataFixer dataFixer, StructureTemplateManager templateLoader, Executor executor, BlockableEventLoop<Runnable> eventLoop, LightChunkGetter lightChunkGetter, ChunkGenerator chunkGenerator, ChunkProgressListener chunkProgressListener, ChunkStatusUpdateListener chunkStatusListener, Supplier<DimensionDataStorage> dimensionStorage, int viewDistance, boolean syncChunkWrites, CallbackInfo callback) {
		this.freeterraforged$storageAccess = storageAccess;

		if (isSyntheticOrDummyLevel(serverLevel)) {
			return;
		}

		if (!((Object) this.randomState instanceof FTFRandomState rtfRandomState)) {
			throw new IllegalStateException("RandomState does not expose the FTF ownership contract");
		}
		if (!(chunkGenerator instanceof TerraForgedChunkGenerator terraForged)) {
			if (serverLevel.dimension() == Level.OVERWORLD) {
				String settingsIdentity = WorldgenSettingsIdentity.describe(chunkGenerator);
				FTFCommon.LOGGER.warn(
					"FTF terrain generation is inactive for the Overworld: resolved generator is {}. "
						+ "The world will use that generator; select the FreeTerraForged world type to use FTF terrain.",
					settingsIdentity
				);
			}
			return;
		}

		LevelStem selectedStem = new LevelStem(serverLevel.dimensionTypeRegistration(), chunkGenerator);
		String settingsIdentity = etcodehome.freeterraforged.world.worldgen.runtime.WorldgenSettingsIdentity
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
			BiomeReplacementConfigStore.capture(),
			WorldgenContributionRevision.snapshot(dimension, providerCatalog)
		);
		try {
			terraForged.initializeEpoch(epoch, rtfRandomState, providerCatalog);
			((IFlowSettingsHolder) serverLevel).freeterraforged$setFlowSettings(
				FlowSettingsSnapshot.from(Objects.requireNonNull(
					rtfRandomState.preset(),
					"FTF worldgen initialized without its selected preset"
				).flow())
			);
		} catch (IllegalStateException error) {
			// If initialization failed specifically because the generator root is already owned,
			// log or ignore for synthetic/re-used generator instances rather than crashing the server.
			if (error.getMessage() != null && error.getMessage().contains("already owned by worldgen epoch")) {
				return;
			}
			throw new IllegalStateException("Failed to initialize FTF worldgen epoch", error);
		} catch (Exception error) {
			throw new IllegalStateException("Failed to initialize FTF worldgen epoch", error);
		}
	}

	@Override
	public void freeterraforged$regenerateAllWorldContents() {
		if (this.level == null || isSyntheticOrDummyLevel(this.level)) {
			return;
		}

		FTFCommon.LOGGER.info("[FTF] Regenerating world contents for dimension {}", this.level.dimension().location());

		// 1. Untrack all players and clear their chunk views (drops chunks on client)
		List<ServerPlayer> players = new ArrayList<>(this.level.players());
		for (ServerPlayer player : players) {
			try {
				if (player.getVehicle() != null) {
					player.stopRiding();
				}
				this.updatePlayerStatus(player, false);
			} catch (Exception e) {
				FTFCommon.LOGGER.warn("[FTF] Failed to untrack player {}: {}", player.getName().getString(), e.getMessage());
			}
		}

		// 2. Discard all non-player entities in this level
		try {
			List<Entity> toDiscard = new ArrayList<>();
			for (Entity entity : this.level.getAllEntities()) {
				if (!(entity instanceof Player)) {
					toDiscard.add(entity);
				}
			}
			for (Entity entity : toDiscard) {
				entity.discard();
			}
		} catch (Exception e) {
			FTFCommon.LOGGER.warn("[FTF] Failed to discard non-player entities: {}", e.getMessage());
		}

		// 3. Mark loaded chunks un-saved and unloaded so they don't get written out
		if (this.updatingChunkMap != null) {
			for (ChunkHolder holder : this.updatingChunkMap.values()) {
				try {
					ChunkAccess chunk = holder.getLatestChunk();
					if (chunk != null) {
						chunk.setUnsaved(false);
						if (chunk instanceof LevelChunk lc) {
							lc.setLoaded(false);
						}
					}
				} catch (Exception ignored) {}
			}
		}

		// 4. Safely clear pending writes and close region file handles to release file locks on Windows
		// 4a. Chunk storage worker
		try {
			IOWorker chunkWorker = ((ChunkStorageAccessor) (Object) this).freeterraforged$getWorker();
			freeterraforged$clearAndCloseWorker(chunkWorker);
		} catch (Exception e) {
			FTFCommon.LOGGER.warn("[FTF] Failed to clear chunk worker: {}", e.getMessage());
		}

		// 4b. POI storage worker
		if (this.poiManager != null) {
			try {
				SectionStorageAccessor ssa = (SectionStorageAccessor) (Object) this.poiManager;
				ssa.freeterraforged$getStorage().clear();
				ssa.freeterraforged$getDirty().clear();
				if ((Object) this.poiManager instanceof PoiManagerAccessor pma) {
					pma.freeterraforged$getLoadedChunks().clear();
				}
				SimpleRegionStorage srs = ssa.freeterraforged$getSimpleRegionStorage();
				if (srs != null) {
					IOWorker poiWorker = ((SimpleRegionStorageAccessor) (Object) srs).freeterraforged$getWorker();
					freeterraforged$clearAndCloseWorker(poiWorker);
				}
			} catch (Exception e) {
				FTFCommon.LOGGER.warn("[FTF] Failed to clear POI worker: {}", e.getMessage());
			}
		}

		// 4c. Entity storage worker
		if ((Object) this.level instanceof ServerLevelAccessor sla) {
			try {
				PersistentEntitySectionManager<Entity> em = sla.freeterraforged$getEntityManager();
				if ((Object) em instanceof PersistentEntitySectionManagerAccessor pema) {
					EntityPersistentStorage<?> ps = pema.freeterraforged$getPermanentStorage();
					if (ps instanceof EntityStorage es) {
						EntityStorageAccessor esa = (EntityStorageAccessor) (Object) es;
						esa.freeterraforged$getEmptyChunks().clear();
						SimpleRegionStorage srs = esa.freeterraforged$getSimpleRegionStorage();
						if (srs != null) {
							IOWorker entityWorker = ((SimpleRegionStorageAccessor) (Object) srs).freeterraforged$getWorker();
							freeterraforged$clearAndCloseWorker(entityWorker);
						}
					}
				}
			} catch (Exception e) {
				FTFCommon.LOGGER.warn("[FTF] Failed to clear entity worker: {}", e.getMessage());
			}
		}

		// 5. Delete all world contents on disk (region, poi, entities)
		LevelStorageSource.LevelStorageAccess storageAccess = this.freeterraforged$storageAccess;
		if (storageAccess == null && this.level.getServer() != null && (Object) this.level.getServer() instanceof MinecraftServerAccessor msa) {
			storageAccess = msa.freeterraforged$getStorageSource();
		}
		if (storageAccess != null) {
			try {
				Path dimPath = storageAccess.getDimensionPath(this.level.dimension());
				freeterraforged$deleteDirectoryContents(dimPath.resolve("region"));
				freeterraforged$deleteDirectoryContents(dimPath.resolve("poi"));
				freeterraforged$deleteDirectoryContents(dimPath.resolve("entities"));
			} catch (Exception e) {
				FTFCommon.LOGGER.warn("[FTF] Failed to delete disk contents: {}", e.getMessage());
			}
		}

		// 6. Clear in-memory maps in ChunkMap and DistanceManager
		if (this.updatingChunkMap != null) this.updatingChunkMap.clear();
		this.visibleChunkMap = new Long2ObjectLinkedOpenHashMap<>();
		if (this.pendingUnloads != null) this.pendingUnloads.clear();
		if (this.toDrop != null) this.toDrop.clear();
		if (this.chunkTypeCache != null) this.chunkTypeCache.clear();
		if (this.chunkSaveCooldowns != null) this.chunkSaveCooldowns.clear();
		if (this.pendingGenerationTasks != null) this.pendingGenerationTasks.clear();
		if (this.unloadQueue != null) this.unloadQueue.clear();

		DistanceManager dm = this.getDistanceManager();
		if ((Object) dm instanceof DistanceManagerAccessor dma) {
			dma.freeterraforged$getTickets().clear();
			dma.freeterraforged$getPlayersPerChunk().clear();
			dma.freeterraforged$getChunksToUpdateFutures().clear();
			dma.freeterraforged$getTicketsToRelease().clear();
		}

		if ((Object) this.level.getChunkSource() instanceof ServerChunkCache scc) {
			((ServerChunkCacheAccessor) (Object) scc).freeterraforged$clearCache();
		}

		// 7. Refresh flow settings
		if (this.level instanceof IFlowSettingsHolder flowHolder) {
			flowHolder.freeterraforged$setFlowSettings(FlowSettingsSnapshot.from(PresetManager.PM.flowSettings));
		}

		// 8. Safely reposition players to the new terrain surface
		ChunkGenerator generator = this.level.getChunkSource().getGenerator();
		RandomState rs = this.level.getChunkSource().randomState();
		for (ServerPlayer player : players) {
			try {
				int blockX = player.getBlockX();
				int blockZ = player.getBlockZ();
				int surfaceY = generator.getBaseHeight(blockX, blockZ, Heightmap.Types.MOTION_BLOCKING, this.level, rs);
				if (surfaceY < this.level.getMinBuildHeight() || surfaceY > this.level.getMaxBuildHeight()) {
					surfaceY = generator.getSeaLevel() + 2;
				}
				player.teleportTo(player.getX(), surfaceY + 2.0, player.getZ());
				player.setDeltaMovement(Vec3.ZERO);
				player.resetFallDistance();
			} catch (Exception e) {
				FTFCommon.LOGGER.warn("[FTF] Failed to reposition player {}: {}", player.getName().getString(), e.getMessage());
			}
		}

		// 9. Re-track players and re-schedule chunk generation around players
		for (ServerPlayer player : players) {
			try {
				this.updatePlayerStatus(player, true);
			} catch (Exception e) {
				FTFCommon.LOGGER.warn("[FTF] Failed to re-track player {}: {}", player.getName().getString(), e.getMessage());
			}
		}

		if (players.isEmpty()) {
			this.level.setDefaultSpawnPos(this.level.getSharedSpawnPos(), this.level.getSharedSpawnAngle());
		}

		if ((Object) this.level.getChunkSource() instanceof ServerChunkCacheAccessor sca) {
			sca.freeterraforged$runDistanceManagerUpdates();
		}
		FTFCommon.LOGGER.info("[FTF] World contents purged and regeneration scheduled.");
	}

	@Unique
	private static void freeterraforged$clearAndCloseWorker(IOWorker worker) {
		if (worker == null) return;
		try {
			IOWorkerAccessor wa = (IOWorkerAccessor) (Object) worker;
			wa.freeterraforged$getPendingWrites().clear();
			RegionFileStorage storage = wa.freeterraforged$getStorage();
			if (storage != null) {
				Long2ObjectLinkedOpenHashMap<RegionFile> cache = ((RegionFileStorageAccessor) (Object) storage).freeterraforged$getRegionCache();
				synchronized (cache) {
					for (RegionFile rf : cache.values()) {
						try {
							rf.close();
						} catch (Exception ignored) {}
					}
					cache.clear();
				}
			}
		} catch (Exception e) {
			FTFCommon.LOGGER.warn("[FTF] Failed to clear IOWorker: {}", e.getMessage());
		}
	}

	@Unique
	private static void freeterraforged$deleteDirectoryContents(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder())
				  .filter(p -> !p.equals(dir))
				  .forEach(p -> {
					  try {
						  Files.deleteIfExists(p);
					  } catch (IOException e) {
						  FTFCommon.LOGGER.warn("[FTF] Failed to delete file {}: {}", p, e.getMessage());
					  }
				  });
		} catch (IOException e) {
			FTFCommon.LOGGER.warn("[FTF] Failed to walk directory {}: {}", dir, e.getMessage());
		}
	}

	private static boolean isSyntheticOrDummyLevel(ServerLevel serverLevel) {
		if (serverLevel == null || serverLevel.getServer() == null) {
			return true;
		}
		if (!(serverLevel.getServer() instanceof WorldgenResourceRevision)) {
			return true;
		}

		// Standard Vanilla and modded dimensions instantiate ServerLevel directly.
		// Fake/test levels (e.g. Moonlight's FakeServerLevel, Supplementaries' BlockTestLevel) subclass ServerLevel.
		return serverLevel.getClass() != ServerLevel.class;
	}
}
