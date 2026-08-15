package raccoonman.reterraforged.mixin;

import java.util.OptionalInt;

import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import raccoonman.reterraforged.world.worldgen.IFlowFieldHolder;
import raccoonman.reterraforged.world.worldgen.RTFChunk;
import raccoonman.reterraforged.world.worldgen.ChunkFlowField;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess implements RTFChunk, LevelHeightAccessor, IFlowFieldHolder {

	@Unique
	private final ChunkFlowField reterraforged$flowField = new ChunkFlowField();

	@Override
	public ChunkFlowField reterraforged$getFlowField() {
		return this.reterraforged$flowField;
	}

	private OptionalInt maxHeight = OptionalInt.empty();

	@Override
	public void setMaxHeight(int maxHeight) {
		this.maxHeight = OptionalInt.of(maxHeight);
	}

	@Override
	public OptionalInt getMaxHeight() {
		return this.maxHeight;
	}
	
	@Inject(
		method = "fillBiomesFromNoise",
		at = @At("HEAD"),
		cancellable = true
	)
	public void fillBiomesFromNoise(BiomeResolver biomeResolver, Climate.Sampler sampler, CallbackInfo callback) {
		if(this.maxHeight.isEmpty()) {
			return;
		}
		
		ChunkPos chunkPos = this.getPos();
		int quartX = QuartPos.fromBlock(chunkPos.getMinBlockX());
		int quartZ = QuartPos.fromBlock(chunkPos.getMinBlockZ());
		LevelHeightAccessor levelHeightAccessor = this.getHeightAccessorForGeneration();
		
		int maxHeight = this.maxHeight.getAsInt();
		int highestSectionY = SectionPos.blockToSectionCoord(maxHeight) + 1;
		int maxSectionY = levelHeightAccessor.getMaxSection();

		for (int sectionY = levelHeightAccessor.getMinSection(); sectionY < Math.min(highestSectionY + 1, maxSectionY); sectionY++) {
			LevelChunkSection levelChunkSection = this.getSection(this.getSectionIndexFromSectionY(sectionY));
			int quartY = QuartPos.fromSection(sectionY);
			levelChunkSection.fillBiomesFromNoise(biomeResolver, sampler, quartX, quartY, quartZ);
		}
		
		if(highestSectionY < maxSectionY) {
			LevelChunkSection highestChunkSection = this.getSection(this.getSectionIndexFromSectionY(highestSectionY));
			PalettedContainer<Holder<Biome>> highestBiomes = (PalettedContainer<Holder<Biome>>) highestChunkSection.getBiomes();
			
			for(int sectionY = highestSectionY; sectionY < maxSectionY; sectionY++) {
				LevelChunkSection levelChunkSection = this.getSection(this.getSectionIndexFromSectionY(sectionY));
				LevelChunkSectionAccessor accessor = (LevelChunkSectionAccessor) levelChunkSection;
				accessor.setBiomes(highestBiomes.copy());
			}
		}
		callback.cancel();
	}

	@Shadow
	public abstract LevelHeightAccessor getHeightAccessorForGeneration();

	@Shadow
	public abstract ChunkPos getPos();
	
	@Shadow
	public abstract LevelChunkSection getSection(int i);
}
