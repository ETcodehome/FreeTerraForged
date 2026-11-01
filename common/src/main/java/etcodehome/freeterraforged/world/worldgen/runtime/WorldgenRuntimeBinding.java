package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.registries.FTFRegistries;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;

public final class WorldgenRuntimeBinding {
	private final AtomicReference<State> state;
	private final AtomicReference<RejectedPublication> rejection = new AtomicReference<>();
	private final WorldgenQueryCaches queryCaches = new WorldgenQueryCaches();

	private WorldgenRuntimeBinding() {
		this.state = new AtomicReference<>();
	}

	static WorldgenRuntimeBinding create(
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings
	) {
		return create(epoch, plan, generationSettings, null);
	}

	static WorldgenRuntimeBinding create(
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings,
		@org.jetbrains.annotations.Nullable etcodehome.freeterraforged.world.worldgen.biome.ClimateQuerySemantics fallbackSemantics
	) {
		WorldgenRuntimeBinding binding = new WorldgenRuntimeBinding();
		binding.state.set(materializeState(epoch, plan, generationSettings, fallbackSemantics, binding));
		return binding;
	}

	public WorldgenEpoch epoch() {
		return this.current().epoch();
	}

	public WorldgenPlan plan() {
		return this.current().plan();
	}

	public WorldgenBiomeSelection.Executable biomeSelection() {
		return this.current().biomeSelection();
	}

	public BiomeRuntime biomeRuntime() {
		State current = this.current();
		return new BiomeRuntime(
			current.plan(), current.biomeSelection(), current.biomePlacement()
		);
	}

	public WorldgenQueryCaches queryCaches() {
		return this.queryCaches;
	}

	State current() {
		State current = this.state.get();
		if (current == null) {
			throw new IllegalStateException("Worldgen runtime binding is closed");
		}
		return current;
	}

	State replace(
		State expected,
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings
	) {
		return this.replace(expected, epoch, plan, generationSettings, null);
	}

	State replace(
		State expected,
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings,
		@org.jetbrains.annotations.Nullable etcodehome.freeterraforged.world.worldgen.biome.ClimateQuerySemantics fallbackSemantics
	) {
		State replacement = materializeState(epoch, plan, generationSettings, fallbackSemantics, this);
		if (!this.state.compareAndSet(expected, replacement)) {
			throw new IllegalStateException("Worldgen runtime changed while a replacement plan was prepared");
		}
		this.queryCaches.clearBiomeSelection();
		this.rejection.set(null);
		return expected;
	}

	void reject(WorldgenEpoch attempted, Throwable failure) {
		this.reject(
			attempted.id(), attempted.resourceRevision(), attempted.resourceLayerFingerprint(), attempted.tagEpoch(),
			attempted.biomeReplacementConfig(), attempted.contributionRevision(), failure
		);
	}

	void reject(
		UUID owner,
		long resourceRevision,
		String resourceLayerFingerprint,
		TagEpoch tags,
		BiomeReplacementConfigSnapshot biomeReplacementConfig,
		WorldgenContributionRevision.Snapshot contributions,
		Throwable failure
	) {
		this.rejection.set(new RejectedPublication(
			owner, resourceRevision, resourceLayerFingerprint, tags, biomeReplacementConfig, contributions,
			CapabilityFailure.of("plan_replacement_rejected", failure)
		));
	}

	public Optional<RejectedPublication> rejection() {
		return Optional.ofNullable(this.rejection.get());
	}

	State close() {
		State current = this.state.getAndSet(null);
		if (current == null) {
			throw new IllegalStateException("Worldgen runtime binding is already closed");
		}
		this.queryCaches.clear();
		return current;
	}

