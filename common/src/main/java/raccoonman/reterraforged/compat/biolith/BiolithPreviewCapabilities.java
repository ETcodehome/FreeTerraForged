package raccoonman.reterraforged.compat.biolith;

public final class BiolithPreviewCapabilities {
	private static final String PLACEMENT = "com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement";
	private static final String OVERWORLD = "com.terraformersmc.biolith.impl.biome.OverworldBiomePlacement";
	private static final String REPLACEMENT = PLACEMENT + "$ReplacementRequest";
	private static final String REPLACEMENT_SET = PLACEMENT + "$ReplacementRequestSet";
	private static final String SUB_BIOME = PLACEMENT + "$SubBiomeRequest";
	private static final String SUB_BIOME_SET = PLACEMENT + "$SubBiomeRequestSet";

	private BiolithPreviewCapabilities() {
	}

	public static boolean isAvailable() {
		try {
			return hasFields(PLACEMENT, "replacementNoise", "seedlets", "replacementRequests", "subBiomeRequests")
				&& hasMethods(PLACEMENT, "getReplacement", "getReplacementEntry", "getReplacementPair")
				&& hasMethods(OVERWORLD, "getLocalNoise")
				&& hasFields(REPLACEMENT, "biome", "rate", "biomeEntry", "start", "end", "fromData")
				&& hasFields(REPLACEMENT_SET, "finalized", "requests")
				&& hasFields(SUB_BIOME, "biome", "criterion")
				&& hasFields(SUB_BIOME_SET, "requests");
		} catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
			return false;
		}
	}

	private static boolean hasFields(String className, String... names) throws ReflectiveOperationException {
		Class<?> type = load(className);
		for (String name : names) {
			type.getDeclaredField(name);
		}
		return true;
	}

	private static boolean hasMethods(String className, String... names) throws ReflectiveOperationException {
		Class<?> type = load(className);
		for (String name : names) {
			boolean present = false;
			for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
				if (method.getName().equals(name)) {
					present = true;
					break;
				}
			}
			if (!present) {
				return false;
			}
		}
		return true;
	}

	private static Class<?> load(String className) throws ClassNotFoundException {
		return Class.forName(className, false, BiolithPreviewCapabilities.class.getClassLoader());
	}
}
