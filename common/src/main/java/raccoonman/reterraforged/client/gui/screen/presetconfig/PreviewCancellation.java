package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.util.concurrent.CancellationException;

/** Cancellation token shared by a single preview request and its worker stages. */
final class PreviewCancellation {
    private volatile boolean cancelled;

    void cancel() {
        this.cancelled = true;
    }

    boolean isCancelled() {
        return this.cancelled;
    }

    void check() {
        if (this.cancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Preview request superseded");
        }
    }

    static boolean isCancellation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
