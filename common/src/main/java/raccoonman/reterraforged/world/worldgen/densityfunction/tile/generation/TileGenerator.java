package raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.Objects;

import raccoonman.reterraforged.concurrent.Resource;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.concurrent.cache.SafeCloseable;
import raccoonman.reterraforged.concurrent.pool.ArrayPool;
import raccoonman.reterraforged.world.worldgen.WorldFilters;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile.Chunk;

public class TileGenerator {
	private static final int MAX_RETAINED_CELL_COUNT = 1 << 18;
	private static final int MAX_RETAINED_ARRAYS = 8;
	private static final int MAX_IN_FLIGHT_CELL_COUNT = 1 << 18;
	private static final int MAX_IN_FLIGHT_TILES = 8;
	private static final int PENDING_MULTIPLIER = 2;
	private Heightmap heightmap;
	private WorldFilters filters;
	private ArrayPool<Cell> cellPool;
	private ArrayPool<Chunk> chunkPool;
	private int tileChunks;
	private int tileBorder;
	private Size tileSizeBlocks;
	private Size tileSizeChunks;
	private int batchSize;
	private int batchCount;
	private final Object lifecycle = new Object();
	private final Semaphore generationPermits;
	private final Semaphore requestPermits;
	private int activeGenerations;
	private boolean closeRequested;
	private boolean resourcesClosed;
	
	public TileGenerator(Heightmap heightmap, WorldFilters filters, int tileChunks, int tileBorder, int batchCount) {
		if (batchCount <= 0) {
			throw new IllegalArgumentException("Tile generation batch count must be positive");
		}
		this.heightmap = heightmap;
		this.filters = filters;
		this.tileChunks = tileChunks;
		this.tileBorder = tileBorder;
		this.tileSizeBlocks = Size.blocks(tileChunks, tileBorder);
		this.tileSizeChunks = Size.chunks(tileChunks, tileBorder);
		int retainedArrays = Math.min(
			MAX_RETAINED_ARRAYS,
			MAX_RETAINED_CELL_COUNT / this.tileSizeBlocks.arraySize()
		);
		this.cellPool = ArrayPool.of(retainedArrays, (length) -> {
			Cell[] cells = new Cell[length];
			for(int i = 0; i < cells.length; i++) {
				cells[i] = new Cell();
			}
			return cells;
		});
		this.chunkPool = ArrayPool.of(retainedArrays, Chunk[]::new);
		this.batchSize = getBatchSize(batchCount, this.tileSizeChunks);
		this.batchCount = batchCount;
		int inFlight = maxInFlightTiles(this.tileSizeBlocks.arraySize());
		this.generationPermits = new Semaphore(inFlight, true);
		this.requestPermits = new Semaphore(maxPendingTiles(this.tileSizeBlocks.arraySize()), true);
	}
	
	public Heightmap getHeightmap() {
		return this.heightmap;
	}

	public int getTileBlockSize() {
		return this.tileSizeBlocks.size();
	}

	public int getTileCellCount() {
		return this.tileSizeBlocks.arraySize();
	}

	public static int maxInFlightTiles(int cellsPerTile) {
		if (cellsPerTile <= 0) {
			throw new IllegalArgumentException("Tile cell count must be positive");
		}
		return Math.max(1, Math.min(
			MAX_IN_FLIGHT_TILES,
			MAX_IN_FLIGHT_CELL_COUNT / cellsPerTile
		));
	}

	public static int maxPendingTiles(int cellsPerTile) {
		return Math.multiplyExact(maxInFlightTiles(cellsPerTile), PENDING_MULTIPLIER);
	}

	public void close() {
		boolean closeResources;
		synchronized (this.lifecycle) {
			if (this.closeRequested) {
				return;
			}
			this.closeRequested = true;
			closeResources = this.activeGenerations == 0 && this.markResourcesClosed();
		}
		if (closeResources) {
			this.closeResources();
		}
	}

	public CompletableFuture<Tile> generate(int tileX, int tileZ) {
		return this.submitGeneration(tileX, tileZ, () -> false, this::generateTile);
	}

