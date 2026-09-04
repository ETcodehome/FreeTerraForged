package raccoonman.reterraforged.world.worldgen.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

public final class CellRendezvous {
	private CellRendezvous() {
	}

	public static <T> T select(long salt, long cellX, long cellZ, List<Choice<T>> choices) {
		return new Selector<T>(salt, choices).select(cellX, cellZ);
	}

	private static double score(long coordinateHash, byte[] id, double weight) {
		long hash = coordinateHash;
		for (byte value : id) {
			hash = mix64(hash ^ Byte.toUnsignedLong(value));
		}
		double uniform = ((hash >>> 11) + 1.0D) * 0x1.0p-53;
		return -Math.log(uniform) / weight;
	}

	public static final class Selector<T> {
		private final long salt;
		private final PreparedChoice<T>[] choices;

		@SuppressWarnings("unchecked")
		public Selector(long salt, List<Choice<T>> choices) {
			this.salt = salt;
			this.choices = choices.stream()
				.map(Objects::requireNonNull)
				.map(PreparedChoice::new)
				.toArray(PreparedChoice[]::new);
			if (this.choices.length == 0) {
				throw new IllegalArgumentException("Cannot prepare an empty weighted choice list");
			}
		}

		public T select(long cellX, long cellZ) {
			if (this.choices.length == 1) {
				return this.choices[0].choice.value();
			}
			long coordinateHash = mix64(
				this.salt ^ mix64(cellX) ^ Long.rotateLeft(mix64(cellZ), 29)
			);
			PreparedChoice<T> best = null;
			double bestScore = Double.POSITIVE_INFINITY;
			for (PreparedChoice<T> candidate : this.choices) {
				double score = score(
					coordinateHash, candidate.idBytes, candidate.choice.weight()
				);
				if (best == null || score < bestScore
					|| (Double.compare(score, bestScore) == 0 && candidate.id.compareTo(best.id) < 0)) {
					best = candidate;
					bestScore = score;
				}
			}
			return best.choice.value();
		}
	}

	private static final class PreparedChoice<T> {
		private final Choice<T> choice;
		private final String id;
		private final byte[] idBytes;

		private PreparedChoice(Choice<T> choice) {
			this.choice = choice;
			this.id = choice.id().toString();
			this.idBytes = this.id.getBytes(StandardCharsets.UTF_8);
		}
	}

	private static long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
		value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
		return value ^ (value >>> 31);
	}

	public record Choice<T>(ResourceLocation id, double weight, T value) {
		public Choice {
			id = Objects.requireNonNull(id, "id");
			if (!Double.isFinite(weight) || weight <= 0.0D) {
				throw new IllegalArgumentException("Choice weight must be finite and positive: " + weight);
			}
			value = Objects.requireNonNull(value, "value");
		}
	}
}
