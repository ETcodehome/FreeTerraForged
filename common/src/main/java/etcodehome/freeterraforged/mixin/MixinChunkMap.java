package etcodehome.freeterraforged.mixin;

import java.util.Objects;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.core.registries.Registries;
import org.apache.commons.lang3.mutable.MutableObject;
import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.network.FlowFieldSync;
import etcodehome.freeterraforged.world.worldgen.ChunkFlowField;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.FlowSettingsSnapshot;
import etcodehome.freeterraforged.world.worldgen.IFlowFieldHolder;
import etcodehome.freeterraforged.world.worldgen.IFlowSettingsHolder;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfigStore;
import etcodehome.freeterraforged.world.worldgen.runtime.TagEpoch;
import etcodehome.freeterraforged.world.worldgen.runtime.TerraForgedChunkGenerator;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenEpoch;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenSettingsIdentity;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenFingerprints;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenResourceRevision;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenContributionRevision;

@Mixin(ChunkMap.class)
public class MixinChunkMap {
	@Shadow
	private RandomState randomState;

	@Shadow
	@Final
	ServerLevel level;

	@WrapOperation(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;createState(Lnet/minecraft/core/HolderLookup;Lnet/minecraft/world/level/levelgen/RandomState;J)Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"
		)
	)
	private ChunkGeneratorStructureState freeterraforged$initializeWorldgenEpoch(
		ChunkGenerator chunkGenerator,
		HolderLookup<StructureSet> structureSets,
		RandomState randomState,
		long seed,
		Operation<ChunkGeneratorStructureState> original
	) {
		freeterraforged$initializeWorldgenEpochForLevel(this.level, chunkGenerator);
		return original.call(chunkGenerator, structureSets, randomState, seed);
	}

	@Unique
	private void freeterraforged$initializeWorldgenEpochForLevel(ServerLevel serverLevel, ChunkGenerator chunkGenerator) {
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

	@Inject(at = @At("HEAD"), method = "playerLoadedChunk")
	private void ftf$syncFlowSettings(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk, CallbackInfo ci) {
		FlowFieldSync.syncFlowSettingsIfNeeded(player);
	}

	@Inject(at = @At("TAIL"), method = "playerLoadedChunk")
	private void ftf$onPlayerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk, CallbackInfo ci) {
		if (chunk instanceof IFlowFieldHolder holder) {
			ChunkFlowField flowField = holder.freeterraforged$getFlowField();
			if (flowField != null && flowField.hasRivers()) {
				FlowFieldSync.sendToPlayer(player, chunk.getPos(), flowField.getRawGrid());
			}
		}
	}
}
