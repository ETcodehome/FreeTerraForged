package etcodehome.freeterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;

import etcodehome.freeterraforged.world.worldgen.GeneratorContext;
import etcodehome.freeterraforged.world.worldgen.cell.continent.Continent;
import etcodehome.freeterraforged.world.worldgen.cell.continent.advanced.AdvancedContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.continent.simple.MultiContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.continent.simple.SingleContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.cell.continent.uplift.UpliftContinentGenerator;
import etcodehome.freeterraforged.world.worldgen.util.Seed;
import net.minecraft.util.StringRepresentable;

public enum ContinentType implements StringRepresentable {
    MULTI {
        
    	@Override
        public MultiContinentGenerator create(Seed seed, GeneratorContext context) {
            return new MultiContinentGenerator(seed, context);
        }
    }, 
    SINGLE {
        
    	@Override
        public SingleContinentGenerator create(Seed seed, GeneratorContext context) {
            return new SingleContinentGenerator(seed, context);
        }
    }, 
    MULTI_IMPROVED {
        
    	@Override
        public AdvancedContinentGenerator create(Seed seed, GeneratorContext context) {
            return new AdvancedContinentGenerator(seed, context);
        }
    },
    UPLIFT {

        @Override
        public UpliftContinentGenerator create(Seed seed, GeneratorContext context) {
            return new UpliftContinentGenerator(seed, context);
        }
    };
	
	public static final Codec<ContinentType> CODEC = StringRepresentable.fromEnum(ContinentType::values);
    
    public abstract Continent create(Seed seed, GeneratorContext context);

    @Override
	public String getSerializedName() {
		return this.name();
	}
}
