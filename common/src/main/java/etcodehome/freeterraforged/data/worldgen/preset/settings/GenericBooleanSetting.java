package etcodehome.freeterraforged.data.worldgen.preset.settings;

import etcodehome.freeterraforged.client.data.FTFTranslationKeys;

public class GenericBooleanSetting {
    public String settingsToken;
    public String resolvedToken;
    public boolean defaultValue;

    public GenericBooleanSetting(String settingsToken, String rawToken, boolean defaultValue){
        this.settingsToken = settingsToken;
        this.resolvedToken = FTFTranslationKeys.resolve(rawToken);
        this.defaultValue = defaultValue;
    }

}
