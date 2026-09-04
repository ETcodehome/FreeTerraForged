package raccoonman.reterraforged.concurrent.cache.map;

import java.util.List;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Consumer;

public interface LongMap<T> {
	static <T> void acceptAll(List<T> values, Consumer<T> consumer) {
		Throwable failure = null;
		for (T value : values) {
			try {
				consumer.accept(value);
			} catch (RuntimeException | Error closingFailure) {
				if (failure == null) {
					failure = closingFailure;
				} else if (closingFailure instanceof Error && !(failure instanceof Error)) {
					closingFailure.addSuppressed(failure);
					failure = closingFailure;
				} else {
					failure.addSuppressed(closingFailure);
				}
			}
		}
		if (failure instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (failure instanceof Error error) {
			throw error;
		}
	}

    int size();
    
    void clear();
    
    void remove(long key);
    
    void remove(long key, Consumer<T> ifPreset);

	boolean remove(long key, T expected, Consumer<T> ifPresent);
    
    int removeIf(Predicate<T> predicate);

	default int removeIf(Predicate<T> predicate, Consumer<T> removal) {
		return this.removeIf(predicate);
	}
    
    void put(long key, T value);
    
    T get(long key);
    
    T computeIfAbsent(long key, LongFunction<T> computer);

	default T computeIfAbsent(long key, LongFunction<T> computer, Consumer<T> eviction) {
		return this.computeIfAbsent(key, computer);
	}

	default T computeIfAbsent(
		long key,
		LongFunction<T> computer,
		Predicate<T> evictable,
		Consumer<T> eviction
	) {
		return this.computeIfAbsent(key, computer, eviction);
	}

	default void trim(Predicate<T> evictable, Consumer<T> eviction) {
	}
    
    default <V> V map(long key, LongFunction<T> factory, Function<T, V> mapper) {
        return mapper.apply(this.computeIfAbsent(key, factory));
    }
}
