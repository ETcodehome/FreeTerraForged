package etcodehome.freeterraforged.world.worldgen.heightproviders;

import com.mojang.serialization.MapCodec;
import etcodehome.freeterraforged.platform.RegistryUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.HeightProviderType;

public class FTFHeightProviderTypes {
	public static final HeightProviderType<LegacyCarverHeight> LEGACY_CARVER = register("legacy_carver", LegacyCarverHeight.CODEC);
	
	public static void bootstrap() {
	}
	
	private static <T extends HeightProvider> HeightProviderType<T> register(String name, MapCodec<T> codec) {
		HeightProviderType<T> type = () -> codec;
		RegistryUtil.register(BuiltInRegistries.HEIGHT_PROVIDER_TYPE, name, type);
		return type;
	}
}
