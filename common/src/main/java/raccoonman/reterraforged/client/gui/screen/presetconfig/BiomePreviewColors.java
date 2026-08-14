package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

final class BiomePreviewColors {
    private static final Map<ResourceLocation, Integer> OVERRIDES = Map.ofEntries(
        entry("plains", 0x8DB360),
        entry("sunflower_plains", 0xB5DB88),
        entry("forest", 0x056621),
        entry("flower_forest", 0x2D8E49),
        entry("dark_forest", 0x40511A),
        entry("birch_forest", 0x307444),
        entry("taiga", 0x0B6659),
        entry("snowy_taiga", 0x31554A),
        entry("desert", 0xFA9418),
        entry("savanna", 0xBDB25F),
        entry("swamp", 0x07F9B2),
        entry("mangrove_swamp", 0x2C6143),
        entry("badlands", 0xD94515),
        entry("meadow", 0x83BB6D),
        entry("cherry_grove", 0xE7B0D3),
        entry("jungle", 0x537B09),
        entry("mushroom_fields", 0xFF00FF),
        entry("snowy_plains", 0xFFFFFF),
        entry("frozen_peaks", 0xD8E7EA),
        entry("jagged_peaks", 0xB0B3B3),
        entry("stony_peaks", 0x888888),
        entry("river", 0x0000FF),
        entry("frozen_river", 0xA0A0FF),
        entry("ocean", 0x000070),
        entry("deep_ocean", 0x000030),
        entry("deep_lukewarm_ocean", 0x245F8F),
        entry("deep_cold_ocean", 0x101050),
        entry("deep_frozen_ocean", 0x404090),
        entry("warm_ocean", 0x43D5EE),
        entry("lukewarm_ocean", 0x45ADF2),
        entry("cold_ocean", 0x202070),
        entry("frozen_ocean", 0x7070D6)
    );

    private BiomePreviewColors() {
    }

    static int color(ResourceLocation id) {
        Integer override = OVERRIDES.get(id);
        if (override != null) {
            return toNativeColor(override);
        }

        int hash = mix(id.toString().hashCode());
        float hue = (hash & 0xFFFF) / 65536.0F;
        float saturation = 0.48F + ((hash >>> 16) & 0xFF) / 255.0F * 0.32F;
        float brightness = 0.62F + ((hash >>> 24) & 0xFF) / 255.0F * 0.25F;
        return toNativeColor(Color.HSBtoRGB(hue, saturation, brightness));
    }

    static int color(Holder<Biome> biome, ResourceLocation id) {
        Integer override = OVERRIDES.get(id);
        if (override != null) {
            return toNativeColor(override);
        }
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
            return toNativeColor(biome.value().getWaterColor());
        }
        return biome.value().getSpecialEffects().getGrassColorOverride()
            .or(() -> biome.value().getSpecialEffects().getFoliageColorOverride())
            .map(BiomePreviewColors::toNativeColor)
            .orElseGet(() -> color(id));
    }

    private static Map.Entry<ResourceLocation, Integer> entry(String path, int rgb) {
        return Map.entry(ResourceLocation.withDefaultNamespace(path), rgb);
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }

    private static int toNativeColor(int rgb) {
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red | green << 8 | blue << 16 | 0xFF000000;
    }
}
