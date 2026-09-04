package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import net.minecraft.resources.ResourceLocation;

/** Owner-local compilation state. Scratch snapshots are discarded when compilation returns. */
public final class WorldgenCompilationContext {
	private final WorldgenOwner owner;
	private final WorldgenCompilationPurpose purpose;
	private final BooleanSupplier cancelled;
	private final Map<ResourceLocation, Object> snapshots = new HashMap<>();

	public WorldgenCompilationContext(WorldgenOwner owner, WorldgenCompilationPurpose purpose) {
		this(owner, purpose, () -> false);
	}

	public WorldgenCompilationContext(
		WorldgenOwner owner,
		WorldgenCompilationPurpose purpose,
		BooleanSupplier cancelled
	) {
		this.owner = Objects.requireNonNull(owner, "owner");
		this.purpose = Objects.requireNonNull(purpose, "purpose");
		this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
	}

	public WorldgenOwner owner() {
		return this.owner;
	}

	public WorldgenCompilationPurpose purpose() {
		return this.purpose;
	}

	public void checkCancelled() {
		if (this.cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Worldgen plan acquisition was superseded");
		}
	}

	public <T> T snapshot(ResourceLocation provider, Class<T> type, SnapshotFactory<T> factory) throws Exception {
		this.checkCancelled();
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(factory, "factory");
		Object existing = this.snapshots.get(provider);
		if (existing != null) {
			if (!type.isInstance(existing)) {
				throw new IllegalStateException(
					"Capability provider " + provider + " requested incompatible owner-local snapshot types"
				);
			}
			return type.cast(existing);
		}
		T created = Objects.requireNonNull(factory.create(), "Capability snapshot factory returned null");
		this.checkCancelled();
		this.snapshots.put(provider, created);
		return created;
	}

	@FunctionalInterface
	public interface SnapshotFactory<T> {
		T create() throws Exception;
	}
}
