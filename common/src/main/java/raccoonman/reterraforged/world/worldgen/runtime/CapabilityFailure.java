package raccoonman.reterraforged.world.worldgen.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CapabilityFailure(
	String code,
	String message,
	String exceptionType,
	List<String> causeChain
) {
	public CapabilityFailure {
		code = Objects.requireNonNull(code, "code");
		message = Objects.requireNonNull(message, "message");
		exceptionType = Objects.requireNonNull(exceptionType, "exceptionType");
		causeChain = List.copyOf(causeChain);
	}

	public static CapabilityFailure of(String code, Throwable error) {
		Objects.requireNonNull(error, "error");
		List<String> causes = new ArrayList<>();
		Throwable current = error;
		while (current != null && causes.size() < 16) {
			causes.add(current.getClass().getName() + ": " + message(current));
			current = current.getCause();
		}
		return new CapabilityFailure(code, message(error), error.getClass().getName(), causes);
	}

	public static CapabilityFailure unavailable(String code, String message) {
		return new CapabilityFailure(code, message, "", List.of());
	}

	private static String message(Throwable error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}
}
