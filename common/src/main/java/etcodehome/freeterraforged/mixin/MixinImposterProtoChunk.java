package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.FTFChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;

@Mixin(ImposterProtoChunk.class)
public abstract class MixinImposterProtoChunk implements FTFChunk {

	@Inject(
		method = "<init>",
		at = @At("TAIL")
	)
	public void init(LevelChunk levelChunk, boolean bl, CallbackInfo callback) {
		FTFChunk ftfChunk = (FTFChunk) levelChunk;
		ftfChunk.getMaxHeight().ifPresent(this::setMaxHeight);
	}
}
