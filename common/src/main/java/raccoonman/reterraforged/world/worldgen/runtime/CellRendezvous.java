package raccoonman.reterraforged.world.worldgen.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

public final class CellRendezvous {
	private CellRendezvous() {
	}

	public static <T> T select(long salt, long cellX, long cellZ, List<Choice<T>> choices) {
		if (choices.isEmpty()) {
			throw new IllegalArgumentException("Cannot select from an empty weighted choice list");
		}
		return choices.stream()
			.map(Objects::requireNonNull)
			.min(Comparator
				.comparingDouble((Choice<T> choice) -> score(salt, cellX, cellZ, choice))
				.thenComparing(choice -> choice.id().toString()))
			.orElseThrow()
			.value();
	}

	private static double score(long salt, long cellX, long cellZ, Choice<?> choice) {
		long hash = mix64(salt ^ mix64(cellX) ^ Long.rotateLeft(mix64(cellZ), 29));
		for (byte value : choice.id().toString().getBytes(StandardCharsets.UTF_8)) {
			hash = mix64(hash ^ Byte.toUnsignedLong(value));
		}
		double uniform = ((hash >>> 11) + 1.0D) * 0x1.0p-53;
		return -Math.log(uniform) / choice.weight();
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
