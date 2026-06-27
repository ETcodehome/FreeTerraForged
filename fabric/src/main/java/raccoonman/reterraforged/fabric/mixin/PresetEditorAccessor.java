package raccoonman.reterraforged.fabric.mixin;

import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
import java.util.Optional;

@Mixin(PresetEditor.class)
public interface PresetEditorAccessor {
    @Accessor("EDITORS")
    @Mutable
    static void setEditors(Map<Optional<ResourceKey<WorldPreset>>, PresetEditor> editors) {
        throw new UnsupportedOperationException();
    }
}