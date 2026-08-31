package raccoonman.reterraforged.world.worldgen.feature.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;

/**
 * Extends vanilla's canonical bottom-to-max-terrain placement semantics into
 * the additional vertical volume provided by an RTF preset.
 *
 * <p>The vanilla reference interval is 321 blocks ({@code -64..256}). One
 * candidate is retained in that interval. Extended vertical space is divided
 * into equally sized bands, each receiving one candidate. A partial band
 * receives a candidate with probability proportional to its size. This keeps
 * the expected candidate density constant instead of diluting a fixed count
 * over the entire enlarged dimension.</p>
 */
public final class DynamicHeightRangePlacement {
	public static final int REFERENCE_MIN_Y = -64;
	public static final int REFERENCE_MAX_Y = 256;
	public static final int REFERENCE_SPAN = REFERENCE_MAX_Y - REFERENCE_MIN_Y + 1;

	private DynamicHeightRangePlacement() {
	}

	/**
	 * Returns replacement positions only when the current modifier is the exact
	 * canonical {@code uniform(bottom, absolute(256))} range in an extended RTF
	 * Overworld. Returning empty leaves vanilla and custom placement behavior
	 * untouched.
	 */
	public static Optional<Stream<BlockPos>> getPositions(
		HeightRangePlacement placement,
		PlacementContext context,
		RandomSource random,
		BlockPos origin
	) {
		if (!isCanonicalRange(placement) || !isRtfOverworld(context)) {
			return Optional.empty();
		}
		Optional<ResourceLocation> featureId = getTopLevelFeatureId(placement, context);
		if (featureId.isEmpty()) {
			return Optional.empty();
		}

		int minY = context.getMinGenY();
		int maxY = minY + context.getGenDepth() - 1;
		if (minY >= REFERENCE_MIN_Y && maxY <= REFERENCE_MAX_Y) {
			return Optional.empty();
		}
		if (minY > REFERENCE_MIN_Y || maxY < REFERENCE_MAX_Y) {
			return Optional.empty();
		}

		List<HeightBand> bands = createBands(minY, maxY);
		List<BlockPos> positions = new ArrayList<>(bands.size());
		int baseY = Mth.randomBetweenInclusive(random, REFERENCE_MIN_Y, REFERENCE_MAX_Y);
		positions.add(origin.atY(baseY));

		BandRandom extensionRandom = new BandRandom(extensionSeed(context, featureId.get(), origin, baseY));
		for (int i = 1; i < bands.size(); i++) {
			HeightBand band = bands.get(i);
			if (band.guaranteed() || extensionRandom.nextInt(REFERENCE_SPAN) < band.size()) {
				int y = band.minInclusive() + extensionRandom.nextInt(band.size());
				positions.add(origin.atY(y));
			}
		}
		return Optional.of(positions.stream());
	}

	public static boolean isCanonicalRange(HeightRangePlacement placement) {
		JsonElement encoded = HeightRangePlacement.CODEC.codec()
			.encodeStart(JsonOps.INSTANCE, placement)
			.result()
			.orElse(null);
		if (encoded == null || !encoded.isJsonObject()) {
			return false;
		}
		JsonElement heightElement = encoded.getAsJsonObject().get("height");
		if (heightElement == null || !heightElement.isJsonObject()) {
			return false;
		}
		JsonObject height = heightElement.getAsJsonObject();
		return "minecraft:uniform".equals(string(height, "type"))
			&& anchorEquals(height.get("min_inclusive"), "above_bottom", 0)
			&& anchorEquals(height.get("max_inclusive"), "absolute", REFERENCE_MAX_Y);
	}

