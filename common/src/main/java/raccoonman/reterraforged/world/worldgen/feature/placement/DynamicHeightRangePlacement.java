package raccoonman.reterraforged.world.worldgen.feature.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.mixin.HeightRangePlacementAccessor;
import raccoonman.reterraforged.mixin.UniformHeightAccessor;
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
		HeightProvider provider = ((HeightRangePlacementAccessor)(Object)placement).reterraforged$getHeightProvider();
		if (!(provider instanceof UniformHeight uniform)) {
			return false;
		}

		UniformHeightAccessor accessor = (UniformHeightAccessor)(Object)uniform;
		VerticalAnchor min = accessor.reterraforged$getMinInclusive();
		VerticalAnchor max = accessor.reterraforged$getMaxInclusive();
		return min instanceof VerticalAnchor.AboveBottom aboveBottom
			&& aboveBottom.offset() == 0
			&& max instanceof VerticalAnchor.Absolute absolute
			&& absolute.y() == REFERENCE_MAX_Y;
	}

	static List<HeightBand> createBands(int minY, int maxY) {
		List<HeightBand> bands = new ArrayList<>();
		bands.add(new HeightBand(REFERENCE_MIN_Y, REFERENCE_MAX_Y, true));

		int deepUpper = REFERENCE_MIN_Y - 1;
		while (deepUpper >= minY) {
			int size = Math.min(REFERENCE_SPAN, deepUpper - minY + 1);
			int deepLower = deepUpper - size + 1;
			bands.add(new HeightBand(deepLower, deepUpper, size == REFERENCE_SPAN));
			deepUpper = deepLower - 1;
		}

		int highLower = REFERENCE_MAX_Y + 1;
		while (highLower <= maxY) {
			int size = Math.min(REFERENCE_SPAN, maxY - highLower + 1);
			int highUpper = highLower + size - 1;
			bands.add(new HeightBand(highLower, highUpper, size == REFERENCE_SPAN));
			highLower = highUpper + 1;
		}

		return List.copyOf(bands);
	}

	private static boolean isRtfOverworld(PlacementContext context) {
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
