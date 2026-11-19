package etcodehome.freeterraforged.server.preset;

import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.ActivePresetSnapshot;
import etcodehome.freeterraforged.data.worldgen.preset.settings.EffectivePresetSnapshot;
import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.runtime.BiomeReplacementConfigSnapshot;
import etcodehome.freeterraforged.world.worldgen.runtime.MinecraftBiomeSourceGraphs;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Resolves and snapshots the preset that actually owns a loaded FTF worldgen runtime. */
public final class ActivePresetSnapshotService {
	public static final Codec<Climate.ParameterList<Holder<Biome>>> BIOME_PARAMETERS_CODEC =
		Climate.ParameterList.codec(Biome.CODEC.fieldOf("biome"));

	private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();
	private static final ResourceKey<Level> FTF_DIMENSION = ResourceKey.create(
		Registries.DIMENSION,
		FTFCommon.location("freeterraforged")
	);

	private ActivePresetSnapshotService() {
	}

	@Nullable
	public static ActivePreset find(CommandSourceStack source) {
		ServerLevel current = source.getLevel();
		ActivePreset active = fromLevel(current);
		if (active != null) {
			return active;
		}

		MinecraftServer server = source.getServer();
		if (server == null) {
			return null;
		}

		ServerLevel dedicated = server.getLevel(FTF_DIMENSION);
		active = fromDistinctLevel(dedicated, current);
		if (active != null) {
			return active;
		}

		ServerLevel overworld = server.overworld();
		active = fromDistinctLevel(overworld, current, dedicated);
		if (active != null) {
			return active;
		}

		for (ServerLevel level : server.getAllLevels()) {
			if (isDistinct(level, current, dedicated, overworld)
				&& level.dimension().location().getNamespace().equals(FTFCommon.MOD_ID)) {
				active = fromLevel(level);
				if (active != null) {
					return active;
				}
			}
		}
		for (ServerLevel level : server.getAllLevels()) {
			if (isDistinct(level, current, dedicated, overworld)) {
				active = fromLevel(level);
				if (active != null) {
					return active;
				}
			}
		}
		return null;
	}

	public static ActivePresetSnapshot snapshot(CommandSourceStack source, ActivePreset active) {
		ServerPlayer player = source.getPlayer();
		BlockPos spawn = active.level().getSharedSpawnPos();
		BlockPos origin = player != null && player.serverLevel() == active.level()
			? player.blockPosition()
			: spawn;
		String replacements = active.replacements() == null ? "" : active.replacements().content();
		String biomeParameters = encodeBiomeParameters(active);
		return new ActivePresetSnapshot(
			encode(active.preset()),
			active.level().dimension().location().toString(),
			active.state().seed(),
			origin.getX(),
			origin.getZ(),
			spawn.getX(),
			spawn.getZ(),
			replacements,
			biomeParameters
		);
	}

	public static String encodeBiomeParameters(ActivePreset active) {
		Climate.ParameterList<Holder<Biome>> parameters = extractBiomeParameters(active);
		if (parameters == null || parameters.values().isEmpty()) {
			return "";
		}
		try {
			RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, active.level().registryAccess());
			return BIOME_PARAMETERS_CODEC.encodeStart(ops, parameters)
				.resultOrPartial(FTFCommon.LOGGER::warn)
				.map(JsonElement::toString)
				.orElse("");
		} catch (RuntimeException failure) {
			FTFCommon.LOGGER.warn("Failed to encode active world biome parameters for preview", failure);
			return "";
		}
	}

	@Nullable
	private static Climate.ParameterList<Holder<Biome>> extractBiomeParameters(ActivePreset active) {
		if (active.state() != null && active.state().plan() != null) {
			WorldgenPlans.BiomeComposition composition = active.state().plan().biomeComposition();
			if (composition != null) {
				if (composition.candidateRoot().isPresent()) {
					return composition.candidateRoot().get().candidates();
				}
				if (!composition.entries().isEmpty()) {
					return new Climate.ParameterList<>(composition.entries());
				}
			}
		}
		if (active.level() != null && active.level().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator) {
			BiomeSource source = MinecraftBiomeSourceGraphs.acquisitionSource(generator);
			if (source instanceof MultiNoiseBiomeSource multiNoise) {
				try {
					return MinecraftBiomeSourceGraphs.multiNoiseRoot(multiNoise, active.level().registryAccess()).candidates();
				} catch (RuntimeException ignored) {
				}
			}
		}
		return null;
	}

	public static String encode(Preset preset) {
		return PRETTY_JSON.toJson(EffectivePresetSnapshot.create(preset));
	}

	@Nullable
	private static ActivePreset fromDistinctLevel(@Nullable ServerLevel level, ServerLevel... excluded) {
		return level != null && isDistinct(level, excluded) ? fromLevel(level) : null;
	}

	private static boolean isDistinct(ServerLevel level, ServerLevel... excluded) {
		for (ServerLevel other : excluded) {
			if (level == other) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	private static ActivePreset fromLevel(@Nullable ServerLevel level) {
		if (level == null || !((Object) level.getChunkSource().randomState() instanceof FTFRandomState state)) {
			return null;
		}
		Preset preset = state.preset();
		if (preset == null) {
			return null;
		}
		BiomeReplacementConfigSnapshot replacements = state.epoch() == null
			? null
			: state.epoch().biomeReplacementConfig();
		return new ActivePreset(level, state, preset, replacements);
	}

	public record ActivePreset(
		ServerLevel level,
		FTFRandomState state,
		Preset preset,
		@Nullable BiomeReplacementConfigSnapshot replacements
	) {
	}
}
