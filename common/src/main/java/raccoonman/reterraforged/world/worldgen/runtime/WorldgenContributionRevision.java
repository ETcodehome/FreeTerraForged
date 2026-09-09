package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.LevelStem;

public final class WorldgenContributionRevision {
	private WorldgenContributionRevision() {
	}

	public static Snapshot snapshot(ResourceKey<LevelStem> dimension, WorldgenProviderCatalog catalog) {
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(catalog, "catalog");
		return catalog.inAcquisitionSession(() -> snapshotAcquired(dimension, catalog));
	}

	private static Snapshot snapshotAcquired(
		ResourceKey<LevelStem> dimension,
		WorldgenProviderCatalog catalog
	) {
		Map<RevisionKey, Long> values = new LinkedHashMap<>();
		Map<ResourceLocation, CapabilityFailure> failures = new LinkedHashMap<>();
		WorldgenProviderCatalog.Resolution resolution = catalog.resolveContributionRevisions();
		resolution.failures().forEach(failed -> failures.put(failed.metadata().id(), failed.failure()));
		for (WorldgenProviderCatalog.ProviderBinding binding : resolution.providers()) {
			try {
				OptionalLong revision = binding.provider().contributionRevision(dimension);
				if (revision.isEmpty()) {
					failures.put(binding.metadata().id(), CapabilityFailure.unavailable(
						"provider_contribution_revision_missing",
						"Provider " + binding.metadata().id() + " declared a contribution revision but supplied none"
					));
					continue;
				}
				long value = revision.getAsLong();
				if (value < 0L) {
					throw new IllegalArgumentException("Contribution revision must be non-negative");
				}
				values.put(new RevisionKey(binding.metadata().id(), dimension.location()), value);
			} catch (RuntimeException | LinkageError failure) {
				failures.put(binding.metadata().id(), CapabilityFailure.of(
					"provider_contribution_revision_failed", failure
				));
			}
		}
		return new Snapshot(dimension.location(), values, failures);
	}

	public record RevisionKey(ResourceLocation mechanism, ResourceLocation scope) {
		public RevisionKey {
			mechanism = Objects.requireNonNull(mechanism, "mechanism");
			scope = Objects.requireNonNull(scope, "scope");
		}
	}

	public record Snapshot(
		ResourceLocation dimension,
		Map<RevisionKey, Long> revisions,
		Map<ResourceLocation, CapabilityFailure> failures
	) {
		public Snapshot(ResourceLocation dimension, Map<RevisionKey, Long> revisions) {
			this(dimension, revisions, Map.of());
		}

		public Snapshot {
			dimension = Objects.requireNonNull(dimension, "dimension");
				revisions = Map.copyOf(revisions);
			failures = Map.copyOf(failures);
			if (revisions.entrySet().stream().anyMatch(entry -> entry.getKey() == null
				|| entry.getValue() == null || entry.getValue() < 0L)) {
				throw new IllegalArgumentException("Contribution revision vector contains an invalid entry");
			}
		}

		public static Snapshot empty(ResourceLocation dimension) {
			return new Snapshot(dimension, Map.of(), Map.of());
		}

		public java.util.Optional<CapabilityFailure> failure(ResourceLocation provider) {
			return java.util.Optional.ofNullable(this.failures.get(provider));
		}

		public boolean strictlyAdvances(Snapshot previous) {
			this.requireSameDimension(previous);
			boolean advanced = false;
			for (RevisionKey key : union(this.revisions, previous.revisions)) {
				boolean hasNext = this.revisions.containsKey(key);
				boolean hadOld = previous.revisions.containsKey(key);
				if (!hasNext && hadOld) {
					return false;
				}
				if (hasNext && !hadOld) {
					advanced = true;
					continue;
				}
				long next = this.revisions.get(key);
				long old = previous.revisions.get(key);
				if (next < old) {
					return false;
				}
				advanced |= next > old;
			}
			return advanced;
		}

		public boolean regressesFrom(Snapshot previous) {
			this.requireSameDimension(previous);
			return union(this.revisions, previous.revisions).stream().anyMatch(key -> {
				boolean hasNext = this.revisions.containsKey(key);
				boolean hadOld = previous.revisions.containsKey(key);
				return (!hasNext && hadOld)
					|| (hasNext && hadOld && this.revisions.get(key) < previous.revisions.get(key));
			});
		}

		private void requireSameDimension(Snapshot other) {
			if (!this.dimension.equals(other.dimension)) {
				throw new IllegalArgumentException("Cannot compare contribution revisions for different dimensions");
			}
		}

		private static java.util.Set<RevisionKey> union(
			Map<RevisionKey, Long> first,
			Map<RevisionKey, Long> second
		) {
			java.util.Set<RevisionKey> keys = new java.util.HashSet<>(first.keySet());
			keys.addAll(second.keySet());
			return keys;
		}
	}
}
