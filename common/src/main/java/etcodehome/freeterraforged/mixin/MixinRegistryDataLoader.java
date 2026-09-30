package etcodehome.freeterraforged.mixin;

import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Decoder;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenSurfaceInputs;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RegistryDataLoader.class)
public abstract class MixinRegistryDataLoader {
	@WrapOperation(method = "loadContentsFromManager", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/resources/RegistryDataLoader;loadElementFromResource(Lnet/minecraft/core/WritableRegistry;Lcom/mojang/serialization/Decoder;Lnet/minecraft/resources/RegistryOps;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/server/packs/resources/Resource;Lnet/minecraft/core/RegistrationInfo;)V"), require = 1)
	private static <E> void freeterraforged$acquireSurfaceInputs(
		WritableRegistry<E> registry, Decoder<E> decoder, RegistryOps<JsonElement> ops, ResourceKey<E> key,
		Resource resource, RegistrationInfo registration, Operation<Void> original,
		@Local(argsOnly = true) ResourceManager manager
	) throws java.io.IOException {
		if (registry.key().equals(Registries.NOISE_SETTINGS)) {
			resource = WorldgenSurfaceInputs.acquire(manager, key.location(), resource);
		}
		original.call(registry, decoder, ops, key, resource, registration);
	}
}
