package uk.ac.bris.cs.scotlandyard.ui.ai.arena;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.ui.ai.EvalWeights;
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

		if (options.containsKey("sweep")) {
			sweep(options, games, budget, seed, detectiveName);
			return;
		}

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

	/**
	 * The ablation sweep:
	 *
	 * <pre>
	 * mvnw exec:java@arena -Dexec.args="--sweep=mrx --games=30 --detectives=GreedyAi"
	 * </pre>
	 *
	 * <p>
	 * Every variant plays the <i>same</i> games — same seed, same openings — against the
	 * <i>same</i> fixed opponent, and each one differs from the baseline in exactly one
	 * knob. That is what makes the resulting table an ablation rather than a leaderboard:
	 * a row that drops tells you the knob it turned off was doing work, and a row that
	 * does not tells you the knob was decoration.
	 *
	 * @param side {@code --sweep=mrx} sweeps Mr X's weights; anything else sweeps the
	 *             detectives'
	 */
	private static void sweep(Map<String, String> options, int games, long budget, long seed,
			String opponentName) throws IOException {
		final String side = options.getOrDefault("sweep", "mrx");
		final boolean variantIsMrX = !"detectives".equalsIgnoreCase(side);
		final Supplier<Ai> opponent = resolve(opponentName);

		System.out.printf("Scotland Yard arena sweep: %s weights vs a fixed %s%n",
				variantIsMrX ? "Mr X" : "detective", opponentName);
		System.out.printf("games=%d per variant, budget=%dms seed=%d%n%n", games, budget, seed);

		final long began = System.nanoTime();
		final List<Arena.SweepRow> rows =
				Arena.sweep(variants(), opponent, variantIsMrX, games, seed, budget);
		final long elapsedSeconds = (System.nanoTime() - began) / 1_000_000_000L;

		System.out.println(Arena.sweepTable(rows, variantIsMrX));
		System.out.printf("wall clock            %d s%n", elapsedSeconds);
	}

	/**
	 * The baseline and its one-knob ablations. Bound directly to
	 * {@link EvalWeights} — the weight vector really is the thing being varied, not a
	 * proxy for it.
	 */
	private static List<Arena.Variant> variants() {
		final EvalWeights base = EvalWeights.defaults();
		final List<Arena.Variant> variants = new ArrayList<>();
		variants.add(variant("baseline", base));
		variants.add(variant("no-belief", withEntropyAlpha(base, 0)));
		variants.add(variant("no-gates", withGateMoves(base, false)));
		variants.add(variant("no-beliefSearch", withBeliefSearch(base, false)));
		variants.add(variant("hump-prior", withHumpPrior(base, true)));
		variants.add(variant("no-coverage", withCoverage(base, false)));
		variants.add(variant("freedom-off", withFreedomWeight(base, 0)));
		variants.add(variant("cap-6", withDistanceCap(base, 6)));
		return variants;
	}

	private static Arena.Variant variant(String name, EvalWeights weights) {
		return new Arena.Variant(name, Arena.weighted(weights));
	}

	// EvalWeights is a record with twelve components and no wither methods; these keep the
	// variant list above readable, and are the only place the component order is spelled out.
	private static EvalWeights withEntropyAlpha(EvalWeights w, double entropyAlpha) {
		return new EvalWeights(w.nearestWeight(), w.restWeight(), w.distanceCap(),
				w.useHumpPrior(), w.freedomWeight(), w.freedomCap(), w.revealFreedomBoost(),
				entropyAlpha, w.beliefSearch(), w.gateMoves(), w.rootTieBand(),
				w.detectiveCoverage());
	}

	private static EvalWeights withGateMoves(EvalWeights w, boolean gateMoves) {
		return new EvalWeights(w.nearestWeight(), w.restWeight(), w.distanceCap(),
				w.useHumpPrior(), w.freedomWeight(), w.freedomCap(), w.revealFreedomBoost(),
				w.entropyAlpha(), w.beliefSearch(), gateMoves, w.rootTieBand(),
				w.detectiveCoverage());
	}

	private static EvalWeights withBeliefSearch(EvalWeights w, boolean beliefSearch) {
		return new EvalWeights(w.nearestWeight(), w.restWeight(), w.distanceCap(),
				w.useHumpPrior(), w.freedomWeight(), w.freedomCap(), w.revealFreedomBoost(),
				w.entropyAlpha(), beliefSearch, w.gateMoves(), w.rootTieBand(),
				w.detectiveCoverage());
	}

	private static EvalWeights withHumpPrior(EvalWeights w, boolean useHumpPrior) {
		return new EvalWeights(w.nearestWeight(), w.restWeight(), w.distanceCap(),
				useHumpPrior, w.freedomWeight(), w.freedomCap(), w.revealFreedomBoost(),
				w.entropyAlpha(), w.beliefSearch(), w.gateMoves(), w.rootTieBand(),
				w.detectiveCoverage());
	}

	private static EvalWeights withCoverage(EvalWeights w, boolean detectiveCoverage) {
		return new EvalWeights(w.nearestWeight(), w.restWeight(), w.distanceCap(),
				w.useHumpPrior(), w.freedomWeight(), w.freedomCap(), w.revealFreedomBoost(),
				w.entropyAlpha(), w.beliefSearch(), w.gateMoves(), w.rootTieBand(),
				detectiveCoverage);
	}

	private static EvalWeights withFreedomWeight(EvalWeights w, double freedomWeight) {
		return new EvalWeights(w.nearestWeight(), w.restWeight(), w.distanceCap(),
				w.useHumpPrior(), freedomWeight, w.freedomCap(), w.revealFreedomBoost(),
				w.entropyAlpha(), w.beliefSearch(), w.gateMoves(), w.rootTieBand(),
				w.detectiveCoverage());
	}

	private static EvalWeights withDistanceCap(EvalWeights w, int distanceCap) {
		return new EvalWeights(w.nearestWeight(), w.restWeight(), distanceCap,
				w.useHumpPrior(), w.freedomWeight(), w.freedomCap(), w.revealFreedomBoost(),
				w.entropyAlpha(), w.beliefSearch(), w.gateMoves(), w.rootTieBand(),
				w.detectiveCoverage());
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
