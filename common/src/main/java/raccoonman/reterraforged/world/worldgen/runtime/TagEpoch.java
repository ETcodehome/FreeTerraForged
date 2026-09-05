package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;

public record TagEpoch(long sequence, String fingerprint) {
	public TagEpoch {
		if (sequence < 0L) {
			throw new IllegalArgumentException("Tag epoch sequence must be non-negative");
		}
		fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
	}

	public TagEpoch next(String fingerprint) {
		return new TagEpoch(Math.addExact(this.sequence, 1L), fingerprint);
	}
}
