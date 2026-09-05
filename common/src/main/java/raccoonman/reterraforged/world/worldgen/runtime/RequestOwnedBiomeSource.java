package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.world.level.biome.BiomeSource;

public final class RequestOwnedBiomeSource implements AutoCloseable {
	private static final AutoCloseable NOOP = () -> {
	};
	private final BiomeSource source;
	private final Optional<BiomeSourcePlanInput> planInput;
	private final Optional<BiomeCandidateRoot> candidateRoot;
	private final AutoCloseable lifecycle;
	private final AtomicBoolean closed = new AtomicBoolean();

	public RequestOwnedBiomeSource(BiomeSource source, AutoCloseable lifecycle) {
		this(source, Optional.empty(), Optional.empty(), lifecycle);
	}

	public RequestOwnedBiomeSource(
		BiomeSource source,
		BiomeSourcePlanInput planInput,
		AutoCloseable lifecycle
	) {
		this(source, Optional.of(planInput), Optional.empty(), lifecycle);
	}

	public RequestOwnedBiomeSource(
		BiomeSource source,
		BiomeCandidateRoot candidateRoot,
		AutoCloseable lifecycle
	) {
		this(source, Optional.empty(), Optional.of(candidateRoot), lifecycle);
	}

	private RequestOwnedBiomeSource(
		BiomeSource source,
		Optional<BiomeSourcePlanInput> planInput,
		Optional<BiomeCandidateRoot> candidateRoot,
		AutoCloseable lifecycle
	) {
		this.source = Objects.requireNonNull(source, "source");
		this.planInput = Objects.requireNonNull(planInput, "planInput");
		this.candidateRoot = Objects.requireNonNull(candidateRoot, "candidateRoot");
		if (this.planInput.isPresent() && this.candidateRoot.isPresent()) {
			throw new IllegalArgumentException(
				"A direct custom-source plan and a candidate-table root are mutually exclusive"
			);
		}
		this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
	}

	public static RequestOwnedBiomeSource immutable(BiomeSource source) {
		return new RequestOwnedBiomeSource(source, NOOP);
	}

	public static RequestOwnedBiomeSource immutable(
		BiomeSource source,
		BiomeCandidateRoot candidateRoot
	) {
		return new RequestOwnedBiomeSource(source, candidateRoot, NOOP);
	}

	public BiomeSource source() {
		return this.source;
	}

	public Optional<BiomeSourcePlanInput> planInput() {
		return this.planInput;
	}

	public Optional<BiomeCandidateRoot> candidateRoot() {
		return this.candidateRoot;
	}

	public boolean closed() {
		return this.closed.get();
	}

	@Override
	public void close() throws Exception {
		if (this.closed.compareAndSet(false, true)) {
			this.lifecycle.close();
		}
	}
}
