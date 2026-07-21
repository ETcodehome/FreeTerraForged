package raccoonman.reterraforged.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
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

        long timeoutMs = 10_000;
        long startTime = System.currentTimeMillis();
        boolean loadedSuccessfully = false;
        Exception lastException = null;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                var uiState = screen.getUiState();
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

        // Halt world creation explicitly
        if (!loadedSuccessfully) {
            ci.cancel(); // Cancel CreateWorldScreen execution completely

            throw new IllegalStateException(
                    "[ReTerraForged] Aborted world load because preset was not staged after 10 seconds.",
                    lastException
            );
        }
    }
}