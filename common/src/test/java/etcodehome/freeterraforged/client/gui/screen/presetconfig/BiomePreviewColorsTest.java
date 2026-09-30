package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class BiomePreviewColorsTest {
    @Test
    void vanillaOverridesUseNativeImageChannelOrder() {
        assertEquals(0xFF4BB186, BiomePreviewColors.color(new ResourceLocation("plains")));
        assertEquals(0xFFD5713F, BiomePreviewColors.color(new ResourceLocation("river")));
        assertEquals(0xFF8E6127, BiomePreviewColors.color(new ResourceLocation("deep_lukewarm_ocean")));
        assertEquals(0xFF86412C, BiomePreviewColors.color(new ResourceLocation("deep_cold_ocean")));
        assertEquals(0xFF995C38, BiomePreviewColors.color(new ResourceLocation("deep_frozen_ocean")));
    }

    @Test
    void registryIdFallbackIsStableAndDistinguishesIds() {
        ResourceLocation first = new ResourceLocation("example", "alpine_grove");
        ResourceLocation second = new ResourceLocation("example", "alpine_meadow");

        assertEquals(BiomePreviewColors.color(first), BiomePreviewColors.color(first));
        assertNotEquals(BiomePreviewColors.color(first), BiomePreviewColors.color(second));
    }
}