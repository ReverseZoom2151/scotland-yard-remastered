package uk.ac.bris.cs.scotlandyard.ui.ai.arena;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.ui.ai.GreedyAi;
import uk.ac.bris.cs.scotlandyard.ui.ai.MyAi;
import uk.ac.bris.cs.scotlandyard.ui.ai.RandomAi;

/**
 * The arena's command line:
 *
 * <pre>
 * mvnw exec:java@arena -Dexec.args="--games=100 --mrx=MyAi --detectives=RandomAi"
 * </pre>
 *
 * <p>
 * The AI names are resolved from a hardcoded table rather than by scanning the
 * classpath. A benchmark that quietly picks up whatever happens to be on the
 * classpath is a benchmark you cannot reproduce.
 */
public final class ArenaMain {

	private ArenaMain() {
	}

	/** Every AI the arena knows how to field. */
	private static final Map<String, Supplier<Ai>> AIS = buildRoster();

	private static Map<String, Supplier<Ai>> buildRoster() {
		final Map<String, Supplier<Ai>> roster = new LinkedHashMap<>();
		roster.put("MyAi", MyAi::new);
		roster.put("GreedyAi", GreedyAi::new);
		roster.put("RandomAi", RandomAi::new);
		return roster;
	}

	public static void main(String[] args) throws IOException {
		final Map<String, String> options = parse(args);

		final int games = intOption(options, "games", 50);
		final long budget = longOption(options, "budget", 500);
		final long seed = longOption(options, "seed", 1);
		final String mrXName = options.getOrDefault("mrx", "MyAi");
		final String detectiveName = options.getOrDefault("detectives", "MyAi");
		final String csv = options.get("csv");

		final Supplier<Ai> mrX = resolve(mrXName);
		final Supplier<Ai> detectives = resolve(detectiveName);

		System.out.printf("Scotland Yard arena: %s (Mr X) vs %s (detectives)%n",
				mrXName, detectiveName);
		System.out.printf("games=%d budget=%dms seed=%d%n%n", games, budget, seed);

		final long began = System.nanoTime();
		final List<GameResult> results = new Arena(mrX, detectives, budget).playMany(games, seed);
		final long elapsedSeconds = (System.nanoTime() - began) / 1_000_000_000L;

		System.out.println(Arena.summary(results));
		System.out.printf("wall clock            %d s%n", elapsedSeconds);

		if (csv != null) write(Path.of(csv), results);
	}

	private static void write(Path path, List<GameResult> results) throws IOException {
		final Path parent = path.toAbsolutePath().getParent();
		if (parent != null) Files.createDirectories(parent);
		try (PrintWriter out = new PrintWriter(
				Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
			out.println(GameResult.csvHeader());
			for (GameResult result : results) out.println(result.toCsvRow());
		}
		System.out.printf("%nwrote %d rows to %s%n", results.size(), path.toAbsolutePath());
	}

	private static Supplier<Ai> resolve(String name) {
		final Supplier<Ai> ai = AIS.get(name);
		if (ai == null) {
			throw new IllegalArgumentException(
					"unknown AI '" + name + "'; known AIs are " + AIS.keySet());
		}
		return ai;
	}

	/** Accepts {@code --key=value}; anything else is a usage error, loudly. */
	private static Map<String, String> parse(String[] args) {
		final Map<String, String> options = new LinkedHashMap<>();
		for (String arg : args) {
			if (!arg.startsWith("--") || !arg.contains("=")) {
				throw new IllegalArgumentException("expected --key=value, got '" + arg + "'");
			}
			final int split = arg.indexOf('=');
			options.put(arg.substring(2, split), arg.substring(split + 1));
		}
		return options;
	}

	private static int intOption(Map<String, String> options, String key, int fallback) {
		final String value = options.get(key);
		return value == null ? fallback : Integer.parseInt(value);
	}

	private static long longOption(Map<String, String> options, String key, long fallback) {
		final String value = options.get(key);
		return value == null ? fallback : Long.parseLong(value);
	}
}