	private CompletableFuture<Tile> generateTile(Tile tile) {
		CompletableFuture<?>[] futures = new CompletableFuture<?>[this.batchCount * this.batchCount];
		int submitted = 0;
		try {
			for (int batchZ = 0; batchZ < this.batchCount; batchZ++) {
				for (int batchX = 0; batchX < this.batchCount; batchX++) {
					int chunkX = batchX * this.batchSize;
					int chunkZ = batchZ * this.batchSize;
					CompletableFuture<?> batch = CompletableFuture.runAsync(() -> {
						int maxX = Math.min(this.tileSizeChunks.total(), chunkX + this.batchSize);
						int maxZ = Math.min(this.tileSizeChunks.total(), chunkZ + this.batchSize);
						for (int cZ = chunkZ; cZ < maxZ; cZ++) {
							for (int cX = chunkX; cX < maxX; cX++) {
								Chunk chunk = tile.getChunkWriter(cX, cZ);

								Rivermap rivers = null;
								for (int dz = 0; dz < 16; dz++) {
									for (int dx = 0; dx < 16; dx++) {
										int worldX = chunk.getBlockX() + dx;
										int worldZ = chunk.getBlockZ() + dz;
										Cell cell = chunk.getCell(dx, dz);

										this.heightmap.applyTerrain(cell, worldX, worldZ);
										rivers = Rivermap.get(cell, rivers, this.heightmap);
										this.heightmap.applyRivers(cell, worldX, worldZ, rivers);
										this.heightmap.applyClimate(cell, worldX, worldZ, true);
									}
								}
							}
						}
					}, ThreadPools.WORLD_GEN);
					futures[submitted++] = batch;
				}
			}
			return CompletableFuture.allOf(futures).thenApply((v) -> {
				this.filters.apply(tile, true);
				return tile;
			}).whenComplete((result, throwable) -> this.finishGeneration(tile, throwable));
		} catch (RuntimeException | Error failure) {
			return this.failAfterScheduledWork(tile, futures, submitted, failure);
		}
	}
	
	public CompletableFuture<Tile> generateZoomed(float centerX, float centerZ, float zoom, boolean applyOptionalFilters) {
		return this.generateZoomed(centerX, centerZ, zoom, applyOptionalFilters, () -> false);
	}

	/** Generates a preview tile while allowing superseded UI requests to stop work promptly. */
	public CompletableFuture<Tile> generateZoomed(
		float centerX,
		float centerZ,
		float zoom,
		boolean applyOptionalFilters,
		BooleanSupplier cancelled
	) {
		return this.submitGeneration(0, 0, cancelled, tile ->
			this.generateZoomedTile(tile, centerX, centerZ, zoom, applyOptionalFilters, cancelled)
		);
	}

	private CompletableFuture<Tile> generateZoomedTile(
		Tile tile,
		float centerX,
		float centerZ,
		float zoom,
		boolean applyOptionalFilters,
		BooleanSupplier cancelled
	) {
		CompletableFuture<?>[] futures = new CompletableFuture<?>[this.batchCount * this.batchCount];
		int submitted = 0;
        float translateX = centerX - this.tileSizeBlocks.size() * zoom / 2.0F;
        float translateZ = centerZ - this.tileSizeBlocks.size() * zoom / 2.0F;
		try {
			for (int batchZ = 0; batchZ < this.batchCount; batchZ++) {
				for (int batchX = 0; batchX < this.batchCount; batchX++) {
					int chunkX = batchX * this.batchSize;
					int chunkZ = batchZ * this.batchSize;
					CompletableFuture<?> batch = CompletableFuture.runAsync(() -> {
						checkCancelled(cancelled);
						int maxX = Math.min(this.tileSizeChunks.total(), chunkX + this.batchSize);
						int maxZ = Math.min(this.tileSizeChunks.total(), chunkZ + this.batchSize);
						for (int cZ = chunkZ; cZ < maxZ; cZ++) {
							for (int cX = chunkX; cX < maxX; cX++) {
								checkCancelled(cancelled);
								Chunk chunk = tile.getChunkWriter(cX, cZ);

								Rivermap rivers = null;
								for (int dz = 0; dz < 16; dz++) {
									checkCancelled(cancelled);
									for (int dx = 0; dx < 16; dx++) {
										float worldX = (chunk.getBlockX() + dx) * zoom + translateX;
										float worldZ = (chunk.getBlockZ() + dz) * zoom + translateZ;
										Cell cell = chunk.getCell(dx, dz);

										this.heightmap.applyTerrain(cell, worldX, worldZ);
										rivers = Rivermap.get(cell, rivers, this.heightmap);
										this.heightmap.applyRivers(cell, worldX, worldZ, rivers);
										this.heightmap.applyClimate(cell, worldX, worldZ, true);
									}
								}
							}
						}
					}, ThreadPools.WORLD_GEN);
					futures[submitted++] = batch;
				}
			}
			return CompletableFuture.allOf(futures).thenApply((v) -> {
				checkCancelled(cancelled);
				this.filters.apply(tile, applyOptionalFilters);
				checkCancelled(cancelled);
				return tile;
			}).whenComplete((result, throwable) -> this.finishGeneration(tile, throwable));
		} catch (RuntimeException | Error failure) {
			return this.failAfterScheduledWork(tile, futures, submitted, failure);
		}
	}

