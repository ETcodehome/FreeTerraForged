package etcodehome.freeterraforged.mixin;

import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.ServerLevelData;
import etcodehome.freeterraforged.data.worldgen.preset.PresetManager;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.data.worldgen.preset.settings.SpawnType;
import etcodehome.freeterraforged.data.worldgen.preset.settings.WorldSettings;
import etcodehome.freeterraforged.registries.FTFRegistries;
import etcodehome.freeterraforged.test.PresetValidationRunner;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler;
import etcodehome.freeterraforged.world.worldgen.perf.WorldgenBenchmarkRunner;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenResourceRevision;

@Mixin(MinecraftServer.class)
class MixinMinecraftServer implements WorldgenResourceRevision {
	@Unique
	private long freeterraforged$worldgenResourceRevision;

	@Override
	public long worldgenResourceRevision() {
		return this.freeterraforged$worldgenResourceRevision;
	}

	@Override
	public long advanceWorldgenResourceRevision() {
		return ++this.freeterraforged$worldgenResourceRevision;
	}

	@Inject(
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Climate$Sampler;findSpawnPosition()Lnet/minecraft/core/BlockPos;"
		),
		method = "setInitialSpawn"
	)
    private static void findSpawnPosition(ServerLevel serverLevel, ServerLevelData serverLevelData, boolean bl, boolean bl2, CallbackInfo callback) {
		RandomState randomState = serverLevel.getChunkSource().randomState();
		Climate.Sampler sampler = randomState.sampler();
		WorldSettings.Properties properties = serverLevel.registryAccess().lookup(FTFRegistries.PRESET)
			.flatMap((registry) -> registry.get(Preset.KEY))
			.map(preset -> preset.value().world().properties)
			.orElseGet(() -> PresetManager.PM.isLoaded ? PresetManager.PM.worldSettings.properties : null);

		if (properties != null
			&& (Object) randomState instanceof FTFRandomState rtfRandomState
			&& (Object) sampler instanceof FTFClimateSampler rtfClimateSampler
			&& rtfRandomState.generatorContext() != null) {
			BlockPos searchCenter = properties.spawnType.getSearchCenter(
				rtfRandomState.generatorContext(), properties
			);
			rtfClimateSampler.setSpawnSearch(new FTFClimateSampler.SpawnSearch(
				properties.spawnType, searchCenter
			));
		}
    }

	@Inject(
		at = @At("TAIL"),
		method = "setInitialSpawn"
	)
	private static void freeterraforged$applyUserSelectedSpawn(ServerLevel serverLevel, ServerLevelData serverLevelData, boolean bl, boolean bl2, CallbackInfo callback) {
		if (bl) {
			return;
		}
		WorldSettings.Properties properties = serverLevel.registryAccess().lookup(FTFRegistries.PRESET)
			.flatMap(registry -> registry.get(Preset.KEY))
			.map(preset -> preset.value().world().properties)
			.orElseGet(() -> PresetManager.PM.isLoaded ? PresetManager.PM.worldSettings.properties : null);

		if (properties != null && properties.spawnType == SpawnType.USER_SELECTED) {
			int x = properties.spawnX;
			int z = properties.spawnZ;
			int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			if (y < serverLevel.getMinBuildHeight() + 1) {
				y = serverLevel.getSeaLevel() + 1;
			}
			while (y < serverLevel.getMaxBuildHeight() && (serverLevel.getBlockState(new BlockPos(x, y, z)).blocksMotion() || serverLevel.getBlockState(new BlockPos(x, y + 1, z)).blocksMotion())) {
				y++;
			}
			serverLevelData.setSpawn(new BlockPos(x, y, z), 0.0f);
		}
	}

	@Inject(at = @At("TAIL"), method = "tickServer")
	private void freeterraforged$onTickServer(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
		WorldgenBenchmarkRunner.onServerTick((MinecraftServer) (Object) this);
		PresetValidationRunner.onServerTick((MinecraftServer) (Object) this);
	}
}
