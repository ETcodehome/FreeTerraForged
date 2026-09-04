package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

class PreviewRequestOwnershipTest {
	@Test
	void closeDefersUntilTheActiveUseReleasesAndRejectsNewUses() {
		IPreviewHandler.PreparedContext owner = new IPreviewHandler.PreparedContext(
			null, null, null, null
		);
		IPreviewHandler.PreparedContext.Lease lease = owner.acquire();

		owner.close();
		assertThrows(CancellationException.class, owner::acquire);
		assertDoesNotThrow(lease::close);
		assertDoesNotThrow(lease::close);
		assertDoesNotThrow(owner::close);
	}
}
