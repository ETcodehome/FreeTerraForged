package etcodehome.freeterraforged.data.worldgen.preset.settings;

import etcodehome.freeterraforged.client.data.FTFTranslationKeys;

public class GenericFloatSetting {
    public String settingsToken;
    public String resolvedToken;
    public float softMin;
    public float softMax;
    public float defaultValue;

    public GenericFloatSetting(String settingsToken, String rawToken, float softMin, float softMax, float defaultValue){
        this.settingsToken = settingsToken;
        this.resolvedToken = FTFTranslationKeys.resolve(rawToken);
        this.softMin = softMin;
        this.softMax = softMax;
        this.defaultValue = defaultValue;
    }

}
