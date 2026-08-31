package raccoonman.reterraforged.world.worldgen.lithostitched;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.worldgen.lithostitched.api.registry.LithostitchedBuiltInRegistries;
import dev.worldgen.lithostitched.api.registry.LithostitchedRegistries;
import dev.worldgen.lithostitched.api.worldgen.biomeinjector.BiomeInjector;
import dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FastNoiseConfig;
import dev.worldgen.lithostitched.api.worldgen.util.DensityFunctionWrapper;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.AddPoints;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.DispatchAlternateLayout;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.ForcePlacement;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.ReplaceFully;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.ReplacePartially;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.ParameterMap;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.region.Region;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.FastNoiseDensityFunction;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import raccoonman.reterraforged.platform.ModLoaderUtil;
import raccoonman.reterraforged.world.worldgen.runtime.MinecraftBiomeSourceGraphs;
import raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionRevision;

public final class LithostitchedInjectionBridge {
	public static final Set<String> SUPPORTED_VERSIONS = Set.of("1.8.0+beta4", "1.8.0+beta5");
	private static final ResourceLocation NO_REGION = ResourceLocation.fromNamespaceAndPath(
		"reterraforged", "no_region"
	);
	private static final Codec<ClimateAxis> CLIMATE_AXIS_CODEC = Codec.STRING.comapFlatMap(
		ClimateAxis::decode, ClimateAxis::serializedName
	);
	private static final Codec<InclusiveRange<Double>> DOUBLE_RANGE_CODEC = Codec.withAlternative(
		InclusiveRange.codec(Codec.DOUBLE),
		RecordCodecBuilder.create(instance -> instance.group(
			Codec.DOUBLE.fieldOf("min_inclusive").orElse(-Double.MAX_VALUE)
				.forGetter(InclusiveRange::minInclusive),
			Codec.DOUBLE.fieldOf("max_inclusive").orElse(Double.MAX_VALUE)
				.forGetter(InclusiveRange::maxInclusive)
		).apply(instance, InclusiveRange::new))
	);
	private static final Class<?> END_ISLAND_TYPE = DensityFunctions.endIslands(0L).getClass();
	private static final Map<BiomeSource, Snapshot> SNAPSHOTS = new WeakHashMap<>();

	private LithostitchedInjectionBridge() {
	}

