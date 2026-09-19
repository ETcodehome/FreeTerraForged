package etcodehome.freeterraforged.world.worldgen.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SeedTest {
	@Test
	void retainsTheFullWorldSeedAndDistinguishesHighBits() {
		long highSeed = 1L << 40;
		assertEquals(highSeed, new Seed(highSeed).root());
		assertNotEquals(Seed.toInt(0L), Seed.toInt(highSeed));
	}

	@Test
	void preservesLegacyResultsForSignedIntSeeds() {
		assertEquals(12345, Seed.toInt(12345L));
		assertEquals(-12345, Seed.toInt(-12345L));
	}
}
