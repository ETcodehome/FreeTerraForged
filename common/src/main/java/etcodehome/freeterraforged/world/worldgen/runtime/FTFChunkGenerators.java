package etcodehome.freeterraforged.world.worldgen.runtime;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import etcodehome.freeterraforged.platform.RegistryUtil;

public final class FTFChunkGenerators {
	public static final Codec<TerraForgedChunkGenerator> TERRAFORGED = TerraForgedChunkGenerator.REGISTRY_CODEC;

	private FTFChunkGenerators() {
	}

	public static void bootstrap() {
		register("noise", TERRAFORGED);
	}

	private static void register(String name, Codec<? extends ChunkGenerator> codec) {
		RegistryUtil.register(BuiltInRegistries.CHUNK_GENERATOR, name, codec);
	}
}