	public static void applyAndCapture(
		InjectorBiomeSource source,
		Map<ResourceLocation, BiomeInjector> injectors,
		Optional<DensityFunction> regionFunction,
		Map<ResourceKey<Region>, Region> regions,
		DensityFunctionWrapper noiseHelper,
		RegistryAccess registries,
		long seed
	) {
		String version = ModLoaderUtil.version("lithostitched").orElse("unknown");
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> baseEntries;
		try {
			baseEntries = MinecraftBiomeSourceGraphs.multiNoiseEntries(source.rootDelegate(), registries);
		} catch (RuntimeException failure) {
			baseEntries = List.of();
		}

		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		List<ClonedInjector> clones = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		if (SUPPORTED_VERSIONS.contains(version)) {
			int encounter = 0;
			for (Map.Entry<ResourceLocation, BiomeInjector> entry : injectors.entrySet()) {
				try {
					JsonElement encoded = BiomeInjector.CODEC.encodeStart(ops, entry.getValue())
						.getOrThrow(message -> new IllegalStateException("encode: " + message));
					BiomeInjector clone = BiomeInjector.CODEC.parse(ops, encoded)
						.getOrThrow(message -> new IllegalStateException("decode: " + message));
					ResourceLocation codec = LithostitchedBuiltInRegistries.BIOME_INJECTOR_TYPE
						.getKey(clone.codec());
					if (codec == null) {
						throw new IllegalStateException("unregistered injector codec");
					}
					Optional<ResourceLocation> loadPredicateCodec = clone.predicate().map(predicate -> {
						ResourceLocation id = LithostitchedBuiltInRegistries.LOAD_PREDICATE_TYPE
							.getKey(predicate.codec());
						if (id == null) {
							throw new IllegalStateException("unregistered load-predicate codec");
						}
						return id;
					});
					Optional<Boolean> loadPredicateResult = clone.predicate().map(predicate -> true);
					clones.add(new ClonedInjector(
						entry.getKey(), codec, encounter++, clone.dimension(), loadPredicateCodec,
						loadPredicateResult, clone
					));
				} catch (RuntimeException failure) {
					failures.add(entry.getKey() + ": " + failure.getMessage());
				}
			}
		}

		source.applyInjectors(injectors, regionFunction, regions, noiseHelper);
		List<CapturedInjector> captured = new ArrayList<>();
		for (ClonedInjector clone : clones) {
			try {
				captured.add(normalize(clone, ops, noiseHelper));
			} catch (RuntimeException failure) {
				failures.add(clone.id() + ": " + failure.getMessage());
			}
		}
		List<CapturedRegion> capturedRegions = regions.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(key -> key.location().toString())))
			.map(entry -> new CapturedRegion(
				entry.getKey().location(), entry.getValue().dimension(),
				List.copyOf(entry.getValue().biomes().stream().toList()), entry.getValue().weight()
			))
			.toList();
		Snapshot snapshot = new Snapshot(
			version, seed, source.rootDelegate(), baseEntries, captured, capturedRegions,
			List.copyOf(failures), regionFunction.isPresent()
		);
		synchronized (SNAPSHOTS) {
			SNAPSHOTS.put(source, snapshot);
		}
		WorldgenContributionRevision.advance();
	}

	public static Optional<Snapshot> snapshot(BiomeSource source) {
		synchronized (SNAPSHOTS) {
			return Optional.ofNullable(SNAPSHOTS.get(source));
		}
	}

	public static boolean hasDeclarativeInjectors(
		HolderLookup.Provider lookups,
		ResourceKey<LevelStem> dimension
	) {
		return lookups.lookup(LithostitchedRegistries.BIOME_INJECTOR)
			.stream()
			.flatMap(HolderLookup.RegistryLookup::listElements)
			.anyMatch(holder -> holder.value().dimension().equals(dimension));
	}

	public static Optional<Snapshot> captureDeclarative(
		BiomeSource root,
		HolderLookup.Provider lookups,
		ResourceKey<LevelStem> dimension,
		NoiseGeneratorSettings settings,
		long seed
	) {
		Map<ResourceLocation, BiomeInjector> injectors = new LinkedHashMap<>();
		lookups.lookup(LithostitchedRegistries.BIOME_INJECTOR).stream()
			.flatMap(HolderLookup.RegistryLookup::listElements)
			.filter(holder -> holder.value().dimension().equals(dimension))
			.sorted(Comparator.comparing(holder -> holder.key().location().toString()))
			.forEach(holder -> injectors.put(holder.key().location(), holder.value()));
		if (injectors.isEmpty()) {
			return Optional.empty();
		}

		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookups);
		DensityBinder densityBinder = new DensityBinder(seed, settings, lookups);
		List<CapturedInjector> captured = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		int encounter = 0;
		for (Map.Entry<ResourceLocation, BiomeInjector> entry : injectors.entrySet()) {
			try {
				ClonedInjector clone = clone(entry.getKey(), encounter++, entry.getValue(), ops);
				captured.add(normalize(clone, ops, densityBinder));
			} catch (RuntimeException failure) {
				failures.add(entry.getKey() + ": " + failure.getMessage());
			}
		}

		List<CapturedRegion> regions = lookups.lookup(LithostitchedRegistries.REGION)
			.stream()
			.flatMap(HolderLookup.RegistryLookup::listElements)
			.filter(holder -> holder.value().dimension().equals(dimension))
			.sorted(Comparator.comparing(holder -> holder.key().location().toString()))
			.map(holder -> new CapturedRegion(
				holder.key().location(), holder.value().dimension(),
				List.copyOf(holder.value().biomes().stream().toList()), holder.value().weight()
			))
			.toList();
		ResourceKey<DensityFunction> regionKey = ResourceKey.create(
			Registries.DENSITY_FUNCTION,
			dev.worldgen.lithostitched.Lithostitched.vanillaToLithostitched(dimension.location())
				.withPrefix("region/")
		);
		boolean regionFunctionPresent = lookups.lookup(Registries.DENSITY_FUNCTION)
			.flatMap(registry -> registry.get(regionKey))
			.isPresent();
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> baseEntries;
		try {
			baseEntries = MinecraftBiomeSourceGraphs.multiNoiseEntries(root, lookups);
		} catch (RuntimeException failure) {
			baseEntries = List.of();
		}
		return Optional.of(new Snapshot(
			ModLoaderUtil.version("lithostitched").orElse("unknown"), seed, root, baseEntries,
			captured, regions, failures, regionFunctionPresent
		));
	}

	public static Snapshot rebind(
		Snapshot snapshot,
		BiomeSource root,
		HolderLookup.Provider lookups,
		NoiseGeneratorSettings settings
	) {
		HolderLookup.RegistryLookup<Biome> biomes = lookups.lookupOrThrow(Registries.BIOME);
		DensityFunction.Visitor densityBinder = new DensityBinder(snapshot.seed(), settings, lookups);
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> baseEntries = snapshot.baseEntries().stream()
			.map(entry -> Pair.of(entry.getFirst(), rebind(entry.getSecond(), biomes)))
			.toList();
		List<CapturedInjector> injectors = snapshot.injectors().stream()
			.map(injector -> new CapturedInjector(
				injector.id(), injector.codec(), injector.encounterOrder(), injector.priority(), injector.kind(),
				injector.dimension(), injector.loadPredicateCodec(), injector.loadPredicateResult(),
				injector.targets().stream().map(holder -> rebind(holder, biomes)).toList(),
				injector.output().map(holder -> rebind(holder, biomes)),
				injector.points().stream().map(entry -> Pair.of(
					entry.getFirst(), rebind(entry.getSecond(), biomes)
				)).toList(),
				injector.criteria().map(criteria -> criteria.bind(densityBinder))
			))
			.toList();
		List<CapturedRegion> regions = snapshot.regions().stream()
			.map(region -> new CapturedRegion(
				region.id(), region.dimension(),
				region.biomes().stream().map(holder -> rebind(holder, biomes)).toList(),
				region.weight()
			))
			.toList();
		return new Snapshot(
			snapshot.mechanismVersion(), snapshot.seed(), root, baseEntries, injectors, regions,
			snapshot.cloneFailures(), snapshot.nativeRegionFunctionPresent()
		);
	}

	public static void bind(BiomeSource source, Snapshot snapshot) {
		synchronized (SNAPSHOTS) {
			SNAPSHOTS.put(source, snapshot);
		}
	}

	public static void release(BiomeSource source) {
		synchronized (SNAPSHOTS) {
			SNAPSHOTS.remove(source);
		}
	}

	private static Holder<Biome> rebind(
		Holder<Biome> holder,
		HolderLookup.RegistryLookup<Biome> biomes
	) {
		return biomes.getOrThrow(holder.unwrapKey().orElseThrow(
			() -> new IllegalStateException("Lithostitched snapshot contains a direct biome holder")
		));
	}

	public static boolean isInjectorSource(BiomeSource source) {
		return source.getClass().getName().equals(
			"dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource"
		);
	}

	private static CapturedInjector normalize(
		ClonedInjector value,
		RegistryOps<JsonElement> ops,
		DensityFunction.Visitor densityBinder
	) {
		BiomeInjector injector = value.injector();
		if (injector instanceof AddPoints add) {
			return captured(value, injector, Kind.ADD_POINTS, List.of(), Optional.empty(),
				List.copyOf(add.points().values()), Optional.empty());
		}
		if (injector instanceof ReplaceFully full) {
			return captured(value, injector, Kind.REPLACE_FULLY,
				List.copyOf(full.targets().stream().toList()), Optional.of(full.replacement()),
				List.of(), Optional.empty());
		}
		if (injector instanceof ReplacePartially partial) {
			return captured(value, injector, Kind.REPLACE_PARTIALLY,
				List.copyOf(partial.targets().stream().toList()), Optional.of(partial.replacement()),
				List.of(), Optional.of(normalize(partial.parameters(), ops).bind(densityBinder)));
		}
		if (injector instanceof ForcePlacement force) {
			return captured(value, injector, Kind.FORCE, List.of(), Optional.of(force.biome()), List.of(),
				Optional.of(normalize(force.parameters(), ops).bind(densityBinder)));
		}
		if (injector instanceof DispatchAlternateLayout alternate) {
			return captured(value, injector, Kind.DISPATCH, List.of(), Optional.empty(),
				List.copyOf(alternate.points().values()),
				Optional.of(normalize(alternate.parameters(), ops).bind(densityBinder)));
		}
		return captured(value, injector, Kind.UNKNOWN, List.of(), Optional.empty(), List.of(), Optional.empty());
	}

	private static ClonedInjector clone(
		ResourceLocation id,
		int encounter,
		BiomeInjector injector,
		RegistryOps<JsonElement> ops
	) {
		JsonElement encoded = BiomeInjector.CODEC.encodeStart(ops, injector)
			.getOrThrow(message -> new IllegalStateException("encode: " + message));
		BiomeInjector clone = BiomeInjector.CODEC.parse(ops, encoded)
			.getOrThrow(message -> new IllegalStateException("decode: " + message));
		ResourceLocation codec = LithostitchedBuiltInRegistries.BIOME_INJECTOR_TYPE.getKey(clone.codec());
		if (codec == null) {
			throw new IllegalStateException("unregistered injector codec");
		}
		Optional<ResourceLocation> loadPredicateCodec = clone.predicate().map(predicate -> {
			ResourceLocation predicateCodec = LithostitchedBuiltInRegistries.LOAD_PREDICATE_TYPE
				.getKey(predicate.codec());
			if (predicateCodec == null) {
				throw new IllegalStateException("unregistered load-predicate codec");
			}
			return predicateCodec;
		});
		return new ClonedInjector(
			id, codec, encounter, clone.dimension(), loadPredicateCodec,
			clone.predicate().map(ignored -> true), clone
		);
	}

	private static CapturedInjector captured(
		ClonedInjector value,
		BiomeInjector injector,
		Kind kind,
		List<Holder<Biome>> targets,
		Optional<Holder<Biome>> output,
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> points,
		Optional<ParameterCriteria> criteria
	) {
		return new CapturedInjector(
			value.id(), value.codec(), value.encounter(), injector.priority(), kind, value.dimension(),
			value.loadPredicateCodec(), value.loadPredicateResult(), targets, output, points, criteria
		);
	}

	private static ParameterCriteria normalize(ParameterMap parameters, RegistryOps<JsonElement> ops) {
		JsonElement encoded = ParameterMap.CODEC.codec().encodeStart(ops, parameters)
			.getOrThrow(message -> new IllegalStateException("parameter encode: " + message));
		CriteriaWire wire = CriteriaWire.CODEC.parse(ops, encoded)
			.getOrThrow(message -> new IllegalStateException("parameter decode: " + message));
		List<ClimateCriterion> climate = new ArrayList<>();
		List<DensityCriterion> density = new ArrayList<>();
		for (Map.Entry<Either<ClimateAxis, DensityFunction>, InclusiveRange<Double>> entry
			: wire.parameters().entrySet()) {
			NumericRange range = new NumericRange(
				entry.getValue().minInclusive(), entry.getValue().maxInclusive()
			);
			entry.getKey().ifLeft(axis -> climate.add(new ClimateCriterion(axis, range)));
			entry.getKey().ifRight(function -> density.add(new DensityCriterion(function, function, range)));
		}
		climate.sort(Comparator.comparing(criterion -> criterion.axis().serializedName()));
		return new ParameterCriteria(climate, density, wire.region());
	}

	private record CriteriaWire(
		Map<Either<ClimateAxis, DensityFunction>, InclusiveRange<Double>> parameters,
		Optional<ResourceLocation> region
	) {
		private static final Codec<CriteriaWire> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(
				Codec.either(CLIMATE_AXIS_CODEC, DensityFunction.HOLDER_HELPER_CODEC),
				DOUBLE_RANGE_CODEC
			).fieldOf("parameters").forGetter(CriteriaWire::parameters),
			ResourceLocation.CODEC.optionalFieldOf("region").forGetter(CriteriaWire::region)
		).apply(instance, CriteriaWire::new));
	}

	private record ClonedInjector(
		ResourceLocation id,
		ResourceLocation codec,
		int encounter,
		ResourceKey<LevelStem> dimension,
		Optional<ResourceLocation> loadPredicateCodec,
		Optional<Boolean> loadPredicateResult,
		BiomeInjector injector
	) {
	}

	private static final class DensityBinder implements DensityFunction.Visitor {
		private final Map<DensityFunction, DensityFunction> bound = new ConcurrentHashMap<>();
		private final long seed;
		private final boolean legacy;
		private final RandomState randomState;
		private final PositionalRandomFactory random;

		private DensityBinder(long seed, NoiseGeneratorSettings settings, HolderLookup.Provider lookups) {
			this.seed = seed;
			this.legacy = settings.useLegacyRandomSource();
			this.randomState = RandomState.create(
				settings, lookups.lookupOrThrow(Registries.NOISE), seed
			);
			this.random = settings.getRandomSource().newInstance(seed).forkPositional();
		}

		@Override
		public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
			Holder<NormalNoise.NoiseParameters> parameters = holder.noiseData();
			if (this.legacy && parameters.is(Noises.TEMPERATURE)) {
				return new DensityFunction.NoiseHolder(
					parameters,
					NormalNoise.createLegacyNetherBiome(
						this.legacyRandom(0L), new NormalNoise.NoiseParameters(-7, 1.0D, 1.0D)
					)
				);
			}
			if (this.legacy && parameters.is(Noises.VEGETATION)) {
				return new DensityFunction.NoiseHolder(
					parameters,
					NormalNoise.createLegacyNetherBiome(
						this.legacyRandom(1L), new NormalNoise.NoiseParameters(-7, 1.0D, 1.0D)
					)
				);
			}
			if (this.legacy && parameters.is(Noises.SHIFT)) {
				return new DensityFunction.NoiseHolder(
					parameters,
					NormalNoise.create(
						this.random.fromHashOf(Noises.SHIFT.location()),
						new NormalNoise.NoiseParameters(0, 0.0D)
					)
				);
			}
			return new DensityFunction.NoiseHolder(
				parameters,
				this.randomState.getOrCreateNoise(parameters.unwrapKey().orElseThrow())
			);
		}

		@Override
		public DensityFunction apply(DensityFunction function) {
			return this.bound.computeIfAbsent(function, this::bind);
		}

		private DensityFunction bind(DensityFunction function) {
			if (function instanceof FastNoiseDensityFunction fastNoise) {
				JsonElement encoded = FastNoiseConfig.CODEC.encodeStart(JsonOps.INSTANCE, fastNoise.config().value())
					.getOrThrow(message -> new IllegalStateException("fast-noise encode: " + message));
				FastNoiseConfig config = FastNoiseConfig.CODEC.parse(JsonOps.INSTANCE, encoded)
					.getOrThrow(message -> new IllegalStateException("fast-noise decode: " + message));
				config.bind(this.seed);
				return new FastNoiseDensityFunction(
					Holder.direct(config), fastNoise.xzScale(), fastNoise.yScale(),
					fastNoise.shiftX(), fastNoise.shiftY(), fastNoise.shiftZ()
				);
			}
			if (function instanceof BlendedNoise blended) {
				RandomSource source = this.legacy
					? this.legacyRandom(0L)
					: this.random.fromHashOf(ResourceLocation.withDefaultNamespace("terrain"));
				return blended.withNewRandom(source);
			}
			if (END_ISLAND_TYPE.isInstance(function)) {
				return DensityFunctions.endIslands(this.seed);
			}
			return function;
		}

		private RandomSource legacyRandom(long noiseSeed) {
			return new LegacyRandomSource(this.seed + noiseSeed);
		}
	}

	public enum Kind {
		ADD_POINTS,
		FORCE,
		DISPATCH,
		REPLACE_PARTIALLY,
		REPLACE_FULLY,
		UNKNOWN
	}

	public enum ClimateAxis {
		CONTINENTALNESS("continentalness"),
		EROSION("erosion"),
		WEIRDNESS("weirdness"),
		HUMIDITY("humidity"),
		TEMPERATURE("temperature"),
		DEPTH("depth");

		private final String serializedName;

		ClimateAxis(String serializedName) {
			this.serializedName = serializedName;
		}

		public String serializedName() {
			return this.serializedName;
		}

		public double value(Climate.TargetPoint target) {
			return switch (this) {
				case CONTINENTALNESS -> target.continentalness();
				case EROSION -> target.erosion();
				case WEIRDNESS -> target.weirdness();
				case HUMIDITY -> target.humidity();
				case TEMPERATURE -> target.temperature();
				case DEPTH -> target.depth();
			} / 10000.0D;
		}

		private static DataResult<ClimateAxis> decode(String name) {
			for (ClimateAxis axis : values()) {
				if (axis.serializedName.equals(name)) {
					return DataResult.success(axis);
				}
			}
			return DataResult.error(() -> "Unknown climate axis: " + name);
		}
	}

	public record NumericRange(double minInclusive, double maxInclusive) {
		public NumericRange {
			if (Double.isNaN(minInclusive) || Double.isNaN(maxInclusive) || minInclusive > maxInclusive) {
				throw new IllegalArgumentException("Invalid inclusive range [" + minInclusive + ", " + maxInclusive + "]");
			}
		}

		public boolean contains(double value) {
			return value >= this.minInclusive && value <= this.maxInclusive;
		}
	}

	public record ClimateCriterion(ClimateAxis axis, NumericRange range) {
		public ClimateCriterion {
			axis = Objects.requireNonNull(axis, "axis");
			range = Objects.requireNonNull(range, "range");
		}
	}

	public record DensityCriterion(
		DensityFunction declaration,
		DensityFunction executable,
		NumericRange range
	) {
		public DensityCriterion {
			declaration = Objects.requireNonNull(declaration, "declaration");
			executable = Objects.requireNonNull(executable, "executable");
			range = Objects.requireNonNull(range, "range");
		}

		private DensityCriterion bind(DensityFunction.Visitor visitor) {
			return new DensityCriterion(this.declaration, this.declaration.mapAll(visitor), this.range);
		}
	}

	public record ParameterCriteria(
		List<ClimateCriterion> climate,
		List<DensityCriterion> density,
		Optional<ResourceLocation> region
	) {
		public ParameterCriteria {
			climate = List.copyOf(climate);
			density = List.copyOf(density);
			region = Objects.requireNonNull(region, "region");
		}

		private ParameterCriteria bind(DensityFunction.Visitor visitor) {
			return new ParameterCriteria(
				this.climate,
				this.density.stream().map(criterion -> criterion.bind(visitor)).toList(),
				this.region
			);
		}

		public boolean matches(
			int blockX,
			int blockY,
			int blockZ,
			Climate.TargetPoint target,
			ResourceLocation currentRegion
		) {
			if (this.region.filter(value -> !value.equals(currentRegion)).isPresent()) {
				return false;
			}
			for (ClimateCriterion criterion : this.climate) {
				if (!criterion.range().contains(criterion.axis().value(target))) {
					return false;
				}
			}
			DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(
				blockX, blockY, blockZ
			);
			Map<DensityFunction, Double> values = new HashMap<>();
			for (DensityCriterion criterion : this.density) {
				double value = values.computeIfAbsent(
					criterion.executable(), function -> function.compute(context)
				);
				if (!criterion.range().contains(value)) {
					return false;
				}
			}
			return true;
		}
	}

	public record CapturedInjector(
		ResourceLocation id,
		ResourceLocation codec,
		int encounterOrder,
		int priority,
		Kind kind,
		ResourceKey<LevelStem> dimension,
		Optional<ResourceLocation> loadPredicateCodec,
		Optional<Boolean> loadPredicateResult,
		List<Holder<Biome>> targets,
		Optional<Holder<Biome>> output,
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> points,
		Optional<ParameterCriteria> criteria
	) {
		public CapturedInjector {
			id = Objects.requireNonNull(id, "id");
			codec = Objects.requireNonNull(codec, "codec");
			kind = Objects.requireNonNull(kind, "kind");
			dimension = Objects.requireNonNull(dimension, "dimension");
			loadPredicateCodec = Objects.requireNonNull(loadPredicateCodec, "loadPredicateCodec");
			loadPredicateResult = Objects.requireNonNull(loadPredicateResult, "loadPredicateResult");
			targets = List.copyOf(targets);
			output = Objects.requireNonNull(output, "output");
			points = List.copyOf(points);
			criteria = Objects.requireNonNull(criteria, "criteria");
		}
	}

	public record CapturedRegion(
		ResourceLocation id,
		ResourceKey<LevelStem> dimension,
		List<Holder<Biome>> biomes,
		int weight
	) {
		public CapturedRegion {
			id = Objects.requireNonNull(id, "id");
			dimension = Objects.requireNonNull(dimension, "dimension");
			biomes = List.copyOf(biomes);
		}
	}

	public record Snapshot(
		String mechanismVersion,
		long seed,
		BiomeSource root,
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> baseEntries,
		List<CapturedInjector> injectors,
		List<CapturedRegion> regions,
		List<String> cloneFailures,
		boolean nativeRegionFunctionPresent
	) {
		public Snapshot {
			mechanismVersion = Objects.requireNonNull(mechanismVersion, "mechanismVersion");
			root = Objects.requireNonNull(root, "root");
			baseEntries = List.copyOf(baseEntries);
			injectors = List.copyOf(injectors);
			regions = List.copyOf(regions);
			cloneFailures = List.copyOf(cloneFailures);
		}

		public Snapshot withRoot(BiomeSource root) {
			return new Snapshot(
				this.mechanismVersion, this.seed, root, this.baseEntries, this.injectors,
				this.regions, this.cloneFailures, this.nativeRegionFunctionPresent
			);
		}
	}

	public static ResourceLocation noRegion() {
		return NO_REGION;
	}
}
