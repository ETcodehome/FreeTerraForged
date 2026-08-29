package raccoonman.reterraforged.compat.biolith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import com.terraformersmc.biolith.api.biome.BiolithFittestNodes;
import com.terraformersmc.biolith.api.biome.sub.Criterion;
import com.terraformersmc.biolith.impl.biome.BiomeCoordinator;
import com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement;
import com.terraformersmc.biolith.impl.noise.OpenSimplexNoise2;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Mth;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.mixin.biolith.BiolithDimensionBiomePlacementAccessor;
import raccoonman.reterraforged.mixin.biolith.BiolithReplacementRequestAccessor;
import raccoonman.reterraforged.mixin.biolith.BiolithReplacementRequestSetAccessor;
import raccoonman.reterraforged.mixin.biolith.BiolithSubBiomeRequestAccessor;
import raccoonman.reterraforged.mixin.biolith.BiolithSubBiomeRequestSetAccessor;
import raccoonman.reterraforged.world.worldgen.biome.BiomePreviewIntegration;

public final class BiolithPreviewContext {
	private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

	private BiolithPreviewContext() {
	}

	public static Object captureState() {
		return ACTIVE.get();
	}

	public static AutoCloseable attach(Object captured) {
		if (!(captured instanceof State state)) {
			return () -> {
			};
		}
		State previous = ACTIVE.get();
		ACTIVE.set(state);
		return () -> {
			if (previous == null) {
				ACTIVE.remove();
			} else {
				ACTIVE.set(previous);
			}
		};
	}

	public static void preInitializeBiomeLookup(RegistryAccess registries) {
		BiomeCoordinator.setEarlyBiomeLookup(registries.lookupOrThrow(Registries.BIOME));
	}

	public static BiomePreviewIntegration.Session open(
		long seed,
		RegistryAccess registries,
		HolderLookup.Provider provider
	) {
		State previous = ACTIVE.get();
		ACTIVE.set(new State(seed, registries, provider));
		return () -> {
			if (previous == null) {
				ACTIVE.remove();
			} else {
				ACTIVE.set(previous);
			}
		};
	}

	public static boolean isActive() {
		return ACTIVE.get() != null;
	}

	public static OpenSimplexNoise2 replacementNoise(OpenSimplexNoise2 original) {
		State state = ACTIVE.get();
		return state != null ? state.replacementNoise : original;
	}

	public static int[] seedlets(int[] original) {
		State state = ACTIVE.get();
		return state != null ? state.seedlets : original;
	}

	public static Holder<Biome> getReplacement(
		DimensionBiomePlacement placement,
		int x,
		int y,
		int z,
		Climate.TargetPoint target,
		BiolithFittestNodes<Holder<Biome>> nodes
	) {
		return requireState().snapshot(placement).getReplacement(x, y, z, target, nodes);
	}

	public static Holder<Biome> getReplacementEntry(
		DimensionBiomePlacement placement,
		int x,
		int y,
		int z,
		Holder<Biome> biome
	) {
		return requireState().snapshot(placement).getReplacementEntry(x, y, z, biome);
	}

	public static Pair<ResourceKey<Biome>, Holder<Biome>> getReplacementPair(
		DimensionBiomePlacement placement,
		ResourceKey<Biome> biome,
		float noise
	) {
		return requireState().snapshot(placement).getReplacementPair(biome, noise);
	}

	private static State requireState() {
		State state = ACTIVE.get();
		if (state == null) {
			throw new IllegalStateException("No Biolith preview session is active");
		}
		return state;
	}

	private static final class State {
		private final long seed;
		private final OpenSimplexNoise2 replacementNoise;
		private final int[] seedlets;
		private final HolderLookup.RegistryLookup<Biome> biomes;
		private final RegistryOps<JsonElement> registryOps;
		private final Map<DimensionBiomePlacement, Snapshot> snapshots = new IdentityHashMap<>();

		private State(long seed, RegistryAccess registries, HolderLookup.Provider provider) {
			this.seed = seed;
			this.replacementNoise = new OpenSimplexNoise2(seed);
			this.seedlets = new int[8];
			for (int i = 0; i < this.seedlets.length; i++) {
				this.seedlets[i] = (int) ((seed >> (i * 8)) & 255L);
			}
			this.biomes = registries.lookupOrThrow(Registries.BIOME);
			this.registryOps = RegistryOps.create(JsonOps.INSTANCE, provider);
		}

