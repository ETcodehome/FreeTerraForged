package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedBiomeSourceCodecTest {

	@BeforeAll
	static void setUp() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("UnifiedBiomeSource.codec() returns its registered MapCodec instead of throwing UnsupportedOperationException (Issue #297)")
	void testCodecReturnsRegisteredMapCodec() {
		Biome dummyBiome = new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(0.5F)
			.downfall(0.5F)
			.specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder()
				.fogColor(0)
				.waterColor(0)
				.waterFogColor(0)
				.skyColor(0)
				.build())
			.mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
			.generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY)
			.build();
		FixedBiomeSource acquisitionSource = new FixedBiomeSource(Holder.direct(dummyBiome));
		UnifiedBiomeSource unified = new UnifiedBiomeSource(acquisitionSource, Optional.empty());

		assertSame(UnifiedBiomeSource.CODEC, unified.codec(), "UnifiedBiomeSource.codec() must return UnifiedBiomeSource.CODEC");

		DataResult<Tag> encoded = UnifiedBiomeSource.CODEC.codec().encodeStart(NbtOps.INSTANCE, unified);
		assertTrue(encoded.result().isPresent(), "UnifiedBiomeSource should encode cleanly to NBT for stronghold cache keys");
		Tag tag = encoded.result().orElseThrow();
		assertTrue(tag instanceof CompoundTag compound && compound.contains("acquisition_source"),
			"Encoded UnifiedBiomeSource NBT must include acquisition_source");

		DataResult<UnifiedBiomeSource> decoded = UnifiedBiomeSource.CODEC.codec().parse(NbtOps.INSTANCE, tag);
		assertTrue(decoded.result().isPresent(), "Encoded UnifiedBiomeSource NBT should round-trip decode cleanly");
		UnifiedBiomeSource roundTripped = decoded.result().orElseThrow();
		assertEquals(
			unified.possibleBiomes().size(),
			roundTripped.possibleBiomes().size(),
			"Round-tripped UnifiedBiomeSource should preserve possible biome count"
		);
		DataResult<Tag> reEncoded = UnifiedBiomeSource.CODEC.codec().encodeStart(NbtOps.INSTANCE, roundTripped);
		assertEquals(
			tag,
			reEncoded.result().orElseThrow(),
			"Round-tripped UnifiedBiomeSource should produce identical NBT encoding"
		);
	}

	@Test
	@DisplayName("UnifiedBiomeSource codec gracefully returns DataResult.error when wrapping an unserializable synthetic BiomeSource")
	void testUnserializableAcquisitionSourceReturnsErrorResultInsteadOfThrowing() {
		BiomeSource unserializableSource = new BiomeSource() {
			@Override
			protected MapCodec<? extends BiomeSource> codec() {
				throw new UnsupportedOperationException("Synthetic test biome source cannot be serialized");
			}

			@Override
			protected Stream<Holder<Biome>> collectPossibleBiomes() {
				return Stream.empty();
			}

			@Override
			public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
				return null;
			}
		};

		UnifiedBiomeSource unified = new UnifiedBiomeSource(unserializableSource, Optional.empty());
		DataResult<Tag> encoded = UnifiedBiomeSource.CODEC.codec().encodeStart(NbtOps.INSTANCE, unified);
		assertFalse(encoded.result().isPresent(), "Encoding an unserializable acquisition source should return an empty result, not throw");
		assertNotNull(encoded.error().orElse(null), "Encoding an unserializable acquisition source should populate DataResult.error");
	}
}
