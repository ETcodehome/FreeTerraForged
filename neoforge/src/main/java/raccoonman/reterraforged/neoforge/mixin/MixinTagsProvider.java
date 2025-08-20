package raccoonman.reterraforged.neoforge.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.ExistingFileHelper.IResourceType;

@Mixin(TagsProvider.class)
class MixinTagsProvider {

	@Redirect( //TODO
		remap = false,
		method = "getOrCreateRawBuilder",
		at = @At(
			remap = false,
			value = "INVOKE",
			target = "Lnet/neoforged/neoforge/common/data/ExistingFileHelper;trackGenerated(Lnet/minecraft/resources/ResourceLocation;Lnet/neoforged/neoforge/common/data/ExistingFileHelper$IResourceType;)V"
		)
	)
	void trackGenerated(@Nullable ExistingFileHelper fileHelper, ResourceLocation loc, IResourceType type) {
		// what the hell forge
		// they even annotated it with @Nullable
		if(fileHelper != null) {
			fileHelper.trackGenerated(loc, type);
		}
	}
}