		private Snapshot snapshot(DimensionBiomePlacement placement) {
			return this.snapshots.computeIfAbsent(
				placement,
				key -> new Snapshot(key, this.seed, this.biomes, this.registryOps)
			);
		}
	}

	private static final class Snapshot {
		private final DimensionBiomePlacement placement;
		private final Map<ResourceKey<Biome>, List<Replacement>> replacements;
		private final Map<ResourceKey<Biome>, List<SubRequest>> subBiomes;

		private Snapshot(
			DimensionBiomePlacement placement,
			long seed,
			HolderLookup.RegistryLookup<Biome> biomes,
			RegistryOps<JsonElement> registryOps
		) {
			this.placement = placement;
			this.replacements = snapshotReplacements(placement, seed, biomes);
			this.subBiomes = snapshotSubBiomes(placement, biomes, registryOps);
		}

		private Holder<Biome> getReplacement(
			int x,
			int y,
			int z,
			Climate.TargetPoint target,
			BiolithFittestNodes<Holder<Biome>> nodes
		) {
			Holder<Biome> selected = nodes.ultimate().value;
			ResourceKey<Biome> selectedKey = selected.unwrapKey().orElseThrow();
			double noise = -1.0D;
			InclusiveRange<Float> replacementRange = null;

			List<Replacement> requests = this.replacements.get(selectedKey);
			if (requests != null) {
				noise = this.placement.getLocalNoise(x, y, z);
				Replacement request = select(requests, noise);
				if (request != null) {
					replacementRange = request.range();
					if (!request.biome().equals(DimensionBiomePlacement.VANILLA_PLACEHOLDER)) {
						selected = request.biomeEntry();
						selectedKey = request.biome();
					}
				}
			}

			List<SubRequest> subRequests = this.subBiomes.get(selectedKey);
			if (subRequests != null) {
				if (noise < 0.0D) {
					noise = this.placement.getLocalNoise(x, y, z);
				}
				for (SubRequest request : subRequests) {
					if (request.criterion.matches(nodes, this.placement, target, replacementRange, (float) noise)) {
						return request.biomeEntry;
					}
				}
			}
			return selected;
		}

		private Holder<Biome> getReplacementEntry(int x, int y, int z, Holder<Biome> biome) {
			ResourceKey<Biome> key = biome.unwrapKey().orElseThrow();
			List<Replacement> requests = this.replacements.get(key);
			if (requests == null) {
				return biome;
			}
			Replacement request = select(
				requests,
				this.placement.getLocalNoise(x, y, z)
			);
			return request == null || request.biome().equals(DimensionBiomePlacement.VANILLA_PLACEHOLDER)
				? biome
				: request.biomeEntry();
		}

		private Pair<ResourceKey<Biome>, Holder<Biome>> getReplacementPair(ResourceKey<Biome> biome, float noise) {
			List<Replacement> requests = this.replacements.get(biome);
			Replacement request = requests != null ? select(requests, noise) : null;
			return request != null ? Pair.of(request.biome(), request.biomeEntry()) : null;
		}
	}

