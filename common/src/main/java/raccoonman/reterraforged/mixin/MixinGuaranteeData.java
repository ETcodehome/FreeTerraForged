package raccoonman.reterraforged.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

@Mixin(CreateWorldScreen.class)
public class MixinGuaranteeData {

    @Inject(
            method = {"createNewWorld", "onCreate"},
            at = @At("HEAD"),
            require = 0,
            cancellable = true
    )
    private void rtf$verifyAndRetryPresetPresence(CallbackInfo ci) {
        CreateWorldScreen screen = (CreateWorldScreen) (Object) this;
        WorldCreationUiState uiState = screen.getUiState();

        // Early exit guard to only validate preset staging if an RTF world type is currently selected
        // We skip the check for Vanilla, Flat, Amplified, or other modded world types
        if (!isRTFWorld(uiState)) {
            return;
        }

        long timeoutMs = 10_000;
        long startTime = System.currentTimeMillis();
        boolean loadedSuccessfully = false;
        Exception lastException = null;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                var settings = uiState.getSettings();

                Pair<Path, PackRepository> pair = screen.getDataPackSelectionSettings(settings.dataConfiguration());
                Path datapacksDir = pair.getFirst();
                PackRepository repository = pair.getSecond();
                Path presetZip = datapacksDir.resolve("reterraforged-preset.zip");

                // Verify the preset file is physically written to disk and non-empty
                if (Files.exists(presetZip) && Files.size(presetZip) > 0) {

                    // Force repository scan
                    repository.reload();

                    // Check if repository indexed our pack
                    var targetPack = repository.getAvailablePacks().stream()
                            .filter(pack -> pack.getId().contains("reterraforged-preset"))
                            .findFirst();

                    if (targetPack.isPresent()) {
                        String packId = targetPack.get().getId();
                        var selectedPacks = new ArrayList<>(repository.getSelectedIds());

                        // Ensure it's active in the current selected pack list
                        if (!selectedPacks.contains(packId)) {
                            selectedPacks.add(packId);
                            repository.setSelected(selectedPacks);
                        }

                        loadedSuccessfully = true;
                        break; // Preset verified, can proceed with world creation!
                    }
                }
            } catch (Exception e) {
                lastException = e;
            }

            // Wait 100ms before retrying disk / repository scan
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Halt world creation explicitly if RTF preset was expected but missing
        if (!loadedSuccessfully) {
            ci.cancel(); // Cancel CreateWorldScreen execution completely

            throw new IllegalStateException(
                    "[ReTerraForged] Aborted world load because preset was not staged after 10 seconds.",
                    lastException
            );
        }
    }

    /**
     * Checks if the active world type preset in the Create World UI belongs to ReTerraForged.
     */
    private static boolean isRTFWorld(WorldCreationUiState uiState) {
        if (uiState == null) return false;

        var presetEntry = uiState.getWorldType();
        if (presetEntry == null || presetEntry.preset() == null) return false;

        return presetEntry.preset().unwrapKey()
                .map(key -> key.location().getNamespace().equalsIgnoreCase("reterraforged")
                        || key.location().getPath().contains("reterraforged"))
                .orElse(false);
    }
}