	private static String string(JsonObject object, String member) {
		JsonElement value = object.get(member);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static boolean anchorEquals(JsonElement value, String member, int expected) {
		if (value == null || !value.isJsonObject()) {
			return false;
		}
		JsonObject anchor = value.getAsJsonObject();
		return anchor.size() == 1
			&& anchor.has(member)
			&& anchor.get(member).isJsonPrimitive()
			&& anchor.get(member).getAsInt() == expected;
	}

	static List<HeightBand> createBands(int minY, int maxY) {
		List<HeightBand> bands = new ArrayList<>();
		bands.add(bandContaining(minY, maxY, REFERENCE_MIN_Y));

		int deepY = REFERENCE_MIN_Y - 1;
		while (deepY >= minY) {
			HeightBand band = bandContaining(minY, maxY, deepY);
			bands.add(band);
			deepY = band.minInclusive() - 1;
		}

		int highY = REFERENCE_MAX_Y + 1;
		while (highY <= maxY) {
			HeightBand band = bandContaining(minY, maxY, highY);
			bands.add(band);
			highY = band.maxInclusive() + 1;
		}

		return List.copyOf(bands);
	}

	static HeightBand bandContaining(int minY, int maxY, int y) {
		if (minY > maxY) {
			throw new IllegalArgumentException("Minimum generation Y exceeds maximum generation Y");
		}
		if (y < minY || y > maxY) {
			throw new IllegalArgumentException("Y is outside the generation bounds");
		}
		if (y >= REFERENCE_MIN_Y && y <= REFERENCE_MAX_Y) {
			return new HeightBand(
				Math.max(minY, REFERENCE_MIN_Y),
				Math.min(maxY, REFERENCE_MAX_Y),
				true
			);
		}
		if (y < REFERENCE_MIN_Y) {
			int index = (REFERENCE_MIN_Y - 1 - y) / REFERENCE_SPAN;
			int bandMax = REFERENCE_MIN_Y - 1 - index * REFERENCE_SPAN;
			int minInclusive = Math.max(minY, bandMax - REFERENCE_SPAN + 1);
			int maxInclusive = Math.min(maxY, bandMax);
			return new HeightBand(
				minInclusive,
				maxInclusive,
				maxInclusive - minInclusive + 1 == REFERENCE_SPAN
			);
		}

		int index = (y - REFERENCE_MAX_Y - 1) / REFERENCE_SPAN;
		int bandMin = REFERENCE_MAX_Y + 1 + index * REFERENCE_SPAN;
		int minInclusive = Math.max(minY, bandMin);
		int maxInclusive = Math.min(maxY, bandMin + REFERENCE_SPAN - 1);
		return new HeightBand(
			minInclusive,
			maxInclusive,
			maxInclusive - minInclusive + 1 == REFERENCE_SPAN
		);
	}

	static boolean isRtfOverworld(PlacementContext context) {
		if (!Level.OVERWORLD.equals(context.getLevel().getLevel().dimension())) {
			return false;
		}
		return context.getLevel()
			.registryAccess()
			.lookup(RTFRegistries.PRESET)
			.flatMap(registry -> registry.get(Preset.KEY))
			.isPresent();
	}

	private static Optional<ResourceLocation> getTopLevelFeatureId(HeightRangePlacement placement, PlacementContext context) {
		Optional<PlacedFeature> feature = context.topFeature()
			.filter(topFeature -> topFeature.placement().stream().anyMatch(modifier -> modifier == placement));
		if (feature.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(
			context.getLevel()
				.registryAccess()
				.registryOrThrow(Registries.PLACED_FEATURE)
				.getKey(feature.get())
		);
	}

	private static long extensionSeed(PlacementContext context, ResourceLocation featureId, BlockPos origin, int baseY) {
		long seed = context.getLevel().getSeed();
		seed ^= Mth.getSeed(origin.getX(), baseY, origin.getZ());
		seed ^= (long)featureId.getNamespace().hashCode() << 32;
		seed ^= Integer.toUnsignedLong(featureId.getPath().hashCode());
		return mix64(seed);
	}

	private static long mix64(long value) {
		value = (value ^ value >>> 30) * -4658895280553007687L;
		value = (value ^ value >>> 27) * -7723592293110705685L;
		return value ^ value >>> 31;
	}

	record HeightBand(int minInclusive, int maxInclusive, boolean guaranteed) {

		int size() {
			return this.maxInclusive - this.minInclusive + 1;
		}
	}

	private static final class BandRandom {
		private long state;

		private BandRandom(long seed) {
			this.state = seed;
		}

		private int nextInt(int bound) {
			this.state += -7046029254386353131L;
			return (int)Long.remainderUnsigned(mix64(this.state), bound);
		}
	}
}
