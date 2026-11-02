package etcodehome.freeterraforged.server.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import etcodehome.freeterraforged.FTFCommon;
import etcodehome.freeterraforged.world.worldgen.search.LocateSearchSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/** Parses the optional flags shared by terrain and biome locate commands. */
record LocateCommandOptions(
	LocateSearchSettings searchSettings,
	boolean pauseMainThread
) {
	static final String ARGUMENT = "options";

	private static final String TIMEOUT = "--timeout";
	private static final String PAUSE_MAIN_THREAD = "--pause-main-thread";
	private static final String WORKERS = "--workers";
	private static final List<String> FLAGS = List.of(TIMEOUT, PAUSE_MAIN_THREAD, WORKERS);
	private static final String KEY = FTFCommon.MOD_ID + ".commands.locate.options.";
	private static final DynamicCommandExceptionType ERROR_UNKNOWN = new DynamicCommandExceptionType(
		option -> Component.translatableWithFallback(
			KEY + "unknown", "Unknown locate option: %s.", option
		)
	);
	private static final DynamicCommandExceptionType ERROR_DUPLICATE = new DynamicCommandExceptionType(
		option -> Component.translatableWithFallback(
			KEY + "duplicate", "Locate option %s was supplied more than once.", option
		)
	);
	private static final DynamicCommandExceptionType ERROR_MISSING_VALUE = new DynamicCommandExceptionType(
		option -> Component.translatableWithFallback(
			KEY + "missing_value", "Locate option %s requires a value.", option
		)
	);
	private static final DynamicCommandExceptionType ERROR_WHOLE_NUMBER = new DynamicCommandExceptionType(
		option -> Component.translatableWithFallback(
			KEY + "whole_number", "The value for %s must be a whole number.", option
		)
	);
	private static final DynamicCommandExceptionType ERROR_TIMEOUT = new DynamicCommandExceptionType(
		value -> Component.translatableWithFallback(
			KEY + "timeout", "Locate timeout must be at least 1 second; received %s.", value
		)
	);
	private static final Dynamic2CommandExceptionType ERROR_WORKERS = new Dynamic2CommandExceptionType(
		(value, maximum) -> Component.translatableWithFallback(
			KEY + "workers", "Locate workers must be between 1 and %s; received %s.", maximum, value
		)
	);

	LocateCommandOptions {
		searchSettings = Objects.requireNonNull(searchSettings, "searchSettings");
	}

	static LocateCommandOptions defaults() {
		return new LocateCommandOptions(LocateSearchSettings.defaults(), false);
	}

	static LocateCommandOptions parse(String input) throws CommandSyntaxException {
		LocateSearchSettings defaults = LocateSearchSettings.defaults();
		long timeoutSeconds = defaults.timeoutSeconds();
		boolean pauseMainThread = false;
		int workers = defaults.workerThreads();
		List<String> tokens = tokens(input);
		Set<String> seen = new HashSet<>();

		for (int index = 0; index < tokens.size(); index++) {
			String token = tokens.get(index);
			String option = optionName(token);
			if (!FLAGS.contains(option)) {
				throw ERROR_UNKNOWN.create(token);
			}
			if (!seen.add(option)) {
				throw ERROR_DUPLICATE.create(option);
			}

			switch (option) {
				case PAUSE_MAIN_THREAD -> {
					if (!token.equals(PAUSE_MAIN_THREAD)) {
						throw ERROR_UNKNOWN.create(token);
					}
					pauseMainThread = true;
				}
				case TIMEOUT -> {
					Value value = value(token, tokens, index, TIMEOUT);
					index += value.consumedNext() ? 1 : 0;
					timeoutSeconds = parseTimeout(value.text());
				}
				case WORKERS -> {
					Value value = value(token, tokens, index, WORKERS);
					index += value.consumedNext() ? 1 : 0;
					workers = parseWorkers(value.text());
				}
				default -> throw ERROR_UNKNOWN.create(token);
			}
		}

		return new LocateCommandOptions(
			new LocateSearchSettings(timeoutSeconds, workers), pauseMainThread
		);
	}

	static CompletableFuture<Suggestions> suggest(
		CommandContext<CommandSourceStack> context,
		SuggestionsBuilder builder
	) {
		String remaining = builder.getRemaining();
		int currentStart = remaining.lastIndexOf(' ') + 1;
		String beforeCurrent = remaining.substring(0, currentStart).trim();
		List<String> completed = tokens(beforeCurrent);
		SuggestionsBuilder active = builder.createOffset(builder.getStart() + currentStart);

		if (!completed.isEmpty()) {
			String previous = completed.getLast();
			if (previous.equals(TIMEOUT)) {
				return SharedSuggestionProvider.suggest(
					List.of(Long.toString(LocateSearchSettings.DEFAULT_TIMEOUT_SECONDS)), active
				);
			}
			if (previous.equals(WORKERS)) {
				return SharedSuggestionProvider.suggest(
					List.of(Integer.toString(LocateSearchSettings.DEFAULT_WORKER_THREADS)), active
				);
			}
		}

		Set<String> used = new HashSet<>();
		for (String token : completed) {
			String option = optionName(token);
			if (FLAGS.contains(option)) {
				used.add(option);
			}
		}
		List<String> available = FLAGS.stream().filter(option -> !used.contains(option)).toList();
		return SharedSuggestionProvider.suggest(available, active);
	}

	private static List<String> tokens(String input) {
		String trimmed = input.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}
		String[] split = trimmed.split("\\s+");
		List<String> result = new ArrayList<>(split.length);
		for (String token : split) {
			if (!token.isEmpty()) {
				result.add(token);
			}
		}
		return result;
	}

	private static String optionName(String token) {
		int equals = token.indexOf('=');
		return equals < 0 ? token : token.substring(0, equals);
	}

	private static Value value(
		String token,
		List<String> tokens,
		int index,
		String option
	) throws CommandSyntaxException {
		int equals = token.indexOf('=');
		if (equals >= 0) {
			String value = token.substring(equals + 1);
			if (value.isEmpty()) {
				throw ERROR_MISSING_VALUE.create(option);
			}
			return new Value(value, false);
		}
		if (index + 1 >= tokens.size() || tokens.get(index + 1).startsWith("--")) {
			throw ERROR_MISSING_VALUE.create(option);
		}
		return new Value(tokens.get(index + 1), true);
	}

	private static long parseTimeout(String value) throws CommandSyntaxException {
		long parsed;
		try {
			parsed = Long.parseLong(value);
		} catch (NumberFormatException invalid) {
			throw ERROR_WHOLE_NUMBER.create(TIMEOUT);
		}
		if (parsed < 1L) {
			throw ERROR_TIMEOUT.create(value);
		}
		return parsed;
	}

	private static int parseWorkers(String value) throws CommandSyntaxException {
		int parsed;
		try {
			parsed = Integer.parseInt(value);
		} catch (NumberFormatException invalid) {
			throw ERROR_WHOLE_NUMBER.create(WORKERS);
		}
		if (parsed < 1 || parsed > LocateSearchSettings.MAX_WORKER_THREADS) {
			throw ERROR_WORKERS.create(value, LocateSearchSettings.MAX_WORKER_THREADS);
		}
		return parsed;
	}

	private record Value(String text, boolean consumedNext) {
	}
}
