package raccoonman.reterraforged.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Mixin(InSquarePlacement.class)
public class MixinSquarePlacement {

    // Identity fast-path caches: Graph traversal runs ONCE per feature type
    @Unique
    private static final Set<Holder<?>> KNOWN_UNSAFE_FEATURES = Collections.newSetFromMap(new ConcurrentHashMap<>());
    @Unique
    private static final Set<Holder<?>> KNOWN_SAFE_FEATURES = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
    private void clampOnlyProblematicFeatures(
            PlacementContext context,
            RandomSource random,
            BlockPos pos,
            CallbackInfoReturnable<Stream<BlockPos>> cir
    ) {
        if (context.topFeature().isEmpty()) {
            return;
        }

        PlacedFeature placedFeature = context.topFeature().get();
        Holder<ConfiguredFeature<?, ?>> featureHolder = placedFeature.feature();

        // 1. FAST PATH: Identity cache lookup (~1ns execution, zero string ops/reflection)
        if (KNOWN_SAFE_FEATURES.contains(featureHolder)) {
            return;
        }

        if (KNOWN_UNSAFE_FEATURES.contains(featureHolder)) {
            clampPosition(random, pos, cir);
            return;
        }

        // 2. SLOW PATH: Walk object graph of Placement Modifiers AND Feature Configuration (runs ONCE)
        if (isUnsafeGeneric(placedFeature)) {
            KNOWN_UNSAFE_FEATURES.add(featureHolder);
            clampPosition(random, pos, cir);
        } else {
            KNOWN_SAFE_FEATURES.add(featureHolder);
        }
    }

    @Unique
    private static boolean isUnsafeGeneric(PlacedFeature placedFeature) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        // Check Placement Modifiers (random_offset, block_predicate_filter, etc.)
        for (PlacementModifier mod : placedFeature.placement()) {
            if (inspectObjectGraph(mod, visited, 0)) {
                return true;
            }
        }

        // Check Configured Feature Configuration (VegetationPatch, CliffDecorators, custom mod configs)
        Holder<ConfiguredFeature<?, ?>> holder = placedFeature.feature();
        if (holder.isBound()) {
            ConfiguredFeature<?, ?> cf = holder.value();
            if (inspectObjectGraph(cf.config(), visited, 0)) {
                return true;
            }
        }

        return false;
    }

    @Unique
    private static boolean inspectObjectGraph(Object obj, Set<Object> visited, int depth) {
        if (obj == null || depth > 5 || !visited.add(obj)) {
            return false;
        }

        // Rule 1: Check Vec3i / BlockPos for non-zero X or Z offsets (e.g., predicate offset [-2, 0, 0])
        if (obj instanceof Vec3i vec) {
            if (vec.getX() != 0 || vec.getZ() != 0) {
                return true;
            }
        }

        // Rule 2: Skip java built-ins, primitive wrappers, and enums
        Class<?> clazz = obj.getClass();
        String packageName = clazz.getPackageName();
        if (packageName.startsWith("java.lang") || packageName.startsWith("java.math") || clazz.isEnum()) {
            return false;
        }

        // Rule 3: Unwrap common wrappers, lists, optionals, and arrays
        if (obj instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (inspectObjectGraph(item, visited, depth + 1)) return true;
            }
            return false;
        }
        if (obj instanceof Optional<?> opt) {
            return opt.isPresent() && inspectObjectGraph(opt.get(), visited, depth + 1);
        }
        if (obj instanceof Holder<?> h) {
            return h.isBound() && inspectObjectGraph(h.value(), visited, depth + 1);
        }
        if (clazz.isArray()) {
            int len = java.lang.reflect.Array.getLength(obj);
            for (int i = 0; i < len; i++) {
                if (inspectObjectGraph(java.lang.reflect.Array.get(obj, i), visited, depth + 1)) return true;
            }
            return false;
        }

        // Rule 4: Recursively inspect object fields (Records, Configs, Predicates)
        for (Field field : getDeclaredFields(clazz)) {
            try {
                field.setAccessible(true);
                Object val = field.get(obj);
                if (val != null) {
                    // Check if field name implies horizontal spread/offset and holds a non-zero IntProvider
                    String name = field.getName().toLowerCase(Locale.ROOT);
                    if ((name.contains("xz") || name.contains("offset") || name.contains("spread") || name.contains("radius"))
                            && val instanceof IntProvider ip) {
                        if (ip.getMinValue() != 0 || ip.getMaxValue() != 0) {
                            return true;
                        }
                    }

                    if (inspectObjectGraph(val, visited, depth + 1)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }

        return false;
    }

    @Unique
    private static List<Field> getDeclaredFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class && !current.getPackageName().startsWith("java.lang")) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    @Unique
    private static void clampPosition(RandomSource random, BlockPos pos, CallbackInfoReturnable<Stream<BlockPos>> cir) {
        int safeX = 2 + random.nextInt(12);
        int safeZ = 2 + random.nextInt(12);
        cir.setReturnValue(Stream.of(pos.offset(safeX, 0, safeZ)));
    }
}