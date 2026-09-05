package raccoonman.reterraforged.concurrent;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.concurrent.cache.SafeCloseable;

public final class RetainedResource<T extends SafeCloseable> implements SafeCloseable {
	private final T value;
	private int references;
	private boolean retired;
	private boolean closed;

	public RetainedResource(T value) {
		this.value = Objects.requireNonNull(value, "value");
	}

	@Nullable
	public synchronized Lease<T> acquire() {
		if (this.retired) {
			return null;
		}
		this.references++;
		return new Lease<>(this, this.value);
	}

	@Override
	public void close() {
		T closing = null;
		synchronized (this) {
			if (this.retired) {
				return;
			}
			this.retired = true;
			if (this.references == 0 && !this.closed) {
				this.closed = true;
				closing = this.value;
			}
		}
		if (closing != null) {
			closing.close();
		}
	}

	private void release() {
		T closing = null;
		synchronized (this) {
			if (this.references <= 0) {
				throw new IllegalStateException("Retained resource lease underflow");
			}
			this.references--;
			if (this.retired && this.references == 0 && !this.closed) {
				this.closed = true;
				closing = this.value;
			}
		}
		if (closing != null) {
			closing.close();
		}
	}

	public static final class Lease<T extends SafeCloseable> implements AutoCloseable {
		private RetainedResource<T> owner;
		private final T value;

		private Lease(RetainedResource<T> owner, T value) {
			this.owner = owner;
			this.value = value;
		}

		public T value() {
			if (this.owner == null) {
				throw new IllegalStateException("Retained resource lease is closed");
			}
			return this.value;
		}

		@Override
		public void close() {
			RetainedResource<T> releasing = this.owner;
			if (releasing == null) {
				return;
			}
			this.owner = null;
			releasing.release();
		}
	}
}
