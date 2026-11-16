package etcodehome.freeterraforged.data.worldgen.preset.settings;

import etcodehome.freeterraforged.client.data.FTFTranslationKeys;

public class GenericIntSetting {
    public String settingsToken;
    public String resolvedToken;
    public int softMin;
    public int softMax;
    public int defaultValue;

    public GenericIntSetting(String settingsToken, String rawToken, int softMin, int softMax, int defaultValue){
        this.settingsToken = settingsToken;
        this.resolvedToken = FTFTranslationKeys.resolve(rawToken);
        this.softMin = softMin;
        this.softMax = softMax;
        this.defaultValue = defaultValue;
    }

}
