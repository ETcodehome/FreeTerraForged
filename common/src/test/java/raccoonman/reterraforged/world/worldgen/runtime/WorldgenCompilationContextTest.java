package raccoonman.reterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.LevelStem;

class WorldgenCompilationContextTest {
	private static final ResourceLocation PROVIDER = ResourceLocation.fromNamespaceAndPath("test", "provider");

	@Test
	void oneOwnerCompilationSnapshotsProviderExactlyOnce() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		WorldgenCompilationContext context = new WorldgenCompilationContext(
			owner(1), WorldgenCompilationPurpose.WORLDGEN
		);

		Object first = context.snapshot(PROVIDER, Object.class, () -> {
			calls.incrementAndGet();
			return new Object();
		});
		Object second = context.snapshot(PROVIDER, Object.class, () -> {
			calls.incrementAndGet();
			return new Object();
		});

		assertSame(first, second);
		assertEquals(1, calls.get());
	}

	@Test
	void snapshotsNeverCrossOwners() throws Exception {
		WorldgenCompilationContext firstContext = new WorldgenCompilationContext(
			owner(1), WorldgenCompilationPurpose.WORLDGEN
		);
		WorldgenCompilationContext secondContext = new WorldgenCompilationContext(
			owner(2), WorldgenCompilationPurpose.WORLDGEN
		);
		Object first = firstContext.snapshot(PROVIDER, Object.class, Object::new);
		Object second = secondContext.snapshot(PROVIDER, Object.class, Object::new);

		assertNotSame(first, second);
	}

	@Test
	void incompatibleSnapshotTypeFailsClosed() throws Exception {
		WorldgenCompilationContext context = new WorldgenCompilationContext(
			owner(1), WorldgenCompilationPurpose.WORLDGEN
		);
		context.snapshot(PROVIDER, String.class, () -> "snapshot");

		assertThrows(
			IllegalStateException.class,
			() -> context.snapshot(PROVIDER, Integer.class, () -> 3)
		);
	}

	@Test
	void supersededAcquisitionCannotPublishANewSnapshot() {
		AtomicBoolean cancelled = new AtomicBoolean();
		AtomicInteger calls = new AtomicInteger();
		WorldgenCompilationContext context = new WorldgenCompilationContext(
			owner(1), WorldgenCompilationPurpose.BIOME_PREVIEW, cancelled::get
		);
		cancelled.set(true);

		assertThrows(CancellationException.class, () ->
			context.snapshot(PROVIDER, Object.class, () -> {
				calls.incrementAndGet();
				return new Object();
			})
		);
		assertEquals(0, calls.get());
	}

	private static WorldgenOwner owner(int suffix) {
		return new WorldgenOwner() {
			private final UUID id = new UUID(0L, suffix);

			@Override public UUID id() { return this.id; }
			@Override public WorldgenOwnerType type() { return WorldgenOwnerType.WORLDGEN_EPOCH; }
			@Override public long seed() { return suffix; }
			@Override public RegistryAccess.Frozen registries() { return RegistryAccess.EMPTY; }
			@Override public ResourceKey<LevelStem> dimension() { return LevelStem.OVERWORLD; }
			@Override public LevelStem selectedStem() { return null; }
			@Override public String settingsIdentity() { return "settings"; }
			@Override public long resourceRevision() { return 0L; }
			@Override public String resourceLayerFingerprint() { return "resources"; }
			@Override public TagEpoch tagEpoch() { return new TagEpoch(0L, "tags"); }
			@Override public WorldgenContributionRevision.Snapshot contributionRevision() {
				return WorldgenContributionRevision.Snapshot.empty(LevelStem.OVERWORLD.location());
			}
			@Override public net.minecraft.core.HolderLookup.Provider lookups() { return RegistryAccess.EMPTY; }
		};
	}
}
