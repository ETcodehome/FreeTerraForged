package etcodehome.freeterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import etcodehome.freeterraforged.client.network.ActivePresetSnapshotHandlers;
import etcodehome.freeterraforged.network.ActivePresetSnapshotPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

public class ActivePresetSnapshotTest {

	@Test
	public void testEffectivePresetSnapshotRoundTripAllBuiltins() {
		List<Preset> presets = List.of(
			Presets.makeFTFDefault(),
			Presets.modernDefaultWithRivers(),
			Presets.makeCommunityPreset1(),
			Presets.makeLegacyDefault(),
			Presets.makeLegacyBeautiful(),
			Presets.makeLegacyHugeBiomes(),
			Presets.makeLegacyLite(),
			Presets.makeLegacyVanillaish()
		);

		for (Preset original : presets) {
			JsonObject snapshotJson = EffectivePresetSnapshot.create(original);
			assertNotNull(snapshotJson);

			Preset parsed = Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, snapshotJson)
				.resultOrPartial(err -> fail("Failed to parse snapshot: " + err))
				.orElseThrow();

			assertNotNull(parsed);
			assertEquals(original.world().continent.continentScale, parsed.world().continent.continentScale);
			assertEquals(original.world().properties.worldHeight, parsed.world().properties.worldHeight);
			assertEquals(original.caves().cheeseCaveProbability, parsed.caves().cheeseCaveProbability);
			assertEquals(original.climate().temperature.scale, parsed.climate().temperature.scale);
			assertEquals(original.terrain().general.terrainRegionSize, parsed.terrain().general.terrainRegionSize);
			assertEquals(original.rivers().riverCount, parsed.rivers().riverCount);
			assertEquals(original.flow().flowParticles, parsed.flow().flowParticles);
			assertEquals(original.island().density, parsed.island().density);
			assertEquals(original.erosionFilters().scale, parsed.erosionFilters().scale);
			assertEquals(original.miscellaneous().smoothLayerDecorator, parsed.miscellaneous().smoothLayerDecorator);
		}
	}

	@Test
	public void testPayloadCodecRoundTrip() {
		ActivePresetSnapshot snapshot = new ActivePresetSnapshot(
			"{\"test\": 123}",
			"minecraft:overworld",
			123456789L,
			100,
			200,
			0,
			0,
			"replacements: test"
		);
		ResourceLocation purpose = ResourceLocation.fromNamespaceAndPath("freeterraforged", "test_purpose");
		ActivePresetSnapshotPayload payload = new ActivePresetSnapshotPayload(purpose, snapshot);

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		ActivePresetSnapshotPayload.CODEC.encode(buf, payload);

		ActivePresetSnapshotPayload decoded = ActivePresetSnapshotPayload.CODEC.decode(buf);
		assertEquals(purpose, decoded.purpose());
		assertEquals("{\"test\": 123}", decoded.snapshot().presetJson());
		assertEquals("minecraft:overworld", decoded.snapshot().dimension());
		assertEquals(123456789L, decoded.snapshot().seed());
		assertEquals(100, decoded.snapshot().playerX());
		assertEquals(200, decoded.snapshot().playerZ());
		assertEquals(0, decoded.snapshot().spawnX());
		assertEquals(0, decoded.snapshot().spawnZ());
		assertEquals("replacements: test", decoded.snapshot().biomeReplacements());
		assertEquals("", decoded.snapshot().biomeParameters());
	}

	@Test
	public void testPayloadCodecRoundTripWithBiomeParameters() {
		ActivePresetSnapshot snapshot = new ActivePresetSnapshot(
			"{\"test\": 123}",
			"minecraft:overworld",
			123456789L,
			100,
			200,
			0,
			0,
			"replacements: test",
			"[{\"parameters\":{\"temperature\":0.0,\"humidity\":0.0,\"continentalness\":0.0,\"erosion\":0.0,\"depth\":0.0,\"weirdness\":0.0,\"offset\":0.0},\"biome\":\"stilllife:temperate_rainforest_wetlands\"}]"
		);
		ResourceLocation purpose = ResourceLocation.fromNamespaceAndPath("freeterraforged", "test_biomes");
		ActivePresetSnapshotPayload payload = new ActivePresetSnapshotPayload(purpose, snapshot);

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		ActivePresetSnapshotPayload.CODEC.encode(buf, payload);

		ActivePresetSnapshotPayload decoded = ActivePresetSnapshotPayload.CODEC.decode(buf);
		assertEquals(purpose, decoded.purpose());
		assertEquals(snapshot.biomeParameters(), decoded.snapshot().biomeParameters());
	}

	@Test
	public void testBackwardsCompatiblePayloadDecodingWithoutBiomeParameters() {
		ResourceLocation purpose = ResourceLocation.fromNamespaceAndPath("freeterraforged", "test_legacy");
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeResourceLocation(purpose);
		buf.writeUtf("{\"test\": 123}", 1_048_576);
		buf.writeUtf("minecraft:overworld", 32767);
		buf.writeLong(42L);
		buf.writeInt(10);
		buf.writeInt(20);
		buf.writeInt(0);
		buf.writeInt(0);

		byte[] raw = "legacy replacements".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) {
			gzip.write(raw);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		buf.writeByteArray(bytes.toByteArray());

		ActivePresetSnapshotPayload decoded = ActivePresetSnapshotPayload.CODEC.decode(buf);
		assertEquals("legacy replacements", decoded.snapshot().biomeReplacements());
		assertEquals("", decoded.snapshot().biomeParameters());
	}

	@Test
	public void testPayloadCodecRoundTripWithEmptyReplacements() {
		ActivePresetSnapshot snapshot = new ActivePresetSnapshot(
			"{\"test\": 123}",
			"minecraft:overworld",
			987654321L,
			-50,
			75,
			10,
			20,
			""
		);
		ResourceLocation purpose = ResourceLocation.fromNamespaceAndPath("freeterraforged", "test_empty");
		ActivePresetSnapshotPayload payload = new ActivePresetSnapshotPayload(purpose, snapshot);

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		ActivePresetSnapshotPayload.CODEC.encode(buf, payload);

		ActivePresetSnapshotPayload decoded = ActivePresetSnapshotPayload.CODEC.decode(buf);
		assertEquals("", decoded.snapshot().biomeReplacements());
	}

	@Test
	public void testPayloadLongDimension() {
		String longDimension = "custom_datapack_long_name:very/deeply/nested/custom_dimension_name_test";
		ActivePresetSnapshot snapshot = new ActivePresetSnapshot(
			"{}",
			longDimension,
			0L,
			0,
			0,
			0,
			0,
			""
		);
		ResourceLocation purpose = ResourceLocation.fromNamespaceAndPath("freeterraforged", "test_dim");
		ActivePresetSnapshotPayload payload = new ActivePresetSnapshotPayload(purpose, snapshot);

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		ActivePresetSnapshotPayload.CODEC.encode(buf, payload);

		ActivePresetSnapshotPayload decoded = ActivePresetSnapshotPayload.CODEC.decode(buf);
		assertEquals(longDimension, decoded.snapshot().dimension());
	}

	@Test
	public void testHandlersRegistrationAndDispatch() {
		ResourceLocation purpose = ResourceLocation.fromNamespaceAndPath("freeterraforged", "unit_test_dispatch");
		AtomicReference<ActivePresetSnapshot> received = new AtomicReference<>();

		ActivePresetSnapshotHandlers.register(purpose, received::set);

		// Duplicate registration must throw IllegalStateException
		assertThrows(IllegalStateException.class, () ->
			ActivePresetSnapshotHandlers.register(purpose, received::set)
		);

		ActivePresetSnapshot snapshot = new ActivePresetSnapshot(
			"{\"key\": \"val\"}",
			"minecraft:the_nether",
			42L,
			1,
			2,
			3,
			4,
			"biome: test"
		);

		// Dispatch known purpose
		ActivePresetSnapshotHandlers.handle(new ActivePresetSnapshotPayload(purpose, snapshot));
		assertNotNull(received.get());
		assertEquals("{\"key\": \"val\"}", received.get().presetJson());
		assertEquals("minecraft:the_nether", received.get().dimension());

		// Dispatch unknown purpose (should log warning and not throw)
		ResourceLocation unknownPurpose = ResourceLocation.fromNamespaceAndPath("freeterraforged", "unknown_purpose");
		assertDoesNotThrow(() ->
			ActivePresetSnapshotHandlers.handle(new ActivePresetSnapshotPayload(unknownPurpose, snapshot))
		);
	}
}
