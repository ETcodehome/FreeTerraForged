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

@Mixin(ChunkMap.class)
public class MixinChunkMap {
	@Shadow
    private RandomState randomState;

	@Inject(
		at = @At("TAIL"),
		method = "<init>"
	)
	public void ChunkMap(ServerLevel serverLevel, LevelStorageSource.LevelStorageAccess storageAccess, DataFixer dataFixer, StructureTemplateManager templateLoader, Executor executor, BlockableEventLoop<Runnable> eventLoop, LightChunkGetter lightChunkGetter, ChunkGenerator chunkGenerator, ChunkProgressListener chunkProgressListener, ChunkStatusUpdateListener chunkStatusListener, Supplier<DimensionDataStorage> dimensionStorage, int viewDistance, boolean syncChunkWrites, CallbackInfo callback) {
		if (!((Object) this.randomState instanceof RTFRandomState rtfRandomState)
			|| !rtfRandomState.isTerraForged()) {
			return;
		}
		LevelStem selectedStem = new LevelStem(serverLevel.dimensionTypeRegistration(), chunkGenerator);
		String generatorType = chunkGenerator.getTypeNameForDataFixer()
			.map(key -> key.location().toString())
			.orElse("unregistered");
		String settingsIdentity = generatorType;
		if (chunkGenerator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noiseGenerator) {
			settingsIdentity += "|" + noiseGenerator.generatorSettings().unwrapKey()
				.map(key -> key.location().toString())
				.orElse("inline_noise_settings");
		}
		WorldgenEpoch epoch = WorldgenEpoch.create(
			Registries.levelToLevelStem(serverLevel.dimension()),
			serverLevel.getSeed(),
			serverLevel.registryAccess(),
			selectedStem,
			settingsIdentity,
			"unavailable:resource_layers_not_exposed_at_level_construction",
			new TagEpoch(0L, WorldgenFingerprints.tags(serverLevel.registryAccess()))
		);
		try {
			if (!(chunkGenerator instanceof TerraForgedChunkGenerator terraForged)) {
				throw new IllegalStateException(
					"FTF terrain requires the registered TerraForged generator root"
				);
			}
			terraForged.initializeEpoch(epoch, rtfRandomState);
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
