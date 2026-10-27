package etcodehome.freeterraforged.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import etcodehome.freeterraforged.platform.ModLoaderUtil;

/** The code-listener bridge is applicable only while its complete Mixin seam still exists. */
public final class LithostitchedBridgeContract {
	private static final String PREFIX = "dev/worldgen/lithostitched/";
	private static final String EVENT = PREFIX + "impl/event/LithostitchedEvent";
	private static final String MANAGER = PREFIX
		+ "impl/worldgen/biomeinjector/internal/BiomeInjectorManager";
	private static final String INJECTOR = PREFIX
		+ "impl/worldgen/biomeinjector/internal/InjectorBiomeSource";
	private static final String ACCESSOR = PREFIX + "mixin/common/ChunkGeneratorAccessor";
	private static final String SOURCE_INVOKER = PREFIX + "mixin/common/BiomeSourceInvoker";
	private static final List<String> FINALIZER_CALLS = List.of(
		"event.invoker", "event.invoker", "generator.getBiomeSource", "source.getCodec",
		"generator.getBiomeSource", "injector.applyInjectors", "generator.setBiomeSource",
		"generator.setFeaturesPerStep"
	);
	private static volatile Assessment cached;

	private LithostitchedBridgeContract() {
	}

	public static Assessment current() {
		if (!ModLoaderUtil.isLoaded("lithostitched")) {
			return new Assessment(false, "Lithostitched is not installed");
		}
		Assessment result = cached;
		if (result == null) {
			synchronized (LithostitchedBridgeContract.class) {
				result = cached;
				if (result == null) {
					try {
						result = inspect(
							readClassNode(MANAGER), readClassNode(EVENT)
						);
					} catch (IOException | RuntimeException | LinkageError failure) {
						result = new Assessment(false,
							"Lithostitched bridge bytecode is unavailable: " + failure.getClass().getSimpleName()
								+ ": " + failure.getMessage());
					}
					cached = result;
				}
			}
		}
		return result;
	}

	private static ClassNode readClassNode(String name) throws IOException {
		try (InputStream resource = LithostitchedBridgeContract.class.getClassLoader()
			.getResourceAsStream(name + ".class")) {
			if (resource == null) {
				throw new IOException("Class resource missing: " + name);
			}
			ClassNode node = new ClassNode();
			new ClassReader(resource).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		}
	}

	static Assessment inspect(ClassNode manager, ClassNode event) {
		if (manager == null || event == null || !MANAGER.equals(manager.name) || !EVENT.equals(event.name)) {
			return new Assessment(false, "Lithostitched finalizer or event target changed");
		}
		if (method(event, "register", "(Ljava/lang/Object;)V") == null
			|| method(event, "invoker", "()Ljava/lang/Object;") == null) {
			return new Assessment(false, "Lithostitched event registration or invocation changed");
		}
		MethodNode finalizer = manager.methods.stream()
			.filter(value -> "applyBiomeInjectors".equals(value.name) && finalizerDescriptor(value.desc))
			.findFirst().orElse(null);
		if (finalizer == null) {
			return new Assessment(false, "Lithostitched finalizer entry point changed");
		}
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode instruction : finalizer.instructions) {
			if (instruction instanceof MethodInsnNode call) {
				String seam = seamCall(call);
				if (seam != null) {
					calls.add(seam);
				}
			}
		}
		return calls.equals(FINALIZER_CALLS)
			? new Assessment(true, "Lithostitched event and finalizer seam matches")
			: new Assessment(false, "Lithostitched finalizer call order or signatures changed: " + calls);
	}

	private static MethodNode method(ClassNode owner, String name, String descriptor) {
		return owner.methods.stream()
			.filter(value -> name.equals(value.name) && descriptor.equals(value.desc))
			.findFirst().orElse(null);
	}

	private static boolean finalizerDescriptor(String descriptor) {
		Type method = Type.getMethodType(descriptor);
		Type[] arguments = method.getArgumentTypes();
		return arguments.length == 3
			&& arguments[0].getSort() == Type.OBJECT
			&& arguments[1].getSort() == Type.OBJECT
			&& arguments[2].getSort() == Type.LONG
			&& method.getReturnType().getSort() == Type.VOID;
	}

	private static String seamCall(MethodInsnNode call) {
		if (EVENT.equals(call.owner) && "invoker".equals(call.name)) {
			return "()Ljava/lang/Object;".equals(call.desc) ? "event.invoker" : "invalid event.invoker";
		}
		if (ACCESSOR.equals(call.owner)) {
			return switch (call.name) {
				case "getBiomeSource" -> objectGetter(call.desc)
					? "generator.getBiomeSource" : "invalid generator.getBiomeSource";
				case "setBiomeSource" -> objectSetter(call.desc)
					? "generator.setBiomeSource" : "invalid generator.setBiomeSource";
				case "setFeaturesPerStep" -> "(Ljava/util/function/Supplier;)V".equals(call.desc)
					? "generator.setFeaturesPerStep" : "invalid generator.setFeaturesPerStep";
				default -> null;
			};
		}
		if (SOURCE_INVOKER.equals(call.owner) && "getCodec".equals(call.name)) {
			return "()Lcom/mojang/serialization/MapCodec;".equals(call.desc)
				? "source.getCodec" : "invalid source.getCodec";
		}
		if (INJECTOR.equals(call.owner) && "applyInjectors".equals(call.name)) {
			return ("(Ljava/util/Map;Ljava/util/Optional;Ljava/util/Map;L" + PREFIX
				+ "api/worldgen/util/DensityFunctionWrapper;)V").equals(call.desc)
				? "injector.applyInjectors" : "invalid injector.applyInjectors";
		}
		return null;
	}

	private static boolean objectGetter(String descriptor) {
		Type type = Type.getMethodType(descriptor);
		return type.getArgumentTypes().length == 0 && type.getReturnType().getSort() == Type.OBJECT;
	}

	private static boolean objectSetter(String descriptor) {
		Type type = Type.getMethodType(descriptor);
		return type.getArgumentTypes().length == 1
			&& type.getArgumentTypes()[0].getSort() == Type.OBJECT
			&& type.getReturnType().getSort() == Type.VOID;
	}

	public record Assessment(boolean supported, String reason) {
	}
}
