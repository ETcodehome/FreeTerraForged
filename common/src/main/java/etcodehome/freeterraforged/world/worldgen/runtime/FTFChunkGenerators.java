package etcodehome.freeterraforged.world.worldgen.runtime;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import etcodehome.freeterraforged.platform.RegistryUtil;

public final class FTFChunkGenerators {
	public static final MapCodec<TerraForgedChunkGenerator> TERRAFORGED = TerraForgedChunkGenerator.CODEC;

	private FTFChunkGenerators() {
	}

	public static void bootstrap() {
		register("noise", TERRAFORGED);
	}

	private static void register(String name, MapCodec<? extends ChunkGenerator> codec) {
		RegistryUtil.register(BuiltInRegistries.CHUNK_GENERATOR, name, codec);
	}
}
