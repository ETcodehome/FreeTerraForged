package etcodehome.freeterraforged.platform.neoforge;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import etcodehome.freeterraforged.FTFCommon;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

public final class DataGenUtilImpl {

	public static DataProvider createRegistryProvider(PackOutput output, CompletableFuture<RegistrySetBuilder.PatchedRegistries> registries) {
		return new DatapackBuiltinEntriesProvider(output, registries, Set.of(FTFCommon.MOD_ID, "minecraft"));
	}
}
