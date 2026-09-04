package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PreviewRequestKeyFactoryTest {
	@Test
	void acquisitionGenerationsAdvanceMonotonically() {
		PreviewRequestKeyFactory factory = new PreviewRequestKeyFactory();

		assertEquals(1L, factory.advanceGeneration());
		assertEquals(2L, factory.advanceGeneration());
		assertEquals(3L, factory.advanceGeneration());
	}

	@Test
	void closePreventsAnyLaterGenerationPublication() {
		PreviewRequestKeyFactory factory = new PreviewRequestKeyFactory();
		factory.advanceGeneration();

		factory.close();

		assertThrows(
			java.util.concurrent.CancellationException.class,
			factory::advanceGeneration
		);
	}
}
