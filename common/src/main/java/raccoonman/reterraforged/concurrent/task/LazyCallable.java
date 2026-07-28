package raccoonman.reterraforged.concurrent.task;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public abstract class LazyCallable<T> implements Callable<T>, Future<T>, Supplier<T> {
	protected volatile T value;

	public LazyCallable() {
		this.value = null;
	}

	@Override
	public T call() {
		// Fast lock-free read path
		T result = this.value;
		if (result != null) {
			return result;
		}

		// Reentrant sync path for single-execution initialization
		synchronized (this) {
			result = this.value;
			if (result == null) {
				result = this.create();
				Objects.requireNonNull(result, "LazyCallable computed null value");
				this.value = result;
			}
			return result;
		}
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		return false;
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public boolean isDone() {
		// Volatile read is completely safe without locks
		return this.value != null;
	}

	@Override
	public T get() {
		return this.call();
	}

	@Override
	public T get(long timeout, TimeUnit unit) {
		return this.call();
	}

	protected abstract T create();

}