package etcodehome.freeterraforged.data.worldgen.preset.settings;

import etcodehome.freeterraforged.FTFCommon;

public class GenericBooleanSetting {
    public String settingsToken;
    public String nameToken;
    public boolean defaultValue;

    public GenericBooleanSetting(String settingsToken, String nameToken, boolean defaultValue){
        this.settingsToken = settingsToken;
        this.nameToken = FTFCommon.MOD_ID + "." + nameToken;
        this.defaultValue = defaultValue;
    }

}
