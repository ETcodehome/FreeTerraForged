package etcodehome.freeterraforged.mixin;

import etcodehome.freeterraforged.world.worldgen.FTFRandomState;
import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public class MixinChunkGenerator {

	@Inject(
		at = @At("HEAD"),
		method = "createStructures"
	)
	private void ftf$onStructureStarts(
		RegistryAccess registries,
		ChunkGeneratorStructureState structureState,
		StructureManager structureManager,
		ChunkAccess chunk,
		StructureTemplateManager templateManager,
		CallbackInfo ci
	) {
		RandomState randomState = structureState.randomState();
		if ((Object) randomState instanceof FTFRandomState ftfRandomState) {
			ChunkPos chunkPos = chunk.getPos();
			GeneratorContext context = ftfRandomState.generatorContext();
			if (context != null) {
				context.cache.queueAtChunk(chunkPos.x, chunkPos.z);
			}
		}
	}

	@Inject(
		at = @At("TAIL"),
		method = "applyBiomeDecoration"
	)
	private void ftf$onApplyBiomeDecoration(
		WorldGenLevel level,
		ChunkAccess chunk,
		StructureManager structureManager,
		CallbackInfo ci
	) {
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		if ((Object) randomState instanceof FTFRandomState ftfRandomState) {
			ChunkPos chunkPos = chunk.getPos();
			GeneratorContext context = ftfRandomState.generatorContext();
			if (context != null) {
				context.cache.dropAtChunk(chunkPos.x, chunkPos.z);
			}
		}
	}
}
