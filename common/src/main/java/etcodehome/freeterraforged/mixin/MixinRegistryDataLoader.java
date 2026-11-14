package etcodehome.freeterraforged.mixin;

import java.io.IOException;
import java.util.Map;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenSurfaceInputs;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RegistryDataLoader.class)
public abstract class MixinRegistryDataLoader {
	@WrapOperation(
		method = "loadRegistryContents",
		at = @At(value = "INVOKE", target = "Ljava/util/Map$Entry;getValue()Ljava/lang/Object;", ordinal = 0),
		require = 1
	)
	private static <E> Object freeterraforged$acquireSurfaceInputs(
		Map.Entry<ResourceLocation, Resource> entry,
		Operation<Object> original,
		@Local(argsOnly = true) ResourceManager manager,
		@Local(argsOnly = true) ResourceKey<? extends Registry<E>> registryKey,
		@Local(ordinal = 1) ResourceKey<E> key
	) throws IOException {
		Resource resource = (Resource) original.call(entry);
		if (registryKey.equals(Registries.NOISE_SETTINGS)) {
			return WorldgenSurfaceInputs.acquire(manager, key.location(), resource);
		}
		return resource;
	}
}
