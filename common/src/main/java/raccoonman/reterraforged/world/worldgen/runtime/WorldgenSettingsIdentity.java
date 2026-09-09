package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

public final class WorldgenSettingsIdentity {
	private WorldgenSettingsIdentity() {
	}

	public static String describe(ChunkGenerator generator) {
		Objects.requireNonNull(generator, "generator");
		String type = generator.getTypeNameForDataFixer()
			.map(key -> key.location().toString())
			.orElseGet(() -> "unregistered:" + generator.getClass().getName());
		if (generator instanceof NoiseBasedChunkGenerator noiseGenerator) {
			return type + "|" + noiseGenerator.generatorSettings().unwrapKey()
				.map(key -> key.location().toString())
				.orElse("inline_noise_settings");
		}
		return type;
	}
}