	record State(
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings,
		Set<Holder<Biome>> possibleBiomes,
		BiomePlacementPlan biomePlacement,
		WorldgenBiomeSelection.Executable biomeSelection,
		BiomeDecorationPlan biomeDecorationPlan,
		SurfaceColumnPlan surfaceColumns
	) {
		State {
			epoch = Objects.requireNonNull(epoch, "epoch");
			plan = Objects.requireNonNull(plan, "plan");
			generationSettings = Map.copyOf(generationSettings);
			possibleBiomes = Set.copyOf(possibleBiomes);
			biomePlacement = Objects.requireNonNull(biomePlacement, "biomePlacement");
			biomeSelection = Objects.requireNonNull(biomeSelection, "biomeSelection");
			biomeDecorationPlan = Objects.requireNonNull(biomeDecorationPlan, "biomeDecorationPlan");
			surfaceColumns = Objects.requireNonNull(surfaceColumns, "surfaceColumns");
			if (!epoch.id().equals(plan.owner().id())) {
				throw new IllegalArgumentException("Worldgen plan is owned by a different epoch");
			}
			if (biomeSelection.plan() != plan) {
				throw new IllegalArgumentException("Biome query executor belongs to a different plan");
			}
			if (biomePlacement.plan() != plan) {
				throw new IllegalArgumentException("Biome placement metadata belongs to a different plan");
			}
			for (Holder<Biome> biome : possibleBiomes) {
				if (biome.unwrapKey().isPresent() && !generationSettings.containsKey(biome.unwrapKey().orElseThrow())) {
					throw new IllegalArgumentException(
						"Executable biome selection has no compiled generation settings for " +
						biome.unwrapKey().orElseThrow().location()
					);
				}
			}
		}

	}

	public record BiomeRuntime(
		WorldgenPlan plan,
		WorldgenBiomeSelection.Executable selection,
		BiomePlacementPlan placement
	) {
		public BiomeRuntime {
			plan = Objects.requireNonNull(plan, "plan");
			selection = Objects.requireNonNull(selection, "selection");
			placement = Objects.requireNonNull(placement, "placement");
			if (selection.plan() != plan || placement.plan() != plan) {
				throw new IllegalArgumentException("Biome runtime components belong to different plans");
			}
		}
	}

	synchronized State refreshPossibleBiomes(
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings,
		@org.jetbrains.annotations.Nullable etcodehome.freeterraforged.world.worldgen.biome.ClimateQuerySemantics fallbackSemantics
	) {
		State current = this.current();
		State updated = materializeState(current.epoch(), current.plan(), generationSettings, fallbackSemantics, this);
		this.state.set(updated);
		return updated;
	}

	private static State materializeState(
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings,
		@org.jetbrains.annotations.Nullable etcodehome.freeterraforged.world.worldgen.biome.ClimateQuerySemantics fallbackSemantics,
		@org.jetbrains.annotations.Nullable WorldgenRuntimeBinding ownerBinding
	) {
		Set<Holder<Biome>> possibleBiomes = WorldgenBiomeSelection.possibleBiomes(plan);
		SurfaceColumnPlan surfaceColumns = SurfaceColumnPlan.compile(plan.placedFeatures().steps(), generationSettings);
		Preset preset = plan.owner().lookups()
			.lookupOrThrow(FTFRegistries.PRESET)
			.get(Preset.KEY)
			.orElseThrow(() -> new IllegalStateException("Worldgen runtime plan has no selected preset"))
			.value();
		return new State(
			epoch,
			plan,
			generationSettings,
			possibleBiomes,
			BiomePlacementPlan.compile(plan, preset, possibleBiomes),
			WorldgenBiomeSelection.prepare(plan, possibleBiomes, fallbackSemantics, ownerBinding),
			new BiomeDecorationPlan(
				plan.structures().structures(), plan.placedFeatures().steps(), surfaceColumns.decorationSettings(),
				possibleBiomes
			),
			surfaceColumns
		);
	}

	public record RejectedPublication(
		UUID owner,
		long resourceRevision,
		String resourceLayerFingerprint,
		TagEpoch tags,
		BiomeReplacementConfigSnapshot biomeReplacementConfig,
		WorldgenContributionRevision.Snapshot contributions,
		CapabilityFailure failure
	) {
		public RejectedPublication {
			owner = Objects.requireNonNull(owner, "owner");
			if (resourceRevision < 0L) {
				throw new IllegalArgumentException("Resource revision must be non-negative");
			}
			resourceLayerFingerprint = Objects.requireNonNull(
				resourceLayerFingerprint, "resourceLayerFingerprint"
			);
			tags = Objects.requireNonNull(tags, "tags");
			biomeReplacementConfig = Objects.requireNonNull(
				biomeReplacementConfig, "biomeReplacementConfig"
			);
			contributions = Objects.requireNonNull(contributions, "contributions");
			failure = Objects.requireNonNull(failure, "failure");
		}
	}
}
