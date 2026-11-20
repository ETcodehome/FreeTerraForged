package etcodehome.freeterraforged.world.worldgen.cell.rivermap.wetland;

import etcodehome.freeterraforged.world.worldgen.noise.NoiseUtil;
import etcodehome.freeterraforged.world.worldgen.util.Variance;
import etcodehome.freeterraforged.data.worldgen.preset.settings.RiverSettings;

public class WetlandConfig {
    public final float chance;
    public int skipSize;
    public Variance length;
    public Variance width;
    
    public WetlandConfig(RiverSettings.Wetland settings) {
        this.chance = (settings != null) ? settings.chance : 0.0F;
        this.skipSize = Math.max(1, NoiseUtil.round((1.0F - this.chance) * 10.0F));
        this.length = (settings != null) ? Variance.of(settings.sizeMin, settings.sizeMax) : Variance.of(50, 150);
        this.width = Variance.of(50.0F, 150.0F);
    }
}
