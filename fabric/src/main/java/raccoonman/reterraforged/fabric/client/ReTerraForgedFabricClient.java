package raccoonman.reterraforged.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.resources.ResourceKey;
import raccoonman.reterraforged.client.gui.screen.presetconfig.PresetConfigScreen;
import raccoonman.reterraforged.fabric.mixin.PresetEditorAccessor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ReTerraForgedFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 1. Create a mutable copy of Mojang's hardcoded map
        Map<Optional<ResourceKey<WorldPreset>>, PresetEditor> mutableEditors = new HashMap<>(PresetEditor.EDITORS);

        // 2. Inject your custom config screen provider over the Normal world preset
        mutableEditors.put(Optional.of(WorldPresets.NORMAL), (screen, ctx) -> new PresetConfigScreen(screen));

        // 3. Overwrite the final field with our expanded immutable map copy
        PresetEditorAccessor.setEditors(Map.copyOf(mutableEditors));
    }
}