package etcodehome.freeterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shared deterministic precedence operations for direct, tag, and dimension rules. */
final class BiomeReplacementPrecedence {
	private BiomeReplacementPrecedence() {
	}

	static <K, V> V overwrite(Map<K, V> scope, K key, V value) {
		return scope.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
	}

	static <K, V> FillResult<K> fillUnclaimed(Map<K, V> scope, Iterable<? extends K> keys, V value) {
		Objects.requireNonNull(value, "value");
		return fillUnclaimedWith(scope, keys, ignored -> value);
	}

	static <K, V> FillResult<K> fillUnclaimedWith(
		Map<K, V> scope,
		Iterable<? extends K> keys,
		Function<? super K, ? extends V> valueFactory
	) {
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(keys, "keys");
		Objects.requireNonNull(valueFactory, "valueFactory");
		List<K> added = new ArrayList<>();
		int shadowed = 0;
		for (K key : keys) {
			Objects.requireNonNull(key, "key");
			if (scope.containsKey(key)) {
				shadowed++;
			} else {
				scope.put(key, Objects.requireNonNull(valueFactory.apply(key), "value"));
				added.add(key);
			}
		}
		return new FillResult<>(added, shadowed);
	}

	static <K, V, M extends Map<K, V>> M overlay(
		Supplier<M> factory,
		Map<K, V> global,
		Map<K, V> dimension
	) {
		M result = Objects.requireNonNull(factory.get(), "map factory result");
		result.putAll(global);
		result.putAll(dimension);
		return result;
	}

	record FillResult<K>(List<K> added, int shadowed) {
		FillResult {
			added = List.copyOf(added);
			if (shadowed < 0) {
				throw new IllegalArgumentException("Shadowed count must not be negative");
			}
		}
	}
}
