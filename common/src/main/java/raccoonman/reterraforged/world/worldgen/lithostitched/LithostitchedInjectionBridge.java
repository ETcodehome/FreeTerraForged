package raccoonman.reterraforged.world.worldgen.lithostitched;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.worldgen.lithostitched.api.event.AddBiomeInjectorsEvent;
import dev.worldgen.lithostitched.api.event.AddRegionsEvent;
import dev.worldgen.lithostitched.api.registry.LithostitchedBuiltInRegistries;
import dev.worldgen.lithostitched.api.registry.LithostitchedRegistries;
import dev.worldgen.lithostitched.api.worldgen.biomeinjector.BiomeInjector;
import dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FastNoiseConfig;
import dev.worldgen.lithostitched.api.worldgen.util.DensityFunctionWrapper;
import dev.worldgen.lithostitched.impl.event.LithostitchedEvent;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.BiomeInjectorManager;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.region.Region;
import dev.worldgen.lithostitched.impl.worldgen.densityfunction.FastNoiseDensityFunction;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.platform.ModLoaderUtil;
import raccoonman.reterraforged.mixin.BiomeSourceAccessor;
import raccoonman.reterraforged.world.worldgen.runtime.BiomeCandidateRoot;
import raccoonman.reterraforged.world.worldgen.runtime.MinecraftBiomeSourceGraphs;
import raccoonman.reterraforged.world.worldgen.runtime.PreServerWorldgenContext;

public final class LithostitchedInjectionBridge {
	private static final AtomicLong REVISION = new AtomicLong();
	public static final Set<String> SUPPORTED_VERSIONS = Set.of("1.8.0+beta4", "1.8.0+beta5");
	private static final ResourceLocation NO_REGION = ResourceLocation.fromNamespaceAndPath(
		"reterraforged", "no_region"
	);
	private static final ResourceLocation ADD_POINTS = lithostitched("add_points");
	private static final ResourceLocation DISPATCH_ALTERNATE_LAYOUT = lithostitched("dispatch_alternate_layout");
	private static final ResourceLocation FORCE_PLACEMENT = lithostitched("force_placement");
	private static final ResourceLocation REPLACE_FULLY = lithostitched("replace_fully");
	private static final ResourceLocation REPLACE_PARTIALLY = lithostitched("replace_partially");
	private static final ResourceLocation END_ISLANDS = ResourceLocation.withDefaultNamespace("end_islands");
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
	private static final Map<BiomeSource, Snapshot> SNAPSHOTS = new WeakHashMap<>();
	private static final Map<BiomeSource, String> PRE_SERVER_FAILURES = new WeakHashMap<>();
	private static final Object PRE_SERVER_LOCK = new Object();
	private static final ThreadLocal<PreServerCollector> PRE_SERVER_COLLECTOR = new ThreadLocal<>();
	private static final Map<ChunkGenerator, PreServerResolution> PRE_SERVER_RESOLUTIONS =
		Collections.synchronizedMap(new WeakHashMap<>());
	private static final Set<BiomeSource> PRE_SERVER_SOURCES =
		Collections.newSetFromMap(new WeakHashMap<>());

	private LithostitchedInjectionBridge() {
	}

	private static ResourceLocation lithostitched(String path) {
		return ResourceLocation.fromNamespaceAndPath("lithostitched", path);
	}

	static Optional<ResourceLocation> densityFunctionType(DensityFunction function) {
		Objects.requireNonNull(function, "function");
		if (function instanceof DensityFunctions.HolderHolder) {
			return Optional.empty();
		}
		return Optional.ofNullable(BuiltInRegistries.DENSITY_FUNCTION_TYPE.getKey(
			function.codec().codec()
		));
	}

