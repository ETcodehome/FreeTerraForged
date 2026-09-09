package raccoonman.reterraforged.world.worldgen.runtime;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import raccoonman.reterraforged.platform.RegistryUtil;

public final class RTFChunkGenerators {
	public static final MapCodec<TerraForgedChunkGenerator> TERRAFORGED = TerraForgedChunkGenerator.CODEC;

	private RTFChunkGenerators() {
	}

	public static void bootstrap() {
		register("noise", TERRAFORGED);
	}

	private static void register(String name, MapCodec<? extends ChunkGenerator> codec) {
		RegistryUtil.register(BuiltInRegistries.CHUNK_GENERATOR, name, codec);
	}
}
