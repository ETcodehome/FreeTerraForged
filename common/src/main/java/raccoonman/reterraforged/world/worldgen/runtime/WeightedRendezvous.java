package raccoonman.reterraforged.world.worldgen.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Query-order-independent weighted rendezvous assignment for stable FTF cell coordinates. */
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
		return providers.stream()
			.map(Objects::requireNonNull)
			.min(Comparator
				.comparingDouble((WorldgenPlans.ProviderDomain provider) -> score(salt, cellX, cellZ, provider))
				.thenComparing(provider -> provider.id().toString()))
			.orElseThrow();
	}

	static double score(long salt, long cellX, long cellZ, WorldgenPlans.ProviderDomain provider) {
		long hash = mix64(salt ^ mix64(cellX) ^ Long.rotateLeft(mix64(cellZ), 29));
		for (byte value : provider.id().toString().getBytes(StandardCharsets.UTF_8)) {
			hash = mix64(hash ^ Byte.toUnsignedLong(value));
		}
		// The numerator is in [1, 2^53], so u is in (0, 1] and log(0) is impossible.
		double uniform = ((hash >>> 11) + 1.0D) * 0x1.0p-53;
		return -Math.log(uniform) / provider.weight();
	}

	private static long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
		value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
		return value ^ (value >>> 31);
	}
}
