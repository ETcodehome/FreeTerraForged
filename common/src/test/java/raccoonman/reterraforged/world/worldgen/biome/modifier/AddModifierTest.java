package raccoonman.reterraforged.world.worldgen.biome.modifier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

class AddModifierTest {
	private static final Holder<Biome> BIOME = Holder.direct((Biome) null);
	private static final Holder<PlacedFeature> EXISTING = Holder.direct((PlacedFeature) null);
	private static final Holder<PlacedFeature> ADDED = Holder.direct((PlacedFeature) null);

	@Test
	void appendPreservesOccurrenceOrderAndReturnsImmutableData() {
		AddModifier modifier = modifier(Order.APPEND, Optional.empty());

		List<Holder<PlacedFeature>> result = modifier.apply(
			BIOME, new ArrayList<>(List.of(EXISTING)), null
		);

		assertSame(EXISTING, result.get(0));
		assertSame(ADDED, result.get(1));
		assertThrows(UnsupportedOperationException.class, () -> result.add(EXISTING));
	}

	@Test
	void prependPreservesOccurrenceOrder() {
		List<Holder<PlacedFeature>> result = modifier(Order.PREPEND, Optional.empty())
			.apply(BIOME, List.of(EXISTING), null);

		assertSame(ADDED, result.get(0));
		assertSame(EXISTING, result.get(1));
	}

	@Test
	void excludedBiomeProducesAnImmutableSnapshot() {
		Filter filter = new Filter(HolderSet.direct(), Filter.Behavior.WHITELIST);
		ArrayList<Holder<PlacedFeature>> mutable = new ArrayList<>(List.of(EXISTING));

		List<Holder<PlacedFeature>> result = modifier(Order.APPEND, Optional.of(filter))
			.apply(BIOME, mutable, null);
		mutable.clear();

		assertSame(EXISTING, result.getFirst());
		assertThrows(UnsupportedOperationException.class, () -> result.add(ADDED));
	}

	private static AddModifier modifier(Order order, Optional<Filter> filter) {
		return new AddModifier(
			order,
			GenerationStep.Decoration.VEGETAL_DECORATION,
			filter,
			HolderSet.direct(ADDED)
		);
	}
}