	private static Map<ResourceKey<Biome>, List<Replacement>> snapshotReplacements(
		DimensionBiomePlacement placement,
		long seed,
		HolderLookup.RegistryLookup<Biome> biomes
	) {
		Map<ResourceKey<Biome>, List<Replacement>> result = new HashMap<>();
		Random random = new Random(seed);
		Map<ResourceKey<Biome>, Object> source =
			((BiolithDimensionBiomePlacementAccessor) placement).reterraforged$getReplacementRequests();
		for (Map.Entry<ResourceKey<Biome>, Object> entry : source.entrySet()) {
			BiolithReplacementRequestSetAccessor requestSet =
				(BiolithReplacementRequestSetAccessor) entry.getValue();
			if (requestSet.reterraforged$isFinalized()) {
				List<Replacement> finalized = requestSet.reterraforged$getRequests().stream()
					.map(rawRequest -> {
						BiolithReplacementRequestAccessor request = (BiolithReplacementRequestAccessor) rawRequest;
						return new Replacement(
							request.reterraforged$getBiome(),
							request.reterraforged$getRate(),
							request.reterraforged$getBiomeEntry(),
							request.reterraforged$getStart(),
							request.reterraforged$getEnd(),
							request.reterraforged$isFromData()
						);
					})
					.toList();
				result.put(entry.getKey(), finalized);
				continue;
			}
			List<Replacement> requests = new ArrayList<>();
			for (Object rawRequest : requestSet.reterraforged$getRequests()) {
				BiolithReplacementRequestAccessor request = (BiolithReplacementRequestAccessor) rawRequest;
				requests.add(new Replacement(
					request.reterraforged$getBiome(),
					request.reterraforged$getRate(),
					null,
					0.0D,
					0.0D,
					request.reterraforged$isFromData()
				));
			}
			requests.removeIf(request -> request.biome().equals(DimensionBiomePlacement.VANILLA_PLACEHOLDER));
			double maximumRate = requests.stream()
				.mapToDouble(Replacement::rate)
				.max()
				.orElse(0.0D);
			double vanillaRate = Mth.clamp(1.0D - maximumRate, 0.0D, 1.0D);
			double totalRate = vanillaRate + requests.stream()
				.mapToDouble(Replacement::rate)
				.sum();
			if (vanillaRate > 0.0D) {
				requests.add(new Replacement(
					DimensionBiomePlacement.VANILLA_PLACEHOLDER,
					vanillaRate,
					null,
					0.0D,
					0.0D,
					false
				));
			}
			Collections.shuffle(requests, random);
			double start = 0.0D;
			List<Replacement> completed = new ArrayList<>(requests.size());
			for (Replacement request : requests) {
				double end = start + request.rate() / totalRate;
				Holder<Biome> biomeEntry = request.biome().equals(DimensionBiomePlacement.VANILLA_PLACEHOLDER)
					? null
					: biomes.getOrThrow(request.biome());
				completed.add(new Replacement(
					request.biome(), request.rate(), biomeEntry, start, end, request.fromData()
				));
				start = end;
			}
			result.put(entry.getKey(), List.copyOf(completed));
		}
		return Map.copyOf(result);
	}

	private static Map<ResourceKey<Biome>, List<SubRequest>> snapshotSubBiomes(
		DimensionBiomePlacement placement,
		HolderLookup.RegistryLookup<Biome> biomes,
		RegistryOps<JsonElement> registryOps
	) {
		Map<ResourceKey<Biome>, List<SubRequest>> result = new HashMap<>();
		Map<ResourceKey<Biome>, Object> source =
			((BiolithDimensionBiomePlacementAccessor) placement).reterraforged$getSubBiomeRequests();
		for (Map.Entry<ResourceKey<Biome>, Object> entry : source.entrySet()) {
			List<SubRequest> requests = ((BiolithSubBiomeRequestSetAccessor) entry.getValue())
				.reterraforged$getRequests()
				.stream()
				.map(rawRequest -> {
					BiolithSubBiomeRequestAccessor request = (BiolithSubBiomeRequestAccessor) rawRequest;
					ResourceKey<Biome> biome = request.reterraforged$getBiome();
					Criterion criterion = copyCriterion(request.reterraforged$getCriterion(), biomes, registryOps);
					return new SubRequest(
						biome,
						criterion,
						biomes.getOrThrow(biome)
					);
				})
				.sorted(Comparator.comparing(request -> request.biome.location()))
				.toList();
			result.put(entry.getKey(), requests);
		}
		return Map.copyOf(result);
	}

	private static Criterion copyCriterion(
		Criterion criterion,
		HolderLookup.RegistryLookup<Biome> biomes,
		RegistryOps<JsonElement> registryOps
	) {
		JsonElement encoded = Criterion.CODEC.encodeStart(registryOps, criterion)
			.result()
			.orElseThrow(() -> new IllegalStateException("Could not encode Biolith sub-biome criterion"));
		Criterion copy = Criterion.CODEC.parse(registryOps, encoded)
			.result()
			.orElseThrow(() -> new IllegalStateException("Could not decode Biolith sub-biome criterion"));
		copy.complete(biomes);
		return copy;
	}

	private static Replacement select(
		List<Replacement> requests,
		double noise
	) {
		for (Replacement request : requests) {
			if (request.end() >= noise) {
				return request;
			}
		}
		return null;
	}

	private record Replacement(
		ResourceKey<Biome> biome,
		double rate,
		Holder<Biome> biomeEntry,
		double start,
		double end,
		boolean fromData
	) {
		private InclusiveRange<Float> range() {
			return new InclusiveRange<>((float) this.start, this.end > 0.9999D ? 1.0F : (float) this.end);
		}
	}

	private record SubRequest(ResourceKey<Biome> biome, Criterion criterion, Holder<Biome> biomeEntry) {
	}
}
