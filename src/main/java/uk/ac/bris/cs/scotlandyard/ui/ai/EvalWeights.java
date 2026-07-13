package uk.ac.bris.cs.scotlandyard.ui.ai;

/**
 * The tunable knobs of {@link Evaluator} and {@link Search}, in one place.
 *
 * <p>
 * Every constant that used to be a {@code private static final int} buried in the
 * evaluator is here instead, so that the arena can sweep a weight vector — or
 * switch a single term off — without a recompile of anybody's judgement about
 * what a good weight is. Ablation is the point: "this change helped" is a claim
 * that needs a control, and a control needs a knob.
 *
 * <p>
 * {@link #fromSystemProperties()} reads overrides from {@code -Dmrx.*} system
 * properties, which is what makes an ablation a one-line arena invocation:
 *
 * <pre>
 * mvnw exec:java@arena -Dmrx.entropyAlpha=0 -Dexec.args="--games=30 ..."
 * </pre>
 *
 * @param nearestWeight       weight on the nearest detective's (saturated) distance
 * @param restWeight          weight on the mean of the other detectives' distances
 * @param distanceCap         distance beyond which extra distance buys nothing
 * @param useHumpPrior        weigh distance by the measured hider prior instead of
 *                            saturating linearly — see {@link Evaluator}
 * @param freedomWeight       weight on the number of onward stations
 * @param freedomCap          onward stations beyond which extra ones buy nothing
 * @param revealFreedomBoost  multiplier on {@code freedomWeight} at a station Mr X
 *                            has just been revealed on
 * @param entropyAlpha        weight on the normalised entropy of the detectives'
 *                            belief; 0 switches the belief term off entirely
 * @param beliefSearch        model the simulated detectives as chasing the
 *                            <i>inferred</i> Mr X rather than the true one
 * @param gateMoves           filter Mr X's branching through {@link MoveGates}
 * @param rootTieBand         fraction of the best root score within which moves are
 *                            treated as tied and chosen between at random
 * @param detectiveCoverage   spread the detectives over distinct high-mass
 *                            candidates instead of all chasing the same one
 */
public record EvalWeights(
		double nearestWeight,
		double restWeight,
		int distanceCap,
		boolean useHumpPrior,
		double freedomWeight,
		int freedomCap,
		double revealFreedomBoost,
		double entropyAlpha,
		boolean beliefSearch,
		boolean gateMoves,
		double rootTieBand,
		boolean detectiveCoverage) {

	/** The tuned defaults — what {@link MyAi} plays with unless told otherwise. */
	public static EvalWeights defaults() {
		return new EvalWeights(
				0.6,    // nearestWeight
				0.4,    // restWeight
				4,      // distanceCap
				false,  // useHumpPrior
				0.25,   // freedomWeight
				10,     // freedomCap
				3.0,    // revealFreedomBoost
				0.8,    // entropyAlpha
				true,   // beliefSearch
				true,   // gateMoves
				0.02,   // rootTieBand
				true);  // detectiveCoverage
	}

	/**
	 * The defaults, with any {@code -Dmrx.<knob>=<value>} system property applied on
	 * top. Unknown or unparseable values are ignored rather than fatal: a benchmark
	 * that dies on a typo in a sweep script wastes an hour of measurement.
	 *
	 * @return the weights to play with
	 */
	public static EvalWeights fromSystemProperties() {
		final EvalWeights base = defaults();
		return new EvalWeights(
				doubleProperty("nearestWeight", base.nearestWeight()),
				doubleProperty("restWeight", base.restWeight()),
				intProperty("distanceCap", base.distanceCap()),
				booleanProperty("useHumpPrior", base.useHumpPrior()),
				doubleProperty("freedomWeight", base.freedomWeight()),
				intProperty("freedomCap", base.freedomCap()),
				doubleProperty("revealFreedomBoost", base.revealFreedomBoost()),
				doubleProperty("entropyAlpha", base.entropyAlpha()),
				booleanProperty("beliefSearch", base.beliefSearch()),
				booleanProperty("gateMoves", base.gateMoves()),
				doubleProperty("rootTieBand", base.rootTieBand()),
				booleanProperty("detectiveCoverage", base.detectiveCoverage()));
	}

	private static String raw(String knob) {
		return System.getProperty("mrx." + knob);
	}

	private static double doubleProperty(String knob, double fallback) {
		final String value = raw(knob);
		if (value == null) return fallback;
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	private static int intProperty(String knob, int fallback) {
		final String value = raw(knob);
		if (value == null) return fallback;
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	private static boolean booleanProperty(String knob, boolean fallback) {
		final String value = raw(knob);
		if (value == null) return fallback;
		return Boolean.parseBoolean(value);
	}
}
