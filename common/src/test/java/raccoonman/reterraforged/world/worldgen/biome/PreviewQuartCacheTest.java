package raccoonman.reterraforged.world.worldgen.biome;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

class PreviewQuartCacheTest {
	@Test
	void returnsOnlyAnExactQuartCoordinate() {
		PreviewQuartCache cache = new PreviewQuartCache();
		Holder<Biome> biome = Holder.direct((Biome)null);

		cache.put(-1_234_567, -96, 7_654_321, biome);

		assertSame(biome, cache.get(-1_234_567, -96, 7_654_321));
		assertNull(cache.get(-1_234_566, -96, 7_654_321));
		assertNull(cache.get(-1_234_567, -95, 7_654_321));
		assertNull(cache.get(-1_234_567, -96, 7_654_320));
	}
}