	private CompletableFuture<Tile> failAfterScheduledWork(
		Tile tile,
		CompletableFuture<?>[] futures,
		int submitted,
		Throwable failure
	) {
		CompletableFuture<Tile> failed = new CompletableFuture<>();
		CompletableFuture<?>[] active = java.util.Arrays.copyOf(futures, submitted);
		CompletableFuture.allOf(active).whenComplete((ignored, batchFailure) -> {
			try {
				this.finishGeneration(tile, failure);
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			failed.completeExceptionally(failure);
		});
		return failed;
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean()) {
			throw new java.util.concurrent.CancellationException("Preview tile generation superseded");
		}
	}
    
	private CompletableFuture<Tile> submitGeneration(
		int x,
		int z,
		BooleanSupplier cancelled,
		java.util.function.Function<Tile, CompletableFuture<Tile>> work
	) {
		GenerationFuture result = new GenerationFuture();
		RequestPermit requestPermit = null;
		try {
			requestPermit = this.acquireRequestPermit(cancelled);
			result.attachRequestPermit(requestPermit);
			requestPermit = null;
			Future<?> admission = ThreadPools.TILE_ADMISSION.submit(() -> {
				if (!result.beginAdmission()) {
					return;
				}
				if (result.isCancelled()) {
					result.finishAdmission();
					return;
				}
				Tile tile;
				try {
					tile = this.beginGeneration(x, z, cancelled);
				} catch (RuntimeException | Error failure) {
					result.completeExceptionally(failure);
					result.finishAdmission();
					return;
				}
				if (result.isCancelled()) {
					try {
						this.finishGeneration(
							tile, new java.util.concurrent.CancellationException("Tile request was evicted")
						);
					} finally {
						result.finishAdmission();
					}
					return;
				}
				CompletableFuture<Tile> generation;
				try {
					generation = Objects.requireNonNull(
						work.apply(tile), "Tile generation work returned null"
					);
					generation.whenComplete((value, failure) -> {
						try {
							if (failure != null) {
								result.completeExceptionally(failure);
							} else if (value == null) {
								IllegalStateException nullFailure =
									new IllegalStateException("Tile generation completed with null");
								try {
									tile.close();
								} catch (RuntimeException | Error cleanupFailure) {
									nullFailure.addSuppressed(cleanupFailure);
								}
								result.completeExceptionally(nullFailure);
							} else if (value != tile) {
								IllegalStateException identityFailure = new IllegalStateException(
									"Tile generation returned storage other than its admitted tile"
								);
								closeAfterFailure(value, identityFailure);
								closeAfterFailure(tile, identityFailure);
								result.completeExceptionally(identityFailure);
							} else if (!result.complete(value)) {
								try {
									value.close();
								} catch (RuntimeException | Error cleanupFailure) {
									raccoonman.reterraforged.RTFCommon.LOGGER.error(
										"Failed retiring a cancelled tile result", cleanupFailure
									);
								}
							}
						} finally {
							result.finishAdmission();
						}
					});
				} catch (RuntimeException | Error failure) {
					try {
						this.finishGeneration(tile, failure);
					} catch (RuntimeException | Error cleanupFailure) {
						failure.addSuppressed(cleanupFailure);
					} finally {
						result.finishAdmission();
					}
					result.completeExceptionally(failure);
					return;
				}
			});
			result.attachAdmission(admission);
		} catch (RuntimeException | Error failure) {
			if (requestPermit != null) {
				requestPermit.close();
			}
			result.failBeforeAdmission();
			result.completeExceptionally(failure);
		}
		return result;
	}

