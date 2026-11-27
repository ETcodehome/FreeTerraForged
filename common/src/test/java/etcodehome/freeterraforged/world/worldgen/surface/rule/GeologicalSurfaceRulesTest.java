package etcodehome.freeterraforged.world.worldgen.surface.rule;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.data.worldgen.preset.settings.MiscellaneousSettings;
import etcodehome.freeterraforged.world.worldgen.noise.module.Noises;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;
import etcodehome.freeterraforged.world.worldgen.geology.RockType;

class GeologicalSurfaceRulesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void oreRestrictionOffIncludesTheEntireMaterialPalette() {
        assertEquals(Blocks.CALCITE.defaultBlockState(), GeologicalSurfaceRules.materialFor(RockType.CALCITE, false));
        assertEquals(Blocks.GRAVEL.defaultBlockState(), GeologicalSurfaceRules.materialFor(RockType.GRAVEL, false));
        assertEquals(Blocks.DRIPSTONE_BLOCK.defaultBlockState(), GeologicalSurfaceRules.materialFor(RockType.DRIPSTONE, false));
        assertEquals(Blocks.PACKED_MUD.defaultBlockState(), GeologicalSurfaceRules.materialFor(RockType.PACKED_MUD, false));
        assertEquals(Blocks.TUFF.defaultBlockState(), GeologicalSurfaceRules.materialFor(RockType.TUFF, false));
        assertEquals(Blocks.BLACKSTONE.defaultBlockState(), GeologicalSurfaceRules.materialFor(RockType.BLACKSTONE, false));
        assertEquals(Blocks.DEEPSLATE.defaultBlockState(), GeologicalSurfaceRules.materialFor(RockType.DEEPSLATE, false));
        assertEquals(Blocks.BASALT.defaultBlockState(), GeologicalSurfaceRules.materialFor(RockType.BASALT, false));
    }

    @Test
    void genericVanillaStoneNoLongerMasksGeologicalMaterials() {
        for (RockType type : RockType.values()) {
            var material = GeologicalSurfaceRules.materialFor(type, false);
            var rule = GeologicalSurfaceRules.compose((x, y, z) -> Blocks.STONE.defaultBlockState(), (x, y, z) -> material);
            assertEquals(material, rule.tryApply(0, 180, 0));
        }
    }

    @Test
    void biomeCoversAndBedrockRetainPriority() {
        for (var block : new net.minecraft.world.level.block.Block[] {
            Blocks.GRASS_BLOCK, Blocks.SNOW_BLOCK, Blocks.BEDROCK, Blocks.TERRACOTTA, Blocks.DEEPSLATE, Blocks.WATER
        }) {
            var rule = GeologicalSurfaceRules.compose((x, y, z) -> block.defaultBlockState(),
                (x, y, z) -> Blocks.CALCITE.defaultBlockState());
            assertEquals(block.defaultBlockState(), rule.tryApply(0, 180, 0));
        }
    }

    @Test
    void supportedGravelCanAppearThroughOrdinarySoilCover() {
        var rule = GeologicalSurfaceRules.compose((x, y, z) -> Blocks.GRASS_BLOCK.defaultBlockState(),
            (x, y, z) -> Blocks.GRAVEL.defaultBlockState());
        assertEquals(Blocks.GRAVEL.defaultBlockState(), rule.tryApply(0, 180, 0));
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), GeologicalSurfaceRules.select(
            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState()));
    }

    @Test
    void decorativeMaterialsUseTheStoneCarverRuleOnlyWithTheFullStructuredPalette() {
        var settings = MiscellaneousSettings.makeDefault();
        settings.strataDecorator = true;
        settings.structuredStrata = true;
        settings.oreCompatibleStoneOnly = false;
        var extraStones = List.of(Blocks.BLACKSTONE, Blocks.BASALT, Blocks.DRIPSTONE_BLOCK, Blocks.PACKED_MUD);
        for (var block : extraStones) {
            assertEquals(Blocks.STONE.defaultBlockState(), GeologicalSurfaceRules.carverSubstrate(block.defaultBlockState(), settings));
        }
        var water = Blocks.WATER.defaultBlockState();
        assertSame(water, GeologicalSurfaceRules.carverSubstrate(water, settings));
        settings.structuredStrata = false;
        for (var block : extraStones) {
            var state = block.defaultBlockState();
            assertSame(state, GeologicalSurfaceRules.carverSubstrate(state, settings));
        }
    }

    @Test
    void structuredStrataKeepTheBiomeRuleInTheirOwnComposition() {
        var selector = Holder.direct(Noises.constant(0.0F));
        var biome = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        var structured = new StrataRule(FTFCommon.location("test"), selector, List.of(), 1, true, Optional.of(biome));
        var legacy = new StrataRule(FTFCommon.location("test"), selector, List.of(), 1);

        assertSame(structured, FastStrataSequenceRule.optimize(structured));
        assertInstanceOf(FastStrataSequenceRule.Source.class, FastStrataSequenceRule.optimize(SurfaceRules.sequence(biome, legacy)));
    }
}
