package raccoonman.reterraforged.world.worldgen.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class WeightedRendezvous {
	private WeightedRendezvous() {
	}

	public static WorldgenPlans.ProviderDomain select(
		long salt,
		long cellX,
		long cellZ,
		List<WorldgenPlans.ProviderDomain> providers
	) {
		if (providers.isEmpty()) {
			throw new IllegalArgumentException("Cannot assign a provider from an empty domain list");
		}
		return new Selector(salt, providers).select(cellX, cellZ);
	}

	static double score(long salt, long cellX, long cellZ, WorldgenPlans.ProviderDomain provider) {
		return score(
			coordinateHash(salt, cellX, cellZ),
			provider.id().toString().getBytes(StandardCharsets.UTF_8),
			provider.weight()
		);
	}

	private static long coordinateHash(long salt, long cellX, long cellZ) {
		return mix64(salt ^ mix64(cellX) ^ Long.rotateLeft(mix64(cellZ), 29));
	}

	private static double score(long coordinateHash, byte[] id, double weight) {
		long hash = coordinateHash;
		for (byte value : id) {
			hash = mix64(hash ^ Byte.toUnsignedLong(value));
		}
		double uniform = ((hash >>> 11) + 1.0D) * 0x1.0p-53;
		return -Math.log(uniform) / weight;
	}

	public static final class Selector {
		private final long salt;
		private final PreparedProvider[] providers;

		public Selector(long salt, List<WorldgenPlans.ProviderDomain> providers) {
			this.salt = salt;
			this.providers = providers.stream()
				.map(Objects::requireNonNull)
				.map(PreparedProvider::new)
				.toArray(PreparedProvider[]::new);
			if (this.providers.length == 0) {
				throw new IllegalArgumentException("Cannot prepare an empty provider set");
			}
		}

		public WorldgenPlans.ProviderDomain select(long cellX, long cellZ) {
			if (this.providers.length == 1) {
				return this.providers[0].provider;
			}
			long coordinateHash = coordinateHash(this.salt, cellX, cellZ);
			PreparedProvider best = null;
			double bestScore = Double.POSITIVE_INFINITY;
			for (PreparedProvider candidate : this.providers) {
				double score = score(coordinateHash, candidate.idBytes, candidate.provider.weight());
				if (best == null || score < bestScore
					|| (Double.compare(score, bestScore) == 0 && candidate.id.compareTo(best.id) < 0)) {
					best = candidate;
					bestScore = score;
				}
			}
			return best.provider;
		}
	}

	private static final class PreparedProvider {
		private final WorldgenPlans.ProviderDomain provider;
		private final String id;
		private final byte[] idBytes;

		private PreparedProvider(WorldgenPlans.ProviderDomain provider) {
			this.provider = provider;
			this.id = provider.id().toString();
			this.idBytes = this.id.getBytes(StandardCharsets.UTF_8);
		}
	}

	private static long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
		value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
		return value ^ (value >>> 31);
	}
}