	private static void closeAfterFailure(Tile tile, Throwable failure) {
		try {
			tile.close();
		} catch (RuntimeException | Error cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	private RequestPermit acquireRequestPermit(BooleanSupplier cancelled) {
		try {
			while (true) {
				checkAdmission(cancelled);
				if (this.requestPermits.tryAcquire(100L, TimeUnit.MILLISECONDS)) {
					return new RequestPermit(this.requestPermits);
				}
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			java.util.concurrent.CancellationException cancellation =
				new java.util.concurrent.CancellationException(
					"Interrupted waiting for tile-request capacity"
				);
			cancellation.initCause(interrupted);
			throw cancellation;
		}
	}

	private Tile beginGeneration(int x, int z, BooleanSupplier cancelled) {
		try {
			while (true) {
				checkAdmission(cancelled);
				if (this.generationPermits.tryAcquire(100L, TimeUnit.MILLISECONDS)) {
					break;
				}
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			java.util.concurrent.CancellationException cancellation =
				new java.util.concurrent.CancellationException("Interrupted waiting for tile-generation capacity");
			cancellation.initCause(interrupted);
			throw cancellation;
		}
		boolean cancelledBeforeOwnership;
		try {
			cancelledBeforeOwnership = cancelled.getAsBoolean();
		} catch (RuntimeException | Error failure) {
			this.generationPermits.release();
			throw failure;
		}
		synchronized (this.lifecycle) {
			if (this.closeRequested || cancelledBeforeOwnership) {
				this.generationPermits.release();
				throw new java.util.concurrent.CancellationException(
					this.closeRequested ? "Tile generator is closed" : "Tile request was superseded"
				);
			}
			this.activeGenerations++;
		}
		try {
			return this.makeTile(x, z);
		} catch (RuntimeException | Error failure) {
			try {
				this.finishGeneration(null, failure);
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	private void checkAdmission(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean()) {
			throw new java.util.concurrent.CancellationException("Tile request was superseded");
		}
		synchronized (this.lifecycle) {
			if (this.closeRequested) {
				throw new java.util.concurrent.CancellationException("Tile generator is closed");
			}
		}
	}

	private Tile makeTile(int x, int z) {
		Resource<Cell[]> cells = this.cellPool.get(this.tileSizeBlocks.arraySize());
		Resource<Chunk[]> chunks = null;
		try {
			chunks = this.chunkPool.get(this.tileSizeChunks.arraySize());
			return new Tile(
				x, z, this.tileChunks, this.tileBorder, this.tileSizeBlocks, this.tileSizeChunks,
				cells, chunks
			);
		} catch (RuntimeException | Error failure) {
			if (chunks != null) {
				try {
					chunks.close();
				} catch (RuntimeException | Error cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			try {
				cells.close();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	private void finishGeneration(Tile tile, Throwable failure) {
		Throwable cleanupFailure = null;
		if (failure != null && tile != null) {
			try {
				tile.close();
			} catch (RuntimeException | Error closeFailure) {
				cleanupFailure = closeFailure;
			}
		}
		boolean closeResources;
		synchronized (this.lifecycle) {
			if (this.activeGenerations <= 0) {
				IllegalStateException underflow =
					new IllegalStateException("Tile generation ownership underflow");
				if (cleanupFailure != null) {
					underflow.addSuppressed(cleanupFailure);
				}
				throw underflow;
			}
			this.activeGenerations--;
			closeResources = this.closeRequested
				&& this.activeGenerations == 0
				&& this.markResourcesClosed();
		}
		this.generationPermits.release();
		if (closeResources) {
			try {
				this.closeResources();
			} catch (RuntimeException | Error closeFailure) {
				cleanupFailure = mergeFailure(cleanupFailure, closeFailure);
			}
		}
		if (cleanupFailure instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (cleanupFailure instanceof Error error) {
			throw error;
		}
	}

	private boolean markResourcesClosed() {
		if (this.resourcesClosed) {
			return false;
		}
		this.resourcesClosed = true;
		return true;
	}

	private void closeResources() {
		Throwable failure = null;
		try {
			this.cellPool.close();
		} catch (RuntimeException | Error closeFailure) {
			failure = closeFailure;
		}
		try {
			this.chunkPool.close();
		} catch (RuntimeException | Error closeFailure) {
			failure = mergeFailure(failure, closeFailure);
		}
		try {
			this.heightmap.close();
		} catch (RuntimeException | Error closeFailure) {
			failure = mergeFailure(failure, closeFailure);
		}
		if (failure instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (failure instanceof Error error) {
			throw error;
		}
	}

	private static Throwable mergeFailure(Throwable current, Throwable next) {
		if (current == null) {
			return next;
		}
		if (next instanceof Error && !(current instanceof Error)) {
			next.addSuppressed(current);
			return next;
		}
		current.addSuppressed(next);
		return current;
	}

	private static final class GenerationFuture extends CompletableFuture<Tile> implements SafeCloseable {
		private static final int QUEUED = 0;
		private static final int STARTED = 1;
		private static final int FINISHED = 2;

		private final AtomicReference<Future<?>> admission = new AtomicReference<>();
		private final AtomicReference<RequestPermit> requestPermit = new AtomicReference<>();
		private final AtomicInteger admissionState = new AtomicInteger(QUEUED);

		private void attachRequestPermit(RequestPermit permit) {
			if (!this.requestPermit.compareAndSet(null, permit)) {
				throw new IllegalStateException("Tile request permit was already attached");
			}
			if (this.admissionState.get() == FINISHED) {
				this.releaseRequestPermit();
			}
		}

		private boolean beginAdmission() {
			return this.admissionState.compareAndSet(QUEUED, STARTED);
		}

		private void finishAdmission() {
			int prior = this.admissionState.getAndSet(FINISHED);
			if (prior == FINISHED) {
				return;
			}
			this.releaseRequestPermit();
		}

		private void failBeforeAdmission() {
			if (this.admissionState.compareAndSet(QUEUED, FINISHED)) {
				this.releaseRequestPermit();
			}
		}

		private void releaseRequestPermit() {
			RequestPermit releasing = this.requestPermit.getAndSet(null);
			if (releasing != null) {
				releasing.close();
			}
		}

		private void attachAdmission(Future<?> task) {
			if (!this.admission.compareAndSet(null, task)) {
				throw new IllegalStateException("Tile admission task was already attached");
			}
			if (this.isCancelled()) {
				task.cancel(true);
			}
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			boolean cancelled = super.cancel(mayInterruptIfRunning);
			Future<?> task = this.admission.get();
			if (task != null) {
				task.cancel(true);
			}
			if (this.admissionState.compareAndSet(QUEUED, FINISHED)) {
				this.releaseRequestPermit();
			}
			return cancelled;
		}

		@Override
		public void close() {
			this.cancel(true);
		}
	}

	private static final class RequestPermit implements AutoCloseable {
		private final Semaphore permits;
		private final AtomicBoolean closed = new AtomicBoolean();

		private RequestPermit(Semaphore permits) {
			this.permits = permits;
		}

		@Override
		public void close() {
			if (this.closed.compareAndSet(false, true)) {
				this.permits.release();
			}
		}
	}
	
    private static int getBatchSize(int batchCount, Size chunkSize) {
        int batchSize = chunkSize.total() / batchCount;
        if (batchSize * batchCount < chunkSize.total()) {
            ++batchSize;
        }
        return batchSize;
    }
}
