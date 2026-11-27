package etcodehome.freeterraforged.world.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class GeologicalSurfaceAcquisitionTest {
    @Test
    void inheritedSurfaceInsideGeologicalStrataIsAcquiredBeforeExecution() {
        var root = JsonParser.parseString("""
            {"type":"freeterraforged:strata","geology":true,"surface":{
                "type":"freeterraforged:inherited_surface","policy":"bundled_biome_domains",
                "fallback":{"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}}
            }}
            """);
        var acquired = WorldgenSurfaceInputs.normalizeLayers(root, index -> {
            throw new AssertionError("No lower packs should be requested");
        }, -1).getAsJsonObject();
        assertTrue(acquired.get("geology").getAsBoolean());
        assertEquals("freeterraforged:acquired_surface", acquired.getAsJsonObject("surface").get("type").getAsString());
        assertFalse(acquired.toString().contains("inherited_surface"));
    }

    @Test
    void olderStrataWithoutNestedSurfaceRemainsReadable() {
        var root = JsonParser.parseString("{\"type\":\"freeterraforged:strata\",\"geology\":true}");
        assertEquals(root, WorldgenSurfaceInputs.normalizeLayers(root, index -> {
            throw new AssertionError("No lower packs should be requested");
        }, -1));
    }
}
