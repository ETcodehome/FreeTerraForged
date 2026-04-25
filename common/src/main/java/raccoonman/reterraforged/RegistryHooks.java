package raccoonman.reterraforged;

import com.mojang.serialization.Codec;
import net.minecraft.core.Cloner;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;

public class RegistryHooks {
    // We use strings for the NeoForge-specific IDs to avoid classloading the NeoForge classes on Fabric
    private static final String NEOFORGE = "neoforge";
    private static final String STRUC_MOD = "structure_modifier";
    private static final String BIOME_MOD = "biome_modifier";

    public static void addOptionalCloners(Cloner.Factory factory) {
        bind(factory, "structure_modifier", "net.neoforged.neoforge.common.world.StructureModifier");
        bind(factory, "biome_modifier", "net.neoforged.neoforge.common.world.BiomeModifier");
    }

    private static void bind(Cloner.Factory factory, String path, String className) {
        try {
            // Create the key manually since the iterator is skipping it
            ResourceKey<? extends Registry<?>> key = ResourceKey.createRegistryKey(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(NEOFORGE, path)
            );

            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Field field;
            try {
                field = clazz.getField("DIRECT_CODEC");
            } catch (NoSuchFieldException e) {
                field = clazz.getField("CODEC");
            }

            com.mojang.serialization.Codec<?> codec = (com.mojang.serialization.Codec<?>) field.get(null);
            if (codec != null) {
                factory.addCodec((ResourceKey) key, (com.mojang.serialization.Codec) codec);
            }
        } catch (Throwable ignored) {
            // This will trigger on Fabric/Vanilla as expected
        }
    }
}
