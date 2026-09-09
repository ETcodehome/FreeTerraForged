package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

public final class CellIntervalSelector<T> {
	private final long salt;
	private final Entry<T>[] entries;
	private final double totalWeight;

	@SuppressWarnings("unchecked")
	public CellIntervalSelector(long salt, List<Choice<T>> choices) {
		this.salt = salt;
		List<Choice<T>> ordered = choices.stream()
			.map(Objects::requireNonNull)
			.sorted(Comparator.comparing(choice -> choice.id().toString()))
			.toList();
		if (ordered.isEmpty()) {
			throw new IllegalArgumentException("Cannot prepare an empty weighted interval set");
		}
		Set<ResourceLocation> ids = new HashSet<>();
		if (ordered.stream().anyMatch(choice -> !ids.add(choice.id()))) {
			throw new IllegalArgumentException("Weighted interval IDs must be unique");
		}
		double total = 0.0D;
		this.entries = new Entry[ordered.size()];
		for (int index = 0; index < ordered.size(); index++) {
			Choice<T> choice = ordered.get(index);
			double start = total;
			total += choice.weight();
			if (!Double.isFinite(total)) {
				throw new IllegalArgumentException("Weighted interval total must be finite");
			}
			this.entries[index] = new Entry<>(choice, start, total);
		}
		this.totalWeight = total;
	}

	public Selection<T> select(long cellX, long cellZ) {
		return this.select(sample(this.salt, cellX, cellZ));
	}

	public Selection<T> select(double sample) {
		if (!Double.isFinite(sample) || sample < 0.0D || sample >= 1.0D) {
			throw new IllegalArgumentException("Cell interval sample must be in [0, 1)");
		}
		double target = sample * this.totalWeight;
		for (int index = 0; index < this.entries.length; index++) {
			Entry<T> entry = this.entries[index];
			if (target <= entry.end || index == this.entries.length - 1) {
				return new Selection<>(
					entry.choice.value(),
					entry.start / this.totalWeight,
					entry.end / this.totalWeight,
					sample
				);
			}
		}
		throw new IllegalStateException("A finite cell sample did not resolve to an interval");
	}

	public static double sample(long salt, long cellX, long cellZ) {
		long hash = mix64(salt ^ mix64(cellX) ^ Long.rotateLeft(mix64(cellZ), 29));
		return (hash >>> 11) * 0x1.0p-53;
	}

	private static long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
		value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
		return value ^ (value >>> 31);
	}

	private record Entry<T>(Choice<T> choice, double start, double end) {
	}

	public record Choice<T>(ResourceLocation id, double weight, T value) {
		public Choice {
			id = Objects.requireNonNull(id, "id");
			if (!Double.isFinite(weight) || weight <= 0.0D) {
				throw new IllegalArgumentException(
					"Interval weight must be finite and positive: " + weight
				);
			}
			value = Objects.requireNonNull(value, "value");
		}
	}

	public record Selection<T>(
		T value,
		double minInclusive,
		double maxInclusive,
		double sample
	) {
		public Selection {
			value = Objects.requireNonNull(value, "value");
			if (!Double.isFinite(minInclusive) || !Double.isFinite(maxInclusive)
				|| !Double.isFinite(sample) || minInclusive < 0.0D
				|| minInclusive > sample || sample > maxInclusive || maxInclusive > 1.0D) {
				throw new IllegalArgumentException("Invalid normalized interval selection");
			}
		}
	}
}
