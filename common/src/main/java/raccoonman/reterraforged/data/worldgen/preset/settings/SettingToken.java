package raccoonman.reterraforged.data.worldgen.preset.settings;

public record SettingToken<T>(String id, Class<T> type) {

    public String getGroup() {
        int dotIndex = id.indexOf('.');
        return dotIndex != -1 ? id.substring(0, dotIndex) : "general";
    }
}