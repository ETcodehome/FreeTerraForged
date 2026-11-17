package etcodehome.freeterraforged.data.worldgen.tags;

import java.util.concurrent.CompletableFuture;

import etcodehome.freeterraforged.data.worldgen.preset.settings.Preset;
import etcodehome.freeterraforged.tags.FTFBlockTags;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.IntrinsicHolderTagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class FTFBlockTagsProvider extends IntrinsicHolderTagsProvider<Block> {
	private Preset preset;
	
	public FTFBlockTagsProvider(Preset preset, PackOutput packOutput, CompletableFuture<Provider> completableFuture) {
		super(packOutput, Registries.BLOCK, completableFuture, (block) -> BuiltInRegistries.BLOCK.getResourceKey(block).orElseThrow());

		this.preset = preset;
	}

	@Override
	protected void addTags(HolderLookup.Provider provider) {
//		MiscellaneousSettings miscellaneousSettings = this.preset.miscellaneous();

		this.tag(FTFBlockTags.SOIL).add(Blocks.DIRT, Blocks.COARSE_DIRT);
		this.tag(FTFBlockTags.CLAY).add(Blocks.CLAY);
		this.tag(FTFBlockTags.SEDIMENT).add(Blocks.SAND, Blocks.GRAVEL);
		this.tag(FTFBlockTags.ERODIBLE).add(Blocks.SNOW_BLOCK).add(Blocks.POWDER_SNOW).add(Blocks.GRAVEL).addOptionalTag(BlockTags.DIRT.location());
		// Small plants that the opt-in "snow covers small plants" setting may replace with smoothed snow.
		// minecraft:wildflowers is vanilla from 1.21.5 and is also registered under the minecraft namespace by backport mods on 1.21.1.
		this.tag(FTFBlockTags.SNOW_COVERABLE)
			.add(Blocks.SHORT_GRASS, Blocks.FERN, Blocks.PINK_PETALS)
			.addOptionalTag(BlockTags.SMALL_FLOWERS.location())
			.addOptional(ResourceLocation.withDefaultNamespace("wildflowers"));
		
//		if(!miscellaneousSettings.oreCompatibleStoneOnly) {
			this.tag(FTFBlockTags.ROCK).add(Blocks.GRANITE, Blocks.ANDESITE, Blocks.STONE, Blocks.DIORITE);
//		} else{
			//TODO
//		}
	}
}
