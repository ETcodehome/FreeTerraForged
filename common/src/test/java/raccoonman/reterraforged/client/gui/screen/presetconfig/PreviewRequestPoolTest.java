package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreviewRequestPoolTest {
	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

    @Test
    void pagesShareOneOwnerForTheCompleteSemanticKey() {
        PreviewRequestPool pool = new PreviewRequestPool();
        BiomePreview.CacheKey key = key(1L, "preset-a");
        AtomicInteger creations = new AtomicInteger();

        IPreviewHandler.PreparedContext.Lease first = pool.acquire(key, () -> false, ignored -> owner(key, creations));
        IPreviewHandler.PreparedContext.Lease second = pool.acquire(key, () -> false, ignored -> owner(key, creations));

        assertEquals(1, creations.get());
        assertSame(first.owner(), second.owner());
        first.close();
        second.close();
        pool.close();
    }

    @Test
	void semanticChangeClosesTheReplacedScreenOwner() {
        PreviewRequestPool pool = new PreviewRequestPool();
	        BiomePreview.CacheKey firstKey = key(
				1L, "preset-a", net.minecraft.core.RegistryAccess.EMPTY, 1L
			);
	        BiomePreview.CacheKey secondKey = key(
				2L, "preset-b", net.minecraft.core.RegistryAccess.EMPTY, 2L
			);
        AtomicInteger creations = new AtomicInteger();
        IPreviewHandler.PreparedContext.Lease first = pool.acquire(
                firstKey, () -> false, ignored -> owner(firstKey, creations)
        );
        IPreviewHandler.PreparedContext firstOwner = first.owner();

        IPreviewHandler.PreparedContext.Lease second = pool.acquire(
                secondKey, () -> false, ignored -> owner(secondKey, creations)
        );

        assertEquals(2, creations.get());
        assertThrows(CancellationException.class, firstOwner::acquire);
        first.close();
        second.close();
		pool.close();
	}

	@Test
	void aReplacementRegistrySnapshotInvalidatesOtherwiseIdenticalOwners() {
		net.minecraft.core.RegistryAccess.Frozen firstRegistries = emptyRegistries();
		net.minecraft.core.RegistryAccess.Frozen secondRegistries = emptyRegistries();
		BiomePreview.CacheKey first = key(1L, "preset-a", firstRegistries);
		BiomePreview.CacheKey second = key(1L, "preset-a", secondRegistries);

		assertNotEquals(first, second);
	}

	@Test
	void aReplacementSelectedStemInvalidatesOtherwiseIdenticalOwners() {
		net.minecraft.core.RegistryAccess.Frozen registries = emptyRegistries();
		BiomePreview.CacheKey first = key(
			1L, "preset-a", registries,
			new net.minecraft.world.level.dimension.LevelStem(null, null)
		);
		BiomePreview.CacheKey second = key(
			1L, "preset-a", registries,
			new net.minecraft.world.level.dimension.LevelStem(null, null)
		);

		assertNotEquals(first, second);
	}

	@Test
	void anOlderAsyncRequestCannotReplaceANewerScreenOwner() {
		PreviewRequestPool pool = new PreviewRequestPool();
		BiomePreview.CacheKey older = key(1L, "preset-a", net.minecraft.core.RegistryAccess.EMPTY, 1L);
		BiomePreview.CacheKey newer = key(2L, "preset-b", net.minecraft.core.RegistryAccess.EMPTY, 2L);
		AtomicInteger creations = new AtomicInteger();
		IPreviewHandler.PreparedContext.Lease current = pool.acquire(
			newer, () -> false, ignored -> owner(newer, creations)
		);

		assertThrows(CancellationException.class, () -> pool.acquire(
			older, () -> false, ignored -> owner(older, creations)
		));
		assertEquals(1, creations.get());
		current.close();
		pool.close();
	}

	@Test
	void aNewAcquisitionGenerationCannotCollideWithAnOlderCachedResult() {
		net.minecraft.core.RegistryAccess.Frozen registries = emptyRegistries();
		net.minecraft.world.level.dimension.LevelStem selectedStem =
			new net.minecraft.world.level.dimension.LevelStem(null, null);
		BiomePreview.CacheKey first = key(1L, "preset-a", registries, selectedStem, 1L);
		BiomePreview.CacheKey reacquired = key(1L, "preset-a", registries, selectedStem, 2L);

		assertNotEquals(first, reacquired);
	}

	@Test
	void ownerConstructionDoesNotHoldThePoolLockAgainstGenerationAdvance() throws Exception {
		PreviewRequestPool pool = new PreviewRequestPool();
		BiomePreview.CacheKey first = key(1L, "preset-a", net.minecraft.core.RegistryAccess.EMPTY, 1L);
		BiomePreview.CacheKey second = key(2L, "preset-b", net.minecraft.core.RegistryAccess.EMPTY, 2L);
		CountDownLatch factoryEntered = new CountDownLatch(1);
		CountDownLatch releaseFactory = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> factoryCancellation =
			new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<IPreviewHandler.PreparedContext> createdOwner =
			new java.util.concurrent.atomic.AtomicReference<>();
		CompletableFuture<IPreviewHandler.PreparedContext.Lease> acquisition = CompletableFuture.supplyAsync(
			() -> pool.acquire(first, () -> false, cancelled -> {
				factoryCancellation.set(cancelled);
				factoryEntered.countDown();
				await(releaseFactory);
				IPreviewHandler.PreparedContext owner =
					new IPreviewHandler.PreparedContext(first, null, null, null);
				createdOwner.set(owner);
				return owner;
			})
		);
		assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
		try {
			CompletableFuture.runAsync(() -> pool.advance(second)).get(1, TimeUnit.SECONDS);
			assertTrue(factoryCancellation.get().getAsBoolean());
		} finally {
			releaseFactory.countDown();
		}
		CompletionException cancelled = assertThrows(CompletionException.class, acquisition::join);
		assertTrue(cancelled.getCause() instanceof CancellationException);
		assertThrows(CancellationException.class, createdOwner.get()::acquire);
		IPreviewHandler.PreparedContext.Lease current = pool.acquire(
			second, () -> false, ignored -> new IPreviewHandler.PreparedContext(second, null, null, null)
		);
		current.close();
		pool.close();
	}

	@Test
	void concurrentPagesCoalesceOneOwnerConstructionWithoutBlockingThePool() throws Exception {
		PreviewRequestPool pool = new PreviewRequestPool();
		BiomePreview.CacheKey key = key(1L, "preset-a");
		AtomicInteger creations = new AtomicInteger();
		CountDownLatch factoryEntered = new CountDownLatch(1);
		CountDownLatch releaseFactory = new CountDownLatch(1);
		java.util.function.Function<java.util.function.BooleanSupplier, IPreviewHandler.PreparedContext> factory = ignored -> {
			creations.incrementAndGet();
			factoryEntered.countDown();
			await(releaseFactory);
			return new IPreviewHandler.PreparedContext(key, null, null, null);
		};
		CompletableFuture<IPreviewHandler.PreparedContext.Lease> first = CompletableFuture.supplyAsync(
			() -> pool.acquire(key, () -> false, factory)
		);
		assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
		CompletableFuture<IPreviewHandler.PreparedContext.Lease> second = CompletableFuture.supplyAsync(
			() -> pool.acquire(key, () -> false, factory)
		);
		releaseFactory.countDown();
		IPreviewHandler.PreparedContext.Lease firstLease = first.get(5, TimeUnit.SECONDS);
		IPreviewHandler.PreparedContext.Lease secondLease = second.get(5, TimeUnit.SECONDS);
		assertEquals(1, creations.get());
		assertSame(firstLease.owner(), secondLease.owner());
		firstLease.close();
		secondLease.close();
		pool.close();
	}

	@Test
	void oneCancelledPageDoesNotAbortASharedOwnerNeededByAnotherPage() throws Exception {
		PreviewRequestPool pool = new PreviewRequestPool();
		BiomePreview.CacheKey key = key(1L, "preset-a");
		AtomicBoolean firstCancelled = new AtomicBoolean();
		AtomicBoolean secondCancelled = new AtomicBoolean();
		CountDownLatch factoryEntered = new CountDownLatch(1);
		CountDownLatch secondCancellationObserved = new CountDownLatch(1);
		CountDownLatch releaseFactory = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> sharedCancellation =
			new java.util.concurrent.atomic.AtomicReference<>();

		CompletableFuture<IPreviewHandler.PreparedContext.Lease> first = CompletableFuture.supplyAsync(
			() -> pool.acquire(key, firstCancelled::get, cancelled -> {
				sharedCancellation.set(cancelled);
				factoryEntered.countDown();
				await(releaseFactory);
				return new IPreviewHandler.PreparedContext(key, null, null, null);
			})
		);
		assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
		CompletableFuture<IPreviewHandler.PreparedContext.Lease> second = CompletableFuture.supplyAsync(
			() -> pool.acquire(key, () -> {
				secondCancellationObserved.countDown();
				return secondCancelled.get();
			}, ignored -> {
					throw new AssertionError("shared owner factory ran more than once");
				})
		);
		firstCancelled.set(true);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (sharedCancellation.get().getAsBoolean() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertTrue(secondCancellationObserved.await(1, TimeUnit.SECONDS));
		assertTrue(!sharedCancellation.get().getAsBoolean());
		releaseFactory.countDown();

		CompletionException cancelled = assertThrows(CompletionException.class, first::join);
		assertTrue(cancelled.getCause() instanceof CancellationException);
		IPreviewHandler.PreparedContext.Lease secondLease = second.get(5, TimeUnit.SECONDS);
		secondLease.close();
		pool.close();
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("Timed out waiting for preview-request test coordination");
			}
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Preview-request test was interrupted", failure);
		}
	}

    private static IPreviewHandler.PreparedContext owner(
            BiomePreview.CacheKey key,
            AtomicInteger creations
    ) {
        creations.incrementAndGet();
        return new IPreviewHandler.PreparedContext(key, null, null, null);
    }

	private static BiomePreview.CacheKey key(long seed, String preset) {
		return key(seed, preset, net.minecraft.core.RegistryAccess.EMPTY);
	}

	private static BiomePreview.CacheKey key(
		long seed,
		String preset,
		net.minecraft.core.RegistryAccess.Frozen registries
	) {
		return key(seed, preset, registries, 0L);
	}

	private static BiomePreview.CacheKey key(
		long seed,
		String preset,
		net.minecraft.core.RegistryAccess.Frozen registries,
		long generation
	) {
		return key(
			seed, preset, registries,
			new net.minecraft.world.level.dimension.LevelStem(null, null), generation
		);
	}

	private static BiomePreview.CacheKey key(
		long seed,
		String preset,
		net.minecraft.core.RegistryAccess.Frozen registries,
		net.minecraft.world.level.dimension.LevelStem selectedStem
	) {
		return key(seed, preset, registries, selectedStem, 0L);
	}

	private static BiomePreview.CacheKey key(
		long seed,
		String preset,
		net.minecraft.core.RegistryAccess.Frozen registries,
		net.minecraft.world.level.dimension.LevelStem selectedStem,
		long generation
	) {
		return new BiomePreview.CacheKey(
				seed, preset, net.minecraft.world.level.WorldDataConfiguration.DEFAULT, "graph", "tags",
				raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionRevision.Snapshot.empty(
					net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld")
				),
				registries,
				selectedStem,
				raccoonman.reterraforged.world.worldgen.runtime.WorldgenProviderCatalog.of(
					java.util.List.of()
				),
				generation
		);
	}

	private static net.minecraft.core.RegistryAccess.Frozen emptyRegistries() {
		return new net.minecraft.core.RegistryAccess.Frozen() {
			@Override
			public <E> java.util.Optional<net.minecraft.core.Registry<E>> registry(
				net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<? extends E>> key
			) {
				return java.util.Optional.empty();
			}

			@Override
			public java.util.stream.Stream<net.minecraft.core.RegistryAccess.RegistryEntry<?>> registries() {
				return java.util.stream.Stream.empty();
			}
		};
	}
}
