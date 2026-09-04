package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;

public final class WorldgenRuntimeBinding {
	private final AtomicReference<State> state;
	private final AtomicReference<RejectedPublication> rejection = new AtomicReference<>();
	private final WorldgenQueryCaches queryCaches = new WorldgenQueryCaches();

	private WorldgenRuntimeBinding(State state) {
		this.state = new AtomicReference<>(Objects.requireNonNull(state, "state"));
	}

	static WorldgenRuntimeBinding create(
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings
	) {
		return new WorldgenRuntimeBinding(materializeState(epoch, plan, generationSettings));
	}

	public WorldgenEpoch epoch() {
		return this.current().epoch();
	}

	public WorldgenPlan plan() {
		return this.current().plan();
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
		State replacement = materializeState(epoch, plan, generationSettings);
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
			attempted.contributionRevision(), failure
		);
	}

	void reject(
		UUID owner,
		long resourceRevision,
		String resourceLayerFingerprint,
		TagEpoch tags,
		WorldgenContributionRevision.Snapshot contributions,
		Throwable failure
	) {
		this.rejection.set(new RejectedPublication(
			owner, resourceRevision, resourceLayerFingerprint, tags, contributions,
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
		WorldgenBiomeSelection.Executable biomeSelection,
		BiomeDecorationPlan biomeDecorationPlan
	) {
		State {
			epoch = Objects.requireNonNull(epoch, "epoch");
			plan = Objects.requireNonNull(plan, "plan");
			generationSettings = Map.copyOf(generationSettings);
			possibleBiomes = Set.copyOf(possibleBiomes);
			biomeSelection = Objects.requireNonNull(biomeSelection, "biomeSelection");
			biomeDecorationPlan = Objects.requireNonNull(biomeDecorationPlan, "biomeDecorationPlan");
			if (!epoch.id().equals(plan.owner().id())) {
				throw new IllegalArgumentException("Worldgen plan is owned by a different epoch");
			}
			if (biomeSelection.plan() != plan) {
				throw new IllegalArgumentException("Biome query executor belongs to a different plan");
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

	private static State materializeState(
		WorldgenEpoch epoch,
		WorldgenPlan plan,
		Map<ResourceKey<Biome>, BiomeGenerationSettings> generationSettings
	) {
		Set<Holder<Biome>> possibleBiomes = WorldgenBiomeSelection.possibleBiomes(plan);
		return new State(
			epoch,
			plan,
			generationSettings,
			possibleBiomes,
			WorldgenBiomeSelection.prepare(plan, possibleBiomes),
			new BiomeDecorationPlan(
				plan.structures().structures(), plan.placedFeatures().steps(), generationSettings,
				possibleBiomes
			)
		);
	}

	public record RejectedPublication(
		UUID owner,
		long resourceRevision,
		String resourceLayerFingerprint,
		TagEpoch tags,
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
			contributions = Objects.requireNonNull(contributions, "contributions");
			failure = Objects.requireNonNull(failure, "failure");
		}
	}
}
