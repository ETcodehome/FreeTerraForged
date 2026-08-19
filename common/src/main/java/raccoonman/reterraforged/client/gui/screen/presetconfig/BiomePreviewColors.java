package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.awt.Color;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

final class BiomePreviewColors {
    private static final Map<ResourceLocation, Integer> OVERRIDES = Map.ofEntries(
            // --- Plains & Fields ---
            entry("plains", 0x86B14B),                // Balanced Grass Green
            entry("sunflower_plains", 0x96D847),      // Balanced Spring Green
            entry("snowy_plains", 0xF4F9FB),          // Crisp Pure Snow
            entry("meadow", 0x66B24B),                // Mid-Vibrance Alpine Green
            entry("cherry_grove", 0xB76EC7),          // Soft Meadow Blossom Pink
            entry("mushroom_fields", 0xA65E9B),       // Balanced Fungal Purple

            // --- Forests ---
            entry("forest", 0x50A355),                // Mid-Vibrance Woodland Green
            entry("flower_forest", 0x93D079),         // Soft Pale Green
            entry("birch_forest", 0x7FB24B),          // Balanced Birch Green
            entry("old_growth_birch_forest", 0x649A42),// Mid Birch Olive
            entry("dark_forest", 0x618A4D),           // Soft Canopy Olive-Sage
            entry("pale_garden", 0xBBBDAE),           // Muted Slate Ash
            entry("taiga", 0x508A6B),                 // Mid-Vibrance Spruce Sage
            entry("snowy_taiga", 0x94CDC4),           // Soft Frost Teal
            entry("old_growth_pine_taiga", 0x737343), // Balanced Podzol Olive
            entry("old_growth_spruce_taiga", 0x4D6F50),// Muted Spruce Green

            // --- Jungles ---
            entry("jungle", 0x41B139),                // Balanced Emerald Jungle
            entry("sparse_jungle", 0x6FAF29),         // Mid Bushy Green
            entry("bamboo_jungle", 0x90BF20),         // Balanced Bamboo Lime

            // --- Swamps ---
            entry("swamp", 0x637D45),                 // Muted Mossy Green
            entry("mangrove_swamp", 0x8B6645),        // Balanced Mangrove Bark

            // --- Arid & Savannas ---
            entry("desert", 0xE3D58D),                // Mid-Tone Sand Gold
            entry("savanna", 0xB9A648),               // Balanced Savanna Gold
            entry("savanna_plateau", 0xAB973D),       // Dusty Plateau Gold
            entry("windswept_savanna", 0xCABC53),     // Mid Straw Yellow

            // --- Badlands ---
            entry("badlands", 0xD3734D),              // Balanced Sunlit Terracotta
            entry("wooded_badlands", 0xD3BC35),       // Balanced Golden Plateau Yellow
            entry("eroded_badlands", 0xDD7E56),       // Soft Terracotta Ochre

            // --- Mountains & Slopes ---
            entry("windswept_hills", 0x657B54),       // Balanced Mountain Green
            entry("windswept_gravelly_hills", 0x808C75),// Muted Gravel Green
            entry("windswept_forest", 0x4D6F47),      // Highland Pine Green
            entry("stony_peaks", 0x918F86),           // Soft Stone Gray
            entry("jagged_peaks", 0xE8F1F5),          // Soft Peak Snow White
            entry("frozen_peaks", 0x8AC8E7),          // Soft Glacial Ice Blue
            entry("snowy_slopes", 0xC2E4F1),          // Light Powder Blue Snow
            entry("grove", 0x6FB197),                 // Mid Spruce Sage

            // --- Coasts & Ice Spikes ---
            entry("beach", 0xE2C94C),                 // Warm Sand Gold
            entry("snowy_beach", 0xE4E4D8),           // Soft Snow Beach
            entry("stony_shore", 0x9D9B90),           // Neutral Shore Gray
            entry("ice_spikes", 0x68C8E6),            // Soft Ice Cyan

            // --- Caves & Underground ---
            entry("dripstone_caves", 0x95694C),       // Speleothem Brown
            entry("lush_caves", 0x4EA93D),            // Vibrant Moss Green
            entry("deep_dark", 0x254853),             // Sculk Slate Cyan

            // --- Mid-Tone Rivers & Oceans ---
            entry("river", 0x3F71D5),                 // Medium Sky Blue
            entry("frozen_river", 0x5D8ED8),          // Medium Frost Blue
            entry("ocean", 0x3C68BE),                 // Medium Royal Blue
            entry("deep_ocean", 0x2C5099),            // Medium Midnight Blue
            entry("warm_ocean", 0x2B8597),            // Slightly Darker Muted Teal
            entry("lukewarm_ocean", 0x408EC2),        // Medium Shallow Blue
            entry("deep_lukewarm_ocean", 0x27618E),   // Slightly Darker Deep Blue
            entry("cold_ocean", 0x3A53A2),            // Medium Cool Navy
            entry("deep_cold_ocean", 0x2C4186),       // Restored Deep Cool Navy
            entry("frozen_ocean", 0x4A72B1),          // Medium Ice Navy
            entry("deep_frozen_ocean", 0x385C99)      // Restored Deep Ice Navy
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
