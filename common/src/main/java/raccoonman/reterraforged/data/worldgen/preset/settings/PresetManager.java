package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.google.gson.*;
import raccoonman.reterraforged.RTFCommon;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PresetManager {
    private static final PresetManager INSTANCE = new PresetManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<SettingToken<?>, SettingDefinition<?>> registry = new LinkedHashMap<>();
    private final Map<SettingToken<?>, Object> currentValues = new HashMap<>();
    private static final Map<String, MigrationHandler> migrationRegistry = new HashMap<>();
    public static PresetManager getInstance() {
        return INSTANCE;
    }

    static {
        registerLegacyMigrations();
    }

    public <T> SettingDefinition<T> getDefinition(SettingToken<T> token) {
        return (SettingDefinition<T>) registry.get(token);
    }

    public static SettingToken<Boolean> registerToggle(String id, boolean defaultValue, String tk) {
        return register(id, SettingKind.TOGGLE, defaultValue, null, null, null, tk);
    }

    public static SettingToken<Float> registerFloat(String id, float defaultValue, float min, float max, String tk) {
        return register(id, SettingKind.SLIDER_FLOAT, defaultValue, min, max, null, tk);
    }

    public static SettingToken<Integer> registerInt(String id, int defaultValue, int min, int max, String tk) {
        return register(id, SettingKind.SLIDER_INT, defaultValue, min, max, null, tk);
    }

    public static SettingToken<String> registerCycle(String id, List<String> choices, String defaultValue, String tk) {
        return register(id, SettingKind.CYCLE, defaultValue, null, null, choices, tk);
    }

    public static <T> SettingToken<T> register(String id, SettingKind kind, T defaultValue, T min, T max, List<T> choices, String tk) {
        SettingToken<T> token = new SettingToken<>(id, (Class<T>) defaultValue.getClass());
        SettingDefinition<T> definition = new SettingDefinition<>(token, kind, defaultValue, min, max, choices, tk);
        INSTANCE.registry.put(token, definition);
        INSTANCE.currentValues.put(token, defaultValue);
        return token;
    }

    public Collection<SettingDefinition<?>> getDefinitions() {
        return registry.values();
    }

    // ==========================================
    // 3. STATE QUERIES & MUTATION (GLOBAL MEMORY)
    // ==========================================
    public <T> T get(SettingToken<T> token) {
        Object val = currentValues.get(token);
        if (val == null) {
            SettingDefinition<T> def = (SettingDefinition<T>) registry.get(token);
            return def.defaultValue();
        }
        return token.type().cast(val);
    }

    public <T> void set(SettingToken<T> token, T value) {
        SettingDefinition<T> def = (SettingDefinition<T>) registry.get(token);
        if (def != null) {
            currentValues.put(token, def.clamp(value));
        }
    }

    public void resetToDefaults() {
        currentValues.clear();
        for (Map.Entry<SettingToken<?>, SettingDefinition<?>> entry : registry.entrySet()) {
            currentValues.put(entry.getKey(), entry.getValue().defaultValue());
        }
    }

    // ==========================================
    // 4. STORAGE: FORCE WRITE ALL & LAZY READ
    // ==========================================
    public void save(Path path) throws IOException {
        Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
        JsonObject json = new JsonObject();

        // Guaranteed loop in sequential declaration order
        for (SettingToken<?> token : registry.keySet()) {
            Object value = currentValues.get(token);
            if (value instanceof Boolean b) json.addProperty(token.id(), b);
            else if (value instanceof Number n) json.addProperty(token.id(), n);
            else if (value instanceof String s) json.addProperty(token.id(), s);
        }

        try (Writer writer = Files.newBufferedWriter(tempPath)) {
            GSON.toJson(json, writer);
        }
        Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
    }

    public void loadFromFile(Path path) throws IOException {

        if (!Files.exists(path)) {
            resetToDefaults();
            return;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                resetToDefaults();
                return;
            }
            loadFromJson(root.getAsJsonObject());
        }
    }

    public void printState(){
        RTFCommon.LOGGER.info(PresetManager.getInstance().toString());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("PresetManager State:\n");
        sb.append("====================\n");

        for (Map.Entry<SettingToken<?>, SettingDefinition<?>> entry : registry.entrySet()) {
            SettingToken<?> token = entry.getKey();
            Object value = currentValues.get(token);

            sb.append(String.format("%-30s : %s%n", token.id(), value));
        }

        return sb.toString();
    }

    @FunctionalInterface
    public interface MigrationHandler {
        void migrate(JsonObject json);
    }

    private static void registerLegacyMigrations() {
        // Register handlers for each top-level JSON category
        migrationRegistry.put("island", IslandSettings::legacyMigration);
    }

    private void runLegacyMigrationAdapter(JsonObject json) {
        for (Map.Entry<String, MigrationHandler> entry : migrationRegistry.entrySet()) {
            if (json.has(entry.getKey())) {
                entry.getValue().migrate(json);
            }
        }
    }

    public void loadFromJson(JsonObject json) {
        this.runLegacyMigrationAdapter(json);
        this.applyModernParser(json);
    }

    private void applyModernParser(JsonObject json) {
        for (Map.Entry<SettingToken<?>, SettingDefinition<?>> entry : registry.entrySet()) {
            SettingToken<?> token = entry.getKey();
            SettingDefinition<?> def = entry.getValue();

            if (json.has(token.id())) {
                JsonElement element = json.get(token.id());
                if (token.type() == Boolean.class) set((SettingToken<Boolean>) token, element.getAsBoolean());
                else if (token.type() == Float.class) set((SettingToken<Float>) token, element.getAsFloat());
                else if (token.type() == Integer.class) set((SettingToken<Integer>) token, element.getAsInt());
                else if (token.type() == String.class) set((SettingToken<String>) token, element.getAsString());
            } else {
                // Lazy Fallback: populate with defaults if configuration lacks the field
                currentValues.put(token, def.defaultValue());
            }
        }
    }

}