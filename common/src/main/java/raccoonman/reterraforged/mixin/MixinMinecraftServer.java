package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.ServerLevelData;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenResourceRevision;

@Mixin(MinecraftServer.class)
class MixinMinecraftServer implements WorldgenResourceRevision {
	@Unique
	private long reterraforged$worldgenResourceRevision;

	@Override
	public long worldgenResourceRevision() {
		return this.reterraforged$worldgenResourceRevision;
	}

	@Override
	public long advanceWorldgenResourceRevision() {
		return ++this.reterraforged$worldgenResourceRevision;
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
		serverLevel.registryAccess().lookup(RTFRegistries.PRESET).flatMap((registry) -> {
			return registry.get(Preset.KEY);
		}).ifPresent((preset) -> {
			if ((Object) randomState instanceof RTFRandomState rtfRandomState
				&& (Object) sampler instanceof RTFClimateSampler rtfClimateSampler
				&& rtfRandomState.plan() != null
				&& rtfClimateSampler.getWorldgenPlan() != null) {
				var properties = preset.value().world().properties;
				BlockPos searchCenter = properties.spawnType.getSearchCenter(
					rtfRandomState.generatorContext(), properties
				);
				rtfClimateSampler.setSpawnSearch(new RTFClimateSampler.SpawnSearch(
					properties.spawnType, searchCenter
				));
			}
		});
    }
}
