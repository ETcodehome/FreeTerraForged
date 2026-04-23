package raccoonman.reterraforged.platform;

import java.util.concurrent.CompletableFuture;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider; // NeoForge specific
import raccoonman.reterraforged.RTFCommon;

public final class DataGenUtil {

	/**
	 * Replaces the @ExpectPlatform method with a NeoForge-native provider.
	 * In 1.21.1, we use DatapackBuiltinEntriesProvider to generate JSONs for
	 * our custom world-gen registries.
	 */
	public static DataProvider createRegistryProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> providerLookup) {
		// We pass the output, the lookup, and the set of Registry Keys our mod is responsible for.
		// Usually, ReTerraForged will have its own RegistrySetBuilder defined elsewhere.
		return new DatapackBuiltinEntriesProvider(
				output,
				providerLookup,
				RTFCommon.getRegistryBuilder(), // This should be your RegistrySetBuilder instance
				Set.of(RTFCommon.MOD_ID)
		);
	}
}