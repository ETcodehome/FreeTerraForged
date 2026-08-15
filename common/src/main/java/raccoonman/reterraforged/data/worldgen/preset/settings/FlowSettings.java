package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class FlowSettings {

    // member settings
    public boolean flowParticles;
    public boolean boatFlowDynamics;
    public boolean navigableWaterfalls;

    // serialization handlers for codec
    private boolean serializeFlowParticles() { return this.flowParticles; }
    private boolean serializeBoatFlowDynamics() { return this.boatFlowDynamics; }
    private boolean serializeNavigableWaterfalls() { return this.navigableWaterfalls; }

    // Codec for writing to disk
    public static final Codec<FlowSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("flowParticles").orElse(true).forGetter(FlowSettings::serializeFlowParticles),
            Codec.BOOL.fieldOf("boatFlowDynamics").orElse(true).forGetter(FlowSettings::serializeBoatFlowDynamics),
            Codec.BOOL.fieldOf("navigableWaterfalls").orElse(true).forGetter(FlowSettings::serializeNavigableWaterfalls)
    ).apply(instance, FlowSettings::new));

    // default constructor
    public FlowSettings() {
        this(true, true, true);
    }

    // value constructor
    public FlowSettings(boolean flowParticles, boolean boatFlowDynamics, boolean navigableWaterfalls) {
        this.flowParticles = flowParticles;
        this.boatFlowDynamics = boatFlowDynamics;
        this.navigableWaterfalls = navigableWaterfalls;
    }

    // explicit default constructor used by presets
    public static FlowSettings makeDefault() {
        return new FlowSettings(true, true, true);
    }

    // Allow settings duplication
    public FlowSettings copy() {
        return new FlowSettings(
            this.flowParticles,
            this.boatFlowDynamics,
            this.navigableWaterfalls
        );
    }

    // Static cache of settings of current preset, used by mixins
    public class CurrentPresetState {
        private static FlowSettings currentSettings = new FlowSettings();

        public static FlowSettings get() {
            return currentSettings;
        }

        public static void set(FlowSettings settings) {
            currentSettings = settings != null ? settings : new FlowSettings();
        }
    }
    public static boolean enableFlowParticles() { return CurrentPresetState.currentSettings.flowParticles; }
    public static boolean enableBoatFlowDynamics() { return CurrentPresetState.currentSettings.boatFlowDynamics; }
    public static boolean enableNavigableWaterfalls() { return CurrentPresetState.currentSettings.navigableWaterfalls; }
}