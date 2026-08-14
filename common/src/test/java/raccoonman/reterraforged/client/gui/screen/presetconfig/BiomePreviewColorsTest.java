package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class BiomePreviewColorsTest {
    @Test
    void vanillaOverridesUseNativeImageChannelOrder() {
        assertEquals(0xFF60B38D, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("plains")));
        assertEquals(0xFFFF0000, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("river")));
        assertEquals(0xFF8F5F24, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("deep_lukewarm_ocean")));
        assertEquals(0xFF501010, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("deep_cold_ocean")));
        assertEquals(0xFF904040, BiomePreviewColors.color(ResourceLocation.withDefaultNamespace("deep_frozen_ocean")));
    }

    @Test
    void registryIdFallbackIsStableAndDistinguishesIds() {
        ResourceLocation first = ResourceLocation.fromNamespaceAndPath("example", "alpine_grove");
        ResourceLocation second = ResourceLocation.fromNamespaceAndPath("example", "alpine_meadow");

        assertEquals(BiomePreviewColors.color(first), BiomePreviewColors.color(first));
        assertNotEquals(BiomePreviewColors.color(first), BiomePreviewColors.color(second));
    }
}
