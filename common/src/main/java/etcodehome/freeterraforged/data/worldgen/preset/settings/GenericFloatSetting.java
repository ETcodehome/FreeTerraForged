package etcodehome.freeterraforged.data.worldgen.preset.settings;

import etcodehome.freeterraforged.FTFCommon;

public class GenericFloatSetting {
    public String settingsToken;
    public String nameToken;
    public float softMin;
    public float softMax;
    public float defaultValue;

    public GenericFloatSetting(String settingsToken, String nameToken, float softMin, float softMax, float defaultValue){
        this.settingsToken = settingsToken;
        this.nameToken = FTFCommon.MOD_ID + "." + nameToken;
        this.softMin = softMin;
        this.softMax = softMax;
        this.defaultValue = defaultValue;
    }

}