	public static void finalizePreServer(PreServerWorldgenContext context) {
		String version = ModLoaderUtil.version("lithostitched").orElse("unknown");
		if (!SUPPORTED_VERSIONS.contains(version)) {
			return;
		}
		synchronized (PRE_SERVER_LOCK) {
			if (resolvedEventGraph(context.dimensions(), context.registries(), context.seed()).isPresent()) {
				return;
			}
			Map<ChunkGenerator, BiomeSource> originalSources = context.dimensions().dimensions().values().stream()
				.map(LevelStem::generator)
				.collect(java.util.stream.Collectors.toMap(
					generator -> generator,
					MinecraftBiomeSourceGraphs::acquisitionSource,
					(left, right) -> left,
					LinkedHashMap::new
				));
			try {
				Registry<LevelStem> isolatedDimensions = isolateDimensions(context.dimensions());
				PreServerCollector collector = new PreServerCollector(context.registries());
				PRE_SERVER_COLLECTOR.set(collector);
				try {
					BiomeInjectorManager.applyBiomeInjectors(
						context.registries(), isolatedDimensions, context.seed()
					);
				} finally {
					PRE_SERVER_COLLECTOR.remove();
				}
				ResolvedEventGraph graph = collector.freeze();
				for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry
					: context.dimensions().dimensions().entrySet()) {
					ChunkGenerator originalGenerator = entry.getValue().generator();
					LevelStem clonedStem = isolatedDimensions.getOrThrow(entry.getKey());
					BiomeSource clonedSource = MinecraftBiomeSourceGraphs.acquisitionSource(clonedStem.generator());
					snapshot(clonedSource).ifPresent(value -> {
						BiomeSource originalSource = originalSources.get(originalGenerator);
						bind(originalSource, value);
						PRE_SERVER_SOURCES.add(originalSource);
					});
					PRE_SERVER_RESOLUTIONS.put(
						originalGenerator,
						new PreServerResolution(context.registries(), context.seed(), graph)
					);
				}
				PRE_SERVER_FAILURES.keySet().removeAll(originalSources.values());
			} catch (RuntimeException | LinkageError failure) {
				RTFCommon.LOGGER.warn(
					"Lithostitched pre-server finalization failed for the isolated creation graph",
					failure
				);
				String message = failure.getClass().getSimpleName()
					+ (failure.getMessage() == null ? "" : ": " + failure.getMessage());
				for (Map.Entry<ChunkGenerator, BiomeSource> entry : originalSources.entrySet()) {
					PRE_SERVER_RESOLUTIONS.remove(entry.getKey());
					PRE_SERVER_FAILURES.put(entry.getValue(), message);
					PRE_SERVER_SOURCES.add(entry.getValue());
					release(entry.getValue());
				}
				REVISION.incrementAndGet();
			}
		}
	}

	public static Optional<String> preServerFailure(BiomeSource source) {
		synchronized (PRE_SERVER_LOCK) {
			return Optional.ofNullable(PRE_SERVER_FAILURES.get(source));
		}
	}

	public static void codeEventRegistered(Object event) {
		if (event != AddBiomeInjectorsEvent.EVENT && event != AddRegionsEvent.EVENT) {
			return;
		}
		synchronized (PRE_SERVER_LOCK) {
			PRE_SERVER_RESOLUTIONS.clear();
			synchronized (SNAPSHOTS) {
				PRE_SERVER_SOURCES.forEach(SNAPSHOTS::remove);
			}
			PRE_SERVER_SOURCES.clear();
			PRE_SERVER_FAILURES.clear();
			REVISION.incrementAndGet();
		}
	}

	static Registry<LevelStem> isolateDimensions(WorldDimensions dimensions) {
		MappedRegistry<LevelStem> result = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.experimental());
		for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : dimensions.dimensions().entrySet()) {
			LevelStem stem = entry.getValue();
			ChunkGenerator generator = stem.generator();
			if (generator instanceof NoiseBasedChunkGenerator noiseGenerator) {
				BiomeSource source = MinecraftBiomeSourceGraphs.acquisitionSource(generator);
				BiomeSource isolatedSource = source instanceof InjectorBiomeSource injector
					? injector.rootDelegate()
					: source;
				generator = new NoiseBasedChunkGenerator(
					isolatedSource,
					noiseGenerator.generatorSettings()
				);
			}
			result.register(
				entry.getKey(), new LevelStem(stem.type(), generator),
				new RegistrationInfo(Optional.empty(), Lifecycle.experimental())
			);
		}
		return result.freeze();
	}

	public static AddBiomeInjectorsEvent biomeInjectorEvent(
		LithostitchedEvent<AddBiomeInjectorsEvent> event,
		RegistryAccess registries,
		Registry<LevelStem> dimensions
	) {
		Optional<ResolvedEventGraph> resolved = resolvedEventGraph(dimensions);
		if (resolved.isPresent()) {
			ResolvedEventGraph graph = resolved.orElseThrow();
			return (ownerRegistries, consumer) -> graph.injectors().forEach(value ->
				consumer.accept(value.id(), value.decode(ownerRegistries))
			);
		}
		AddBiomeInjectorsEvent original = event.invoker();
		PreServerCollector collector = PRE_SERVER_COLLECTOR.get();
		if (collector == null) {
			return original;
		}
		return (ownerRegistries, consumer) -> {
			List<BiomeEmission> invocation = new ArrayList<>();
			original.addInjectors(ownerRegistries, (id, injector) -> {
				invocation.add(collector.encode(id, injector));
				consumer.accept(id, injector);
			});
			collector.acceptInjectors(invocation);
		};
	}

	public static AddRegionsEvent regionEvent(
		LithostitchedEvent<AddRegionsEvent> event,
		RegistryAccess registries,
		Registry<LevelStem> dimensions
	) {
		Optional<ResolvedEventGraph> resolved = resolvedEventGraph(dimensions);
		if (resolved.isPresent()) {
			ResolvedEventGraph graph = resolved.orElseThrow();
			return (ownerRegistries, consumer) -> graph.regions().forEach(value ->
				value.replay(ownerRegistries, consumer)
			);
		}
		AddRegionsEvent original = event.invoker();
		PreServerCollector collector = PRE_SERVER_COLLECTOR.get();
		if (collector == null) {
			return original;
		}
		return (ownerRegistries, consumer) -> {
			List<RegionEmission> invocation = new ArrayList<>();
			original.addRegions(ownerRegistries, (key, level, biomes, weight) -> {
				invocation.add(collector.encode(key, level, biomes, weight));
				consumer.accept(key, level, biomes, weight);
			});
			collector.acceptRegions(invocation);
		};
	}

	private static Optional<ResolvedEventGraph> resolvedEventGraph(
		WorldDimensions dimensions,
		RegistryAccess.Frozen registries,
		long seed
	) {
		ResolvedEventGraph found = null;
		for (LevelStem stem : dimensions.dimensions().values()) {
			PreServerResolution resolution = PRE_SERVER_RESOLUTIONS.get(stem.generator());
			if (resolution == null || !resolution.matches(registries, seed)) {
				return Optional.empty();
			}
			ResolvedEventGraph candidate = resolution.graph();
			if (found != null && found != candidate) {
				return Optional.empty();
			}
			found = candidate;
		}
		return Optional.ofNullable(found);
	}

	private static Optional<ResolvedEventGraph> resolvedEventGraph(Registry<LevelStem> dimensions) {
		ResolvedEventGraph found = null;
		for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : dimensions.entrySet()) {
			PreServerResolution resolution = PRE_SERVER_RESOLUTIONS.get(entry.getValue().generator());
			if (resolution == null) {
				return Optional.empty();
			}
			ResolvedEventGraph candidate = resolution.graph();
			if (found != null && found != candidate) {
				return Optional.empty();
			}
			found = candidate;
		}
		return Optional.ofNullable(found);
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
		Optional<BiomeCandidateRoot> baseRoot;
		try {
			baseRoot = Optional.of(MinecraftBiomeSourceGraphs.multiNoiseRoot(
				source.rootDelegate(), registries
			));
		} catch (RuntimeException failure) {
			baseRoot = Optional.empty();
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
						loadPredicateResult, encoded.deepCopy(), clone
					));
				} catch (RuntimeException | LinkageError failure) {
					failures.add(entry.getKey() + ": " + failure.getMessage());
				}
			}
		}

		if (PRE_SERVER_COLLECTOR.get() == null) {
			source.applyInjectors(injectors, regionFunction, regions, noiseHelper);
		}
		List<CapturedInjector> captured = new ArrayList<>();
		for (ClonedInjector clone : clones) {
			try {
				captured.add(normalize(clone, ops, noiseHelper));
			} catch (RuntimeException | LinkageError failure) {
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
			version, seed, baseRoot, captured, capturedRegions,
			List.copyOf(failures), regionFunction.isPresent()
		);
		synchronized (SNAPSHOTS) {
			SNAPSHOTS.put(source, snapshot);
		}
		REVISION.incrementAndGet();
	}

	public static long revision() {
		return REVISION.get();
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
		List<Holder.Reference<BiomeInjector>> declarations = lookups
			.lookup(LithostitchedRegistries.BIOME_INJECTOR)
			.stream()
			.flatMap(HolderLookup.RegistryLookup::listElements)
			.filter(holder -> holder.value().dimension().equals(dimension))
			.sorted(Comparator.comparing(holder -> holder.key().location().toString()))
			.toList();
		if (declarations.isEmpty()) {
			return Optional.empty();
		}

		Map<ResourceLocation, BiomeInjector> injectors = new LinkedHashMap<>();
		List<String> failures = new ArrayList<>();
		for (Holder.Reference<BiomeInjector> declaration : declarations) {
			try {
				BiomeInjector injector = declaration.value();
				if (injector.predicate().map(predicate -> predicate.test()).orElse(true)) {
					injectors.put(declaration.key().location(), injector);
				}
			} catch (RuntimeException | LinkageError failure) {
				failures.add(declaration.key().location() + " load predicate: "
					+ failure.getClass().getName() + ": "
					+ Optional.ofNullable(failure.getMessage()).orElse("<no message>"));
			}
		}

		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, lookups);
		DensityFunction.Visitor densityBinder = new LazyDensityBinder(seed, settings, lookups);
		List<CapturedInjector> captured = new ArrayList<>();
		int encounter = 0;
		for (Map.Entry<ResourceLocation, BiomeInjector> entry : injectors.entrySet()) {
			try {
				ClonedInjector clone = clone(entry.getKey(), encounter++, entry.getValue(), ops);
				captured.add(normalize(clone, ops, densityBinder));
			} catch (RuntimeException | LinkageError failure) {
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
		Optional<BiomeCandidateRoot> baseRoot;
		try {
			baseRoot = Optional.of(MinecraftBiomeSourceGraphs.multiNoiseRoot(root, lookups));
		} catch (RuntimeException failure) {
			baseRoot = Optional.empty();
		}
		return Optional.of(new Snapshot(
			ModLoaderUtil.version("lithostitched").orElse("unknown"), seed, baseRoot,
			captured, regions, failures, regionFunctionPresent
		));
	}

	public static Snapshot rebind(
		Snapshot snapshot,
		HolderLookup.Provider lookups,
		NoiseGeneratorSettings settings
	) {
		return rebind(snapshot, lookups, settings, snapshot.seed());
	}

	public static Snapshot rebind(
		Snapshot snapshot,
		HolderLookup.Provider lookups,
		NoiseGeneratorSettings settings,
		long seed
	) {
		HolderLookup.RegistryLookup<Biome> biomes = lookups.lookupOrThrow(Registries.BIOME);
		boolean bindsDensity = snapshot.injectors().stream()
			.flatMap(injector -> injector.criteria().stream())
			.anyMatch(criteria -> !criteria.density().isEmpty());
		DensityFunction.Visitor densityBinder = bindsDensity
			? new DensityBinder(seed, settings, lookups)
			: null;
		Optional<BiomeCandidateRoot> baseRoot = rebindRoot(snapshot.baseRoot(), biomes);
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
			snapshot.mechanismVersion(), seed, baseRoot, injectors, regions,
			snapshot.cloneFailures(), snapshot.nativeRegionFunctionPresent()
		);
	}

	private static Optional<BiomeCandidateRoot> rebindRoot(
		Optional<BiomeCandidateRoot> candidate,
		HolderLookup.RegistryLookup<Biome> biomes
	) {
		if (candidate.isEmpty()) {
			return Optional.empty();
		}
		BiomeCandidateRoot original = candidate.orElseThrow();
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> reboundEntries = null;
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> originalEntries = original.entries();
		for (int index = 0; index < originalEntries.size(); index++) {
			Pair<Climate.ParameterPoint, Holder<Biome>> entry = originalEntries.get(index);
			Holder<Biome> rebound = rebind(entry.getSecond(), biomes);
			if (reboundEntries == null && rebound != entry.getSecond()) {
				reboundEntries = new ArrayList<>(originalEntries.size());
				reboundEntries.addAll(originalEntries.subList(0, index));
			}
			if (reboundEntries != null) {
				reboundEntries.add(rebound == entry.getSecond()
					? entry
					: Pair.of(entry.getFirst(), rebound));
			}
		}
		return Optional.of(reboundEntries == null
			? original
			: BiomeCandidateRoot.fromEntries(reboundEntries));
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
		return lithostitched("injector").equals(BuiltInRegistries.BIOME_SOURCE.getKey(
			((BiomeSourceAccessor) (Object) source).rtf$codec()
		));
	}

	private static CapturedInjector normalize(
		ClonedInjector value,
		RegistryOps<JsonElement> ops,
		DensityFunction.Visitor densityBinder
	) {
		Kind kind = kindForCodec(value.codec());
		if (kind == Kind.ADD_POINTS) {
			AddPointsWire wire = decode(AddPointsWire.CODEC, value, ops);
			return captured(value, Kind.ADD_POINTS, List.of(), Optional.empty(),
				List.copyOf(wire.points().values()), Optional.empty());
		}
		if (kind == Kind.REPLACE_FULLY) {
			ReplaceFullyWire wire = decode(ReplaceFullyWire.CODEC, value, ops);
			return captured(value, Kind.REPLACE_FULLY,
				List.copyOf(wire.targets().stream().toList()), Optional.of(wire.replacement()),
				List.of(), Optional.empty());
		}
		if (kind == Kind.REPLACE_PARTIALLY) {
			ReplacePartiallyWire wire = decode(ReplacePartiallyWire.CODEC, value, ops);
			return captured(value, Kind.REPLACE_PARTIALLY,
				List.copyOf(wire.targets().stream().toList()), Optional.of(wire.replacement()),
				List.of(), Optional.of(normalize(wire.criteria()).bind(densityBinder)));
		}
		if (kind == Kind.FORCE) {
			ForcePlacementWire wire = decode(ForcePlacementWire.CODEC, value, ops);
			return captured(value, Kind.FORCE, List.of(), Optional.of(wire.biome()), List.of(),
				Optional.of(normalize(wire.criteria()).bind(densityBinder)));
		}
		if (kind == Kind.DISPATCH) {
			DispatchWire wire = decode(DispatchWire.CODEC, value, ops);
			return captured(value, Kind.DISPATCH, List.of(), Optional.empty(),
				List.copyOf(wire.points().values()),
				Optional.of(normalize(wire.criteria()).bind(densityBinder)));
		}
		return captured(value, Kind.UNKNOWN, List.of(), Optional.empty(), List.of(), Optional.empty());
	}

	static Kind kindForCodec(ResourceLocation codec) {
		if (ADD_POINTS.equals(codec)) {
			return Kind.ADD_POINTS;
		}
		if (FORCE_PLACEMENT.equals(codec)) {
			return Kind.FORCE;
		}
		if (DISPATCH_ALTERNATE_LAYOUT.equals(codec)) {
			return Kind.DISPATCH;
		}
		if (REPLACE_PARTIALLY.equals(codec)) {
			return Kind.REPLACE_PARTIALLY;
		}
		if (REPLACE_FULLY.equals(codec)) {
			return Kind.REPLACE_FULLY;
		}
		return Kind.UNKNOWN;
	}

	private static <T> T decode(
		MapCodec<T> codec,
		ClonedInjector value,
		RegistryOps<JsonElement> ops
	) {
		return codec.codec().parse(ops, value.encoded().deepCopy())
			.getOrThrow(message -> new IllegalStateException(
				"decode " + value.codec() + ": " + message
			));
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
			clone.predicate().map(ignored -> true), encoded.deepCopy(), clone
		);
	}

	private static CapturedInjector captured(
		ClonedInjector value,
		Kind kind,
		List<Holder<Biome>> targets,
		Optional<Holder<Biome>> output,
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> points,
		Optional<ParameterCriteria> criteria
	) {
		return new CapturedInjector(
			value.id(), value.codec(), value.encounter(), value.injector().priority(), kind, value.dimension(),
			value.loadPredicateCodec(), value.loadPredicateResult(), targets, output, points, criteria
		);
	}

	private static ParameterCriteria normalize(CriteriaWire wire) {
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
		private static final MapCodec<CriteriaWire> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.unboundedMap(
				Codec.either(CLIMATE_AXIS_CODEC, DensityFunction.HOLDER_HELPER_CODEC),
				DOUBLE_RANGE_CODEC
			).fieldOf("parameters").forGetter(CriteriaWire::parameters),
			ResourceLocation.CODEC.optionalFieldOf("region").forGetter(CriteriaWire::region)
		).apply(instance, CriteriaWire::new));
	}

	private record AddPointsWire(Climate.ParameterList<Holder<Biome>> points) {
		private static final MapCodec<AddPointsWire> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Climate.ParameterList.codec(Biome.CODEC.fieldOf("biome"))
				.fieldOf("points").forGetter(AddPointsWire::points)
		).apply(instance, AddPointsWire::new));
	}

	private record ReplaceFullyWire(HolderSet<Biome> targets, Holder<Biome> replacement) {
		private static final MapCodec<ReplaceFullyWire> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Biome.LIST_CODEC.fieldOf("targets").forGetter(ReplaceFullyWire::targets),
			Biome.CODEC.fieldOf("replacement").forGetter(ReplaceFullyWire::replacement)
		).apply(instance, ReplaceFullyWire::new));
	}

	private record ReplacePartiallyWire(
		HolderSet<Biome> targets,
		Holder<Biome> replacement,
		CriteriaWire criteria
	) {
		private static final MapCodec<ReplacePartiallyWire> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Biome.LIST_CODEC.fieldOf("targets").forGetter(ReplacePartiallyWire::targets),
			Biome.CODEC.fieldOf("replacement").forGetter(ReplacePartiallyWire::replacement),
			CriteriaWire.MAP_CODEC.forGetter(ReplacePartiallyWire::criteria)
		).apply(instance, ReplacePartiallyWire::new));
	}

	private record ForcePlacementWire(Holder<Biome> biome, CriteriaWire criteria) {
		private static final MapCodec<ForcePlacementWire> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Biome.CODEC.fieldOf("biome").forGetter(ForcePlacementWire::biome),
			CriteriaWire.MAP_CODEC.forGetter(ForcePlacementWire::criteria)
		).apply(instance, ForcePlacementWire::new));
	}

	private record DispatchWire(
		CriteriaWire criteria,
		Climate.ParameterList<Holder<Biome>> points
	) {
		private static final MapCodec<DispatchWire> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			CriteriaWire.MAP_CODEC.forGetter(DispatchWire::criteria),
			Climate.ParameterList.codec(Biome.CODEC.fieldOf("biome"))
				.fieldOf("points").forGetter(DispatchWire::points)
		).apply(instance, DispatchWire::new));
	}

	private record ClonedInjector(
		ResourceLocation id,
		ResourceLocation codec,
		int encounter,
		ResourceKey<LevelStem> dimension,
		Optional<ResourceLocation> loadPredicateCodec,
		Optional<Boolean> loadPredicateResult,
		JsonElement encoded,
		BiomeInjector injector
	) {
		private ClonedInjector {
			encoded = Objects.requireNonNull(encoded, "encoded").deepCopy();
		}
	}

	private static final class LazyDensityBinder implements DensityFunction.Visitor {
		private final long seed;
		private final NoiseGeneratorSettings settings;
		private final HolderLookup.Provider lookups;
		private volatile DensityBinder delegate;

		private LazyDensityBinder(
			long seed,
			NoiseGeneratorSettings settings,
			HolderLookup.Provider lookups
		) {
			this.seed = seed;
			this.settings = settings;
			this.lookups = lookups;
		}

		@Override
		public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
			return this.delegate().visitNoise(holder);
		}

		@Override
		public DensityFunction apply(DensityFunction function) {
			return this.delegate().apply(function);
		}

		private DensityBinder delegate() {
			DensityBinder current = this.delegate;
			if (current != null) {
				return current;
			}
			synchronized (this) {
				current = this.delegate;
				if (current == null) {
					current = new DensityBinder(this.seed, this.settings, this.lookups);
					this.delegate = current;
				}
			}
			return current;
		}
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
			if (densityFunctionType(function).filter(END_ISLANDS::equals).isPresent()) {
				return DensityFunctions.endIslands(this.seed);
			}
			return function;
		}

		private RandomSource legacyRandom(long noiseSeed) {
			return new LegacyRandomSource(this.seed + noiseSeed);
		}
	}

	private static final class PreServerCollector {
		private final RegistryOps<JsonElement> ops;
		private final RepeatableOutput<BiomeEmission> injectors = new RepeatableOutput<>(
			"Lithostitched code injector listeners"
		);
		private final RepeatableOutput<RegionEmission> regions = new RepeatableOutput<>(
			"Lithostitched code region listeners"
		);

		private PreServerCollector(RegistryAccess registries) {
			this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		}

		private BiomeEmission encode(ResourceLocation id, BiomeInjector injector) {
			JsonElement encoded = BiomeInjector.CODEC.encodeStart(this.ops, injector)
				.getOrThrow(message -> new IllegalStateException(
					"Failed freezing pre-server Lithostitched injector " + id + ": " + message
				));
			return new BiomeEmission(id, encoded.deepCopy());
		}

		private RegionEmission encode(
			ResourceKey<Region> key,
			ResourceKey<Level> level,
			HolderSet<Biome> biomes,
			int weight
		) {
			return new RegionEmission(
				key, level,
				biomes.stream().map(holder -> holder.unwrapKey().orElseThrow(
					() -> new IllegalStateException(
						"Pre-server Lithostitched region " + key.location() + " contains a direct biome holder"
					)
				)).toList(),
				weight
			);
		}

		private void acceptInjectors(List<BiomeEmission> invocation) {
			this.injectors.accept(invocation);
		}

		private void acceptRegions(List<RegionEmission> invocation) {
			this.regions.accept(invocation);
		}

		private ResolvedEventGraph freeze() {
			return new ResolvedEventGraph(
				this.injectors.freeze(), this.regions.freeze()
			);
		}
	}

	static final class RepeatableOutput<T> {
		private final String label;
		private List<T> output;

		RepeatableOutput(String label) {
			this.label = Objects.requireNonNull(label, "label");
		}

		synchronized void accept(List<T> invocation) {
			List<T> frozen = List.copyOf(invocation);
			if (this.output == null) {
				this.output = frozen;
			} else if (!this.output.equals(frozen)) {
				throw new IllegalStateException(
					this.label + " changed output across one creation-graph finalization"
				);
			}
		}

		synchronized List<T> freeze() {
			return this.output == null ? List.of() : this.output;
		}
	}

	private record BiomeEmission(ResourceLocation id, JsonElement encoded) {
		private BiomeEmission {
			id = Objects.requireNonNull(id, "id");
			encoded = Objects.requireNonNull(encoded, "encoded").deepCopy();
		}

		private BiomeInjector decode(RegistryAccess registries) {
			return BiomeInjector.CODEC.parse(
				RegistryOps.create(JsonOps.INSTANCE, registries), this.encoded.deepCopy()
			).getOrThrow(message -> new IllegalStateException(
				"Failed rebinding pre-server Lithostitched injector " + this.id + ": " + message
			));
		}
	}

	private record RegionEmission(
		ResourceKey<Region> key,
		ResourceKey<Level> level,
		List<ResourceKey<Biome>> biomes,
		int weight
	) {
		private RegionEmission {
			key = Objects.requireNonNull(key, "key");
			level = Objects.requireNonNull(level, "level");
			biomes = List.copyOf(biomes);
		}

		private void replay(RegistryAccess registries, AddRegionsEvent.RegionConsumer consumer) {
			Registry<Biome> biomeRegistry = registries.registryOrThrow(Registries.BIOME);
			consumer.accept(
				this.key, this.level,
				HolderSet.direct(this.biomes.stream().map(biomeRegistry::getHolderOrThrow).toList()),
				this.weight
			);
		}
	}

	private record ResolvedEventGraph(
		List<BiomeEmission> injectors,
		List<RegionEmission> regions
	) {
		private ResolvedEventGraph {
			injectors = List.copyOf(injectors);
			regions = List.copyOf(regions);
		}
	}

	private record PreServerResolution(
		WeakReference<RegistryAccess.Frozen> registries,
		long seed,
		ResolvedEventGraph graph
	) {
		private PreServerResolution(
			RegistryAccess.Frozen registries,
			long seed,
			ResolvedEventGraph graph
		) {
			this(new WeakReference<>(registries), seed, Objects.requireNonNull(graph, "graph"));
		}

		private boolean matches(RegistryAccess.Frozen registries, long seed) {
			return this.registries.get() == registries && this.seed == seed;
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
			if (this.density.isEmpty()) {
				return this;
			}
			Objects.requireNonNull(visitor, "density visitor");
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
			if (this.density.isEmpty()) {
				return true;
			}
			DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(blockX, blockY, blockZ);
			if (this.density.size() == 1) {
				DensityCriterion criterion = this.density.getFirst();
				return criterion.range().contains(criterion.executable().compute(context));
			}
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
		Optional<BiomeCandidateRoot> baseRoot,
		List<CapturedInjector> injectors,
		List<CapturedRegion> regions,
		List<String> cloneFailures,
		boolean nativeRegionFunctionPresent
	) {
		public Snapshot {
			mechanismVersion = Objects.requireNonNull(mechanismVersion, "mechanismVersion");
			baseRoot = Objects.requireNonNull(baseRoot, "baseRoot");
			injectors = List.copyOf(injectors);
			regions = List.copyOf(regions);
			cloneFailures = List.copyOf(cloneFailures);
		}

		public List<Pair<Climate.ParameterPoint, Holder<Biome>>> baseEntries() {
			return baseRoot.map(BiomeCandidateRoot::entries).orElseGet(List::of);
		}
	}

	public static ResourceLocation noRegion() {
		return NO_REGION;
	}
}
