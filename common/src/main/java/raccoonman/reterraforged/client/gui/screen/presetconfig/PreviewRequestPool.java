package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Screen-owned request contexts shared by page-local preview widgets.
 *
 * <p>There is at most one terrain and one biome owner for the current semantic key. Replacing a
 * key closes the previous owner after its active leases finish; changing pages with the same key
 * therefore does not reconstruct registries, generator state, or a worldgen plan.</p>
 */
final class PreviewRequestPool implements AutoCloseable {
    private IPreviewHandler.PreparedContext terrain;
    private IPreviewHandler.PreparedContext biome;
    private boolean closed;

    synchronized IPreviewHandler.PreparedContext.Lease acquire(
            BiomePreview.CacheKey key,
            boolean biomePipeline,
            Supplier<IPreviewHandler.PreparedContext> factory
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        if (this.closed) {
            throw new java.util.concurrent.CancellationException("Preview request pool is closed");
        }

        IPreviewHandler.PreparedContext current = biomePipeline ? this.biome : this.terrain;
        if (current == null || !current.matches(key, biomePipeline)) {
            IPreviewHandler.PreparedContext replacement = Objects.requireNonNull(
                    factory.get(), "Preview request factory returned null"
            );
            if (!replacement.matches(key, biomePipeline)) {
                replacement.close();
                throw new IllegalArgumentException("Preview request factory returned the wrong semantic owner");
            }
            if (biomePipeline) {
                this.biome = replacement;
            } else {
                this.terrain = replacement;
            }
            if (current != null) {
                current.close();
            }
            current = replacement;
        }
        return current.acquire();
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.terrain != null) {
            this.terrain.close();
            this.terrain = null;
        }
        if (this.biome != null) {
            this.biome.close();
            this.biome = null;
        }
    }
}
