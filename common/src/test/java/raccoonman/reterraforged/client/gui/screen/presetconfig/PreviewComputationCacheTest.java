package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.concurrent.SimpleResource;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

class PreviewComputationCacheTest {
	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

    @Test
    void sidecarResultsAreComputedOnceForAnExactViewKey() {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey revision = revision(0L);
        cache.advance(revision);
        PreviewComputationCache.SidecarKey key = new PreviewComputationCache.SidecarKey(revision, 10, -20, 4, 256);
        AtomicInteger computations = new AtomicInteger();
		BiomePreview.Sidecar expected = sidecar();
		assertSame(expected, cache.sidecar(key, () -> false, cancelled -> {
            computations.incrementAndGet();
			return expected;
        }).join());
		assertSame(expected, cache.sidecar(key, () -> false, cancelled -> {
            computations.incrementAndGet();
			return sidecar();
        }).join());
        assertEquals(1, computations.get());
        cache.close();
    }

    @Test
    void advancingTheAcquisitionGenerationRejectsStaleSidecarWork() {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey older = revision(1L);
        BiomePreview.CacheKey newer = revision(2L);
        PreviewComputationCache.SidecarKey stale =
                new PreviewComputationCache.SidecarKey(older, 10, -20, 4, 256);

        cache.advance(older);
        cache.advance(newer);

        assertThrows(java.util.concurrent.CancellationException.class, () ->
                cache.sidecar(stale, () -> false, cancelled -> null).join()
        );
        cache.close();
    }

	@Test
	void successfulNullSidecarIsRejectedAndNotCached() {
		PreviewComputationCache cache = new PreviewComputationCache();
		BiomePreview.CacheKey revision = revision(12L);
		PreviewComputationCache.SidecarKey key =
			new PreviewComputationCache.SidecarKey(revision, 0, 0, 1, 1);
		cache.advance(revision);

		CompletionException failure = assertThrows(
			CompletionException.class,
			() -> cache.sidecar(key, () -> false, cancelled -> null).join()
		);

		assertTrue(failure.getCause() instanceof IllegalStateException);
		cache.close();
	}

    @Test
    void concurrentWidgetsShareOneCacheOwnedSidecarComputation() throws Exception {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey revision = revision(6L);
        PreviewComputationCache.SidecarKey key =
                new PreviewComputationCache.SidecarKey(revision, 10, -20, 4, 256);
        cache.advance(revision);
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<BiomePreview.Sidecar> first = CompletableFuture.supplyAsync(() ->
                cache.sidecar(key, () -> false, cancelled -> {
                    starts.incrementAndGet();
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("interrupted");
                    }
					return sidecar();
                }).join()
        );
        started.await();
        CompletableFuture<BiomePreview.Sidecar> second = cache.sidecar(key, () -> false, cancelled -> {
            starts.incrementAndGet();
            throw new AssertionError("duplicate sidecar computation");
        });
        release.countDown();

