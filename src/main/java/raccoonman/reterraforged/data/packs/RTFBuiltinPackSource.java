package raccoonman.reterraforged.data.packs;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.Pack.ResourcesSupplier;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import raccoonman.reterraforged.RTFCommon;

public class RTFBuiltinPackSource extends BuiltInPackSource {
	private static final ResourceLocation PACKS_DIR = RTFCommon.location("datapacks");
	
	public RTFBuiltinPackSource(DirectoryValidator directoryValidator) {
		super(PackType.SERVER_DATA, createRTFPackSource(), PACKS_DIR, directoryValidator);
	}

	@Nullable
	@Override
	protected Pack createVanillaPack(PackResources packResources) {
		return null;
	}

	@Override
	protected Component getPackTitle(String title) {
		return Component.literal(title);
	}

	@Override
	protected Pack createBuiltinPack(String id, ResourcesSupplier resourceSupplier, Component description) {
		// 1. PackLocationInfo (Note the package: net.minecraft.server.packs.PackLocationInfo)
		net.minecraft.server.packs.PackLocationInfo locationInfo = new net.minecraft.server.packs.PackLocationInfo(
				id,
				description,
				PackSource.FEATURE,
				java.util.Optional.empty() // KnownPack
		);

		// 2. PackSelectionConfig (Note the package: net.minecraft.server.packs.repository.PackSelectionConfig)
		net.minecraft.server.packs.PackSelectionConfig selectionConfig = new net.minecraft.server.packs.PackSelectionConfig(
				false,             // initialEnabled
				Pack.Position.TOP, // defaultPosition
				false              // required
		);

		// 3. The method call
		return Pack.readMetaAndCreate(locationInfo, resourceSupplier, PackType.SERVER_DATA, selectionConfig);
	}

	private static VanillaPackResources createRTFPackSource() {
		VanillaPackResourcesBuilder builder = new VanillaPackResourcesBuilder().exposeNamespace(RTFCommon.MOD_ID);
		PackType packType = PackType.SERVER_DATA;
		String root = "/" + packType.getDirectory() + "/";
		URL uRL = RTFCommon.class.getResource(root);
		if (uRL == null) {
			RTFCommon.LOGGER.error("File {} does not exist in classpath", root);
		} else {
			try {
				URI uRI = uRL.toURI();
				String uriSchema = uRI.getScheme();
				if (!"jar".equals(uriSchema) && !"file".equals(uriSchema)) {
					RTFCommon.LOGGER.warn("Assets URL '{}' uses unexpected schema", uRI);
				}
				Path path = safeGetPath(uRI);
				builder.pushAssetPath(packType, path);
			} catch (Exception exception) {
				RTFCommon.LOGGER.error("Couldn't resolve path to assets", exception);
			}	
		}

		net.minecraft.server.packs.PackLocationInfo packLocationInfo = new net.minecraft.server.packs.PackLocationInfo(
				"reterraforged_builtin", // Internal ID
				Component.literal("ReTerraForged Builtin Resources"), // Display Name
				net.minecraft.server.packs.repository.PackSource.BUILT_IN, // Source type
				java.util.Optional.empty() // Signature/KnownPack info
		);

        return builder.applyDevelopmentConfig().build(packLocationInfo);
	}

    private static Path safeGetPath(URI uRI) throws IOException {
        try {
            return Paths.get(uRI);
        } catch (FileSystemNotFoundException fileSystemNotFoundException) {
        } catch (Throwable throwable) {
        	RTFCommon.LOGGER.warn("Unable to get path for: {}", uRI, throwable);
        }
        try {
            FileSystems.newFileSystem(uRI, Collections.emptyMap());
        } catch (FileSystemAlreadyExistsException fileSystemAlreadyExistsException) {
            // empty catch block
        }
        return Paths.get(uRI);
    }
}
