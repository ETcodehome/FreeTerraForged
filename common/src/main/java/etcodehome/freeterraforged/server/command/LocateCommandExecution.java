package etcodehome.freeterraforged.server.command;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

import net.minecraft.server.MinecraftServer;

/** Completes locate commands either synchronously or back on the server thread. */
final class LocateCommandExecution {
	private LocateCommandExecution() {
	}

	static <T> void dispatch(
		CompletableFuture<T> future,
		boolean pauseMainThread,
		MinecraftServer server,
		Runnable cleanup,
		BiConsumer<T, Throwable> completion
	) {
		if (pauseMainThread) {
			T result = null;
			Throwable failure = null;
			try {
				result = future.join();
			} catch (CancellationException | CompletionException error) {
				failure = error;
			} finally {
				cleanup.run();
			}
			completion.accept(result, failure);
			return;
		}

		future.whenComplete((result, failure) -> {
			cleanup.run();
			if (!server.isRunning()) {
				return;
			}
			try {
				server.execute(() -> {
					if (server.isRunning()) {
						completion.accept(result, failure);
					}
				});
			} catch (RejectedExecutionException ignored) {
				// Server stopped between completion and dispatch.
			}
		});
	}
}