		assertSame(first.join(), second.join());
        assertEquals(1, starts.get());
        cache.close();
    }

    @Test
    void advancingRevisionCancelsTheSharedSidecarOwner() {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey older = revision(7L);
        cache.advance(older);
        PreviewComputationCache.SidecarKey key =
                new PreviewComputationCache.SidecarKey(older, 10, -20, 4, 256);
        AtomicReference<BooleanSupplier> cancelled = new AtomicReference<>();

        cache.sidecar(key, () -> false, token -> {
            cancelled.set(token);
			return sidecar();
        }).join();
        cache.advance(revision(8L));

        assertTrue(cancelled.get().getAsBoolean());
        cache.close();
    }

    @Test
    void concurrentWidgetsShareOneInFlightTileGeneration() {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey revision = revision(3L);
        PreviewComputationCache.TileKey key = new PreviewComputationCache.TileKey(
                revision, 0, 0, 4, 256, true
        );
        cache.advance(revision);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        CompletableFuture<Tile> producer = new CompletableFuture<>();

        CompletableFuture<PreviewComputationCache.TileLease> first = cache.acquireOrGenerate(
                key, () -> false, cancelled -> {
                    starts.incrementAndGet();
                    return producer;
                }
        );
        CompletableFuture<PreviewComputationCache.TileLease> second = cache.acquireOrGenerate(
                key, () -> false, cancelled -> {
                    starts.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError("duplicate generation"));
                }
        );

        assertEquals(1, starts.get());
        producer.complete(tile(releases));
        PreviewComputationCache.TileLease firstLease = first.join();
        PreviewComputationCache.TileLease secondLease = second.join();
        assertTrue(firstLease.tile() == secondLease.tile());
        firstLease.close();
        secondLease.close();
        cache.close();
        assertEquals(2, releases.get());
    }

    @Test
    void advancingRevisionCancelsCacheOwnedInFlightTile() {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey older = revision(4L);
        BiomePreview.CacheKey newer = revision(5L);
        PreviewComputationCache.TileKey key = new PreviewComputationCache.TileKey(
                older, 0, 0, 4, 256, false
        );
        cache.advance(older);
        CompletableFuture<Tile> producer = new CompletableFuture<>();
        CompletableFuture<PreviewComputationCache.TileLease> pending = cache.acquireOrGenerate(
                key, () -> false, cancelled -> producer
        );

        cache.advance(newer);

        assertTrue(producer.isCancelled());
        CompletionException failure = assertThrows(CompletionException.class, pending::join);
        assertTrue(failure.getCause() instanceof CancellationException);
        cache.close();
    }

    @Test
    void sameRevisionTileStopsWhenItsOnlySubscriberIsSuperseded() {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey revision = revision(9L);
        PreviewComputationCache.TileKey key = new PreviewComputationCache.TileKey(
                revision, 0, 0, 4, 256, false
        );
        cache.advance(revision);
        AtomicBoolean requesterCancelled = new AtomicBoolean();
        AtomicReference<BooleanSupplier> producerCancelled = new AtomicReference<>();
        CompletableFuture<Tile> producer = new CompletableFuture<>();

        CompletableFuture<PreviewComputationCache.TileLease> result = cache.acquireOrGenerate(
                key, requesterCancelled::get, cancelled -> {
                    producerCancelled.set(cancelled);
                    return producer;
                }
        );
        assertFalse(producerCancelled.get().getAsBoolean());
        requesterCancelled.set(true);
        assertTrue(producerCancelled.get().getAsBoolean());
        producer.completeExceptionally(new CancellationException("superseded"));

        CompletionException failure = assertThrows(CompletionException.class, result::join);
        assertTrue(failure.getCause() instanceof CancellationException);
        cache.close();
    }

    @Test
    void oneSupersededSubscriberDoesNotCancelAStillSharedTile() {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey revision = revision(10L);
        PreviewComputationCache.TileKey key = new PreviewComputationCache.TileKey(
                revision, 0, 0, 4, 256, true
        );
        cache.advance(revision);
        AtomicBoolean firstCancelled = new AtomicBoolean();
        AtomicBoolean secondCancelled = new AtomicBoolean();
        AtomicReference<BooleanSupplier> producerCancelled = new AtomicReference<>();
        AtomicInteger releases = new AtomicInteger();
        CompletableFuture<Tile> producer = new CompletableFuture<>();

        CompletableFuture<PreviewComputationCache.TileLease> first = cache.acquireOrGenerate(
                key, firstCancelled::get, cancelled -> {
                    producerCancelled.set(cancelled);
                    return producer;
                }
        );
        CompletableFuture<PreviewComputationCache.TileLease> second = cache.acquireOrGenerate(
                key, secondCancelled::get,
                cancelled -> CompletableFuture.failedFuture(new AssertionError("duplicate generation"))
        );
        firstCancelled.set(true);
        assertFalse(producerCancelled.get().getAsBoolean());
        producer.complete(tile(releases));

        CompletionException failure = assertThrows(CompletionException.class, first::join);
        assertTrue(failure.getCause() instanceof CancellationException);
        PreviewComputationCache.TileLease lease = second.join();
        lease.close();
        cache.close();
        assertEquals(2, releases.get());
    }

    @Test
    void tileResultMapRemainsBoundedWhileEvictedTilesStayAliveThroughLeases() {
        PreviewComputationCache cache = new PreviewComputationCache();
        BiomePreview.CacheKey revision = revision(11L);
        cache.advance(revision);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        List<PreviewComputationCache.TileLease> leases = new ArrayList<>();

        for (int x = 0; x < 7; x++) {
            PreviewComputationCache.TileKey key = new PreviewComputationCache.TileKey(
                    revision, x, 0, 4, 256, false
            );
            leases.add(cache.acquireOrGenerate(key, () -> false, cancelled -> {
                starts.incrementAndGet();
                return CompletableFuture.completedFuture(tile(releases));
            }).join());
        }

        PreviewComputationCache.TileLease firstLease = leases.getFirst();
        assertTrue(firstLease.tile() != null);
        assertEquals(0, releases.get(), "leases must retain every generated tile");

        PreviewComputationCache.TileKey firstKey = new PreviewComputationCache.TileKey(
                revision, 0, 0, 4, 256, false
        );
        PreviewComputationCache.TileLease replacement = cache.acquireOrGenerate(
                firstKey, () -> false, cancelled -> {
                    starts.incrementAndGet();
                    return CompletableFuture.completedFuture(tile(releases));
                }
        ).join();
        assertEquals(8, starts.get(), "the leased LRU result must have left the bounded map");
        assertTrue(firstLease.tile() != replacement.tile());

        firstLease.close();
        assertEquals(2, releases.get(), "evicted storage recycles after its final lease closes");
        replacement.close();
        for (int index = 1; index < leases.size(); index++) {
            leases.get(index).close();
        }
        cache.close();
        assertEquals(16, releases.get());
    }

	@Test
	void cacheCloseRetiresEveryTileWhenOneResourceCloseFails() {
		PreviewComputationCache cache = new PreviewComputationCache();
		BiomePreview.CacheKey revision = revision(13L);
		cache.advance(revision);
		AtomicInteger releases = new AtomicInteger();
		for (int x = 0; x < 2; x++) {
			int index = x;
			PreviewComputationCache.TileLease lease = cache.acquireOrGenerate(
				new PreviewComputationCache.TileKey(revision, x, 0, 1, 1, false),
				() -> false,
				cancelled -> CompletableFuture.completedFuture(tile(releases, index == 0))
			).join();
			lease.close();
		}

		assertThrows(IllegalStateException.class, cache::close);
		assertEquals(4, releases.get(), "all backing resources and cache entries must retire");
	}

    private static Tile tile(AtomicInteger releases) {
		return tile(releases, false);
	}

	private static Tile tile(AtomicInteger releases, boolean failCellRelease) {
        Size blocks = Size.make(1, 0);
        Size chunks = Size.make(1, 0);
        return new Tile(
                0, 0, 0, 0, blocks, chunks,
				new SimpleResource<>(new Cell[] { new Cell() }, ignored -> {
					releases.incrementAndGet();
					if (failCellRelease) {
						throw new IllegalStateException("cell release failed");
					}
				}),
                new SimpleResource<>(new Tile.Chunk[1], ignored -> releases.incrementAndGet())
        );
    }

	private static BiomePreview.Sidecar sidecar() {
		return new BiomePreview.Sidecar(
			1, new String[] { "minecraft:plains" }, new short[1], new int[] { 0 }
		);
	}

    private static BiomePreview.CacheKey revision(long generation) {
        return new BiomePreview.CacheKey(
                123L, "preset", net.minecraft.world.level.WorldDataConfiguration.DEFAULT,
                "selected-graph", "tags",
                raccoonman.reterraforged.world.worldgen.runtime.WorldgenContributionRevision.Snapshot.empty(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld")
                ),
                net.minecraft.core.RegistryAccess.EMPTY,
                new net.minecraft.world.level.dimension.LevelStem(null, null),
                raccoonman.reterraforged.world.worldgen.runtime.WorldgenProviderCatalog.of(java.util.List.of()),
                generation
        );
    }
}
