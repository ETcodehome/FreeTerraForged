package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class BiomePreviewColorsTest {
    @Test
    void vanillaOverridesUseNativeImageChannelOrder() {
        assertEquals(0xFF4BB186, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("plains")));
        assertEquals(0xFFD5713F, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("river")));
        assertEquals(0xFF8E6127, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("deep_lukewarm_ocean")));
        assertEquals(0xFF86412C, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("deep_cold_ocean")));
        assertEquals(0xFF995C38, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("deep_frozen_ocean")));
    }

    @Test
    void registryIdFallbackIsStableAndDistinguishesIds() {
        ResourceLocation first = ResourceLocation.fromNamespaceAndPath("example", "alpine_grove");
        ResourceLocation second = ResourceLocation.fromNamespaceAndPath("example", "alpine_meadow");

        assertEquals(BiomePreviewColors.color(first), BiomePreviewColors.color(first));
        assertNotEquals(BiomePreviewColors.color(first), BiomePreviewColors.color(second));
    }
}