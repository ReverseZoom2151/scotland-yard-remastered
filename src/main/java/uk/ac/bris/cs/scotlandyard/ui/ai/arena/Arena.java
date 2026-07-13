package uk.ac.bris.cs.scotlandyard.ui.ai.arena;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.ui.ai.Distances;
import uk.ac.bris.cs.scotlandyard.ui.ai.EvalWeights;
import uk.ac.bris.cs.scotlandyard.ui.ai.Evaluator;
import uk.ac.bris.cs.scotlandyard.ui.ai.MrXLocator;
import uk.ac.bris.cs.scotlandyard.ui.ai.MyAi;
import uk.ac.bris.cs.scotlandyard.ui.ai.Search;
import uk.ac.bris.cs.scotlandyard.ui.ai.Suspicion;

/**
 * Plays whole games between two AIs, headless and in bulk, so that "is this
 * change an improvement?" becomes a question with a number for an answer.
 *
 * <p>
 * The arena is not a referee that helps: an AI that returns a move outside
 * {@link Board#getAvailableMoves()}, or that throws, loses the game there and
 * then and the offence is recorded. Silently correcting it would let a broken
 * bot post a respectable score, which is the one thing a benchmark must never
 * do.
 *
 * <p>
 * It also records <i>how</i> each game was won, not merely who won it — see
 * {@link GameResult} for why a win rate on its own cannot distinguish a change
 * that worked from a change that got lucky.
 *
 * <p>
 * Games run in parallel. A {@code GameState} is immutable and every game builds
 * its own AI instances, which matters: the AIs cache map distances in fields and
 * are not thread safe.
 */
public final class Arena {

	/** The five detectives, in seating order. */
	public static final ImmutableList<Piece.Detective> DETECTIVE_PIECES = ImmutableList.of(
			Piece.Detective.RED, Piece.Detective.GREEN, Piece.Detective.BLUE,
			Piece.Detective.WHITE, Piece.Detective.YELLOW);

	/** A game that outruns this is looping, not playing: 24 rounds, six players. */
	private static final int MOVE_CEILING = 300;

	private final Supplier<Ai> mrXAi;
	private final Supplier<Ai> detectiveAi;
	private final long perMoveBudgetMillis;

	/**
	 * @param mrXAi               builds a fresh Mr X for each game
	 * @param detectiveAi         builds a fresh detective brain for each game
	 * @param perMoveBudgetMillis the deadline handed to {@code pickMove}
	 */
	public Arena(Supplier<Ai> mrXAi, Supplier<Ai> detectiveAi, long perMoveBudgetMillis) {
		this.mrXAi = mrXAi;
		this.detectiveAi = detectiveAi;
		this.perMoveBudgetMillis = perMoveBudgetMillis;
	}

	/** @return the standard setup; the graph is read from the classpath every call */
	public static GameSetup standardSetup() throws IOException {
		return new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
	}

	/**
	 * Plays one complete game.
	 *
	 * @param setup            the board and the rounds
	 * @param mrXStart         Mr X's station
	 * @param detectiveStarts  five distinct stations, none of them Mr X's
	 * @return what happened
	 */
	public GameResult playOne(GameSetup setup, int mrXStart, List<Integer> detectiveStarts) {
		final ImmutableList<Integer> starts = ImmutableList.copyOf(detectiveStarts);
		final Ai mrXBrain = this.mrXAi.get();
		final Ai detectiveBrain = this.detectiveAi.get();
		mrXBrain.onStart();
		detectiveBrain.onStart();
		try {
			return play(setup, mrXStart, starts, mrXBrain, detectiveBrain);
		} finally {
			mrXBrain.onTerminate();
			detectiveBrain.onTerminate();
		}
	}

	private GameResult play(GameSetup setup, int mrXStart, ImmutableList<Integer> starts,
			Ai mrXBrain, Ai detectiveBrain) {
		final Pair<Long, TimeUnit> timeout =
				new Pair<>(this.perMoveBudgetMillis, TimeUnit.MILLISECONDS);

		Board.GameState state = build(setup, mrXStart, starts);
		int moves = 0;
		long slowestMillis = 0;

		// Telemetry. The arena is the only observer that can see Mr X's true location,
		// so it is the only place these can honestly be collected.
		final List<Integer> secretRounds = new ArrayList<>();
		final List<Integer> doubleRounds = new ArrayList<>();
		int mrXAt = mrXStart;

		while (state.getWinner().isEmpty()) {
			if (moves >= MOVE_CEILING) {
				return finish(GameResult.Winner.NONE, state, mrXStart, starts, moves, slowestMillis,
						0, secretRounds, doubleRounds, mrXAt);
			}

			final boolean mrXToMove =
					state.getAvailableMoves().iterator().next().commencedBy().isMrX();
			final Ai brain = mrXToMove ? mrXBrain : detectiveBrain;

			final long began = System.nanoTime();
			Move move;
			try {
				move = brain.pickMove(state, timeout);
			} catch (RuntimeException | StackOverflowError broken) {
				move = null;
			}
			slowestMillis = Math.max(slowestMillis,
					TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));

			// The one rule of the arena: the move must come from the board that asked for
			// it. Breaking it forfeits the game — no correction, no second chance.
			if (move == null || !state.getAvailableMoves().contains(move)) {
				final GameResult.Winner winner = mrXToMove
						? GameResult.Winner.DETECTIVES
						: GameResult.Winner.MRX;
				return finish(winner, state, mrXStart, starts, moves, slowestMillis, 1,
						secretRounds, doubleRounds, mrXAt);
			}

			if (mrXToMove) {
				recordSpends(move, state.getMrXTravelLog().size(), secretRounds, doubleRounds);
				mrXAt = destinationOf(move);
			}

			state = state.advance(move);
			moves++;
		}

		return finish(winnerOf(state.getWinner()), state, mrXStart, starts, moves, slowestMillis,
				0, secretRounds, doubleRounds, mrXAt);
	}

	/**
	 * Records the special tickets a Mr X move spends, tagged with the round they land
	 * on. The "round" of a ticket is the travel-log size once its entry has been
	 * written, i.e. the 1-based round number — so a secret played on the round right
	 * after the round-3 reveal is recorded as 4, which is exactly the number the
	 * secret-timing hypothesis is stated in.
	 *
	 * <p>
	 * A {@link Move.DoubleMove} spends three tickets — {@code ticket1}, {@code ticket2}
	 * and {@link Ticket#DOUBLE} — and writes two log entries, so its second leg belongs
	 * to the following round.
	 *
	 * @param logSizeBefore the travel-log size before the move is played
	 */
	private static void recordSpends(Move move, int logSizeBefore, List<Integer> secretRounds,
			List<Integer> doubleRounds) {
		final int first = logSizeBefore + 1;
		if (move instanceof Move.DoubleMove twice) {
			doubleRounds.add(first);
			if (twice.ticket1 == Ticket.SECRET) secretRounds.add(first);
			if (twice.ticket2 == Ticket.SECRET) secretRounds.add(first + 1);
		} else if (move instanceof Move.SingleMove once) {
			if (once.ticket == Ticket.SECRET) secretRounds.add(first);
		}
	}

	/** @return where a Mr X move leaves him standing. */
	private static int destinationOf(Move move) {
		if (move instanceof Move.DoubleMove twice) return twice.destination2;
		if (move instanceof Move.SingleMove once) return once.destination;
		throw new IllegalArgumentException("unknown move type: " + move);
	}

	/**
	 * Reads the end-of-game telemetry off the final board and packages the result.
	 * {@link MrXLocator} and {@link Suspicion} are called, never reimplemented: the
	 * number the arena reports has to be the same number the detectives were actually
	 * reasoning with, or it measures nothing.
	 */
	private static GameResult finish(GameResult.Winner winner, Board.GameState state, int mrXStart,
			ImmutableList<Integer> starts, int moves, long slowestMillis, int illegal,
			List<Integer> secretRounds, List<Integer> doubleRounds, int mrXAt) {
		final int rounds = state.getMrXTravelLog().size();
		final int candidates = MrXLocator.possibleLocations(state).size();
		final double entropy = normalisedEntropy(Suspicion.likelihoods(state),
				state.getSetup().graph.nodes().size());

		// "Captured" means a detective is standing on him — as distinct from the other
		// ways the detectives win (Mr X cornered with no move, or disqualified), which
		// would otherwise be silently folded into the capture-round statistics.
		final boolean captured = winner == GameResult.Winner.DETECTIVES && illegal == 0
				&& detectivesStandOn(state, mrXAt);

		return new GameResult(winner, mrXStart, starts, moves, rounds, slowestMillis, illegal,
				ImmutableList.copyOf(secretRounds), ImmutableList.copyOf(doubleRounds),
				captured ? rounds : -1, candidates, entropy, mrXAt);
	}

	private static boolean detectivesStandOn(Board board, int station) {
		for (Piece.Detective detective : DETECTIVE_PIECES) {
			if (board.getDetectiveLocation(detective).map(at -> at == station).orElse(false)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Shannon entropy of the detectives' belief, in nats, divided by the entropy of
	 * total ignorance (a uniform distribution over the whole map). 0 means they have
	 * pinned him to one station; 1 would mean they know nothing at all. Normalising by
	 * the map rather than by the support size keeps the number comparable across games —
	 * a uniform belief over 3 stations is a far better state of knowledge than a uniform
	 * belief over 60, and dividing by the support's own entropy would call both 1.0.
	 */
	static double normalisedEntropy(Map<Integer, Double> likelihoods, int mapSize) {
		if (mapSize <= 1 || likelihoods.isEmpty()) return 0;
		double entropy = 0;
		for (double p : likelihoods.values()) {
			if (p > 0) entropy -= p * Math.log(p);
		}
		return Math.min(1.0, entropy / Math.log(mapSize));
	}

	private static GameResult.Winner winnerOf(ImmutableSet<Piece> winner) {
		if (winner.isEmpty()) return GameResult.Winner.NONE;
		return winner.contains(Piece.MrX.MRX)
				? GameResult.Winner.MRX
				: GameResult.Winner.DETECTIVES;
	}

	private static Board.GameState build(GameSetup setup, int mrXStart, List<Integer> starts) {
		final Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), mrXStart);
		final List<Player> detectives = new ArrayList<>();
		for (int i = 0; i < DETECTIVE_PIECES.size(); i++) {
			detectives.add(new Player(DETECTIVE_PIECES.get(i),
					ScotlandYard.defaultDetectiveTickets(), starts.get(i)));
		}
		return new MyGameStateFactory().build(setup, mrX, ImmutableList.copyOf(detectives));
	}

	/**
	 * Plays a batch, in parallel, from starting positions drawn deterministically
	 * from {@code seed}: the same seed gives the same games, which is what makes two
	 * runs comparable at all.
	 *
	 * @param games how many games to play
	 * @param seed  fixes the starting positions
	 * @return one result per game, in the order the positions were drawn
	 * @throws IOException if the standard graph cannot be read
	 */
	public List<GameResult> playMany(int games, long seed) throws IOException {
		final GameSetup setup = standardSetup();
		final List<Pair<Integer, ImmutableList<Integer>>> positions = positions(games, seed);
		return positions.parallelStream()
				.map(position -> playOne(setup, position.left(), position.right()))
				.collect(Collectors.toList());
	}

	/**
	 * Draws {@code games} starting positions from the standard tables. Mr X rotates
	 * through {@link ScotlandYard#MRX_LOCATIONS}; the detectives are the first five
	 * of a shuffled {@link ScotlandYard#DETECTIVE_LOCATIONS}, which are distinct by
	 * construction and never collide with Mr X (the two tables are disjoint).
	 */
	static List<Pair<Integer, ImmutableList<Integer>>> positions(int games, long seed) {
		final Random random = new Random(seed);
		final List<Pair<Integer, ImmutableList<Integer>>> positions = new ArrayList<>();
		for (int i = 0; i < games; i++) {
			final int mrXStart =
					ScotlandYard.MRX_LOCATIONS.get(i % ScotlandYard.MRX_LOCATIONS.size());
			final List<Integer> pool = new ArrayList<>(ScotlandYard.DETECTIVE_LOCATIONS);
			Collections.shuffle(pool, random);
			positions.add(new Pair<>(mrXStart,
					ImmutableList.copyOf(pool.subList(0, DETECTIVE_PIECES.size()))));
		}
		return positions;
	}

	// ---------------------------------------------------------------- sweeps

	/**
	 * One competitor in a sweep: a name to print and a factory to field.
	 *
	 * @param name a label short enough to head a column
	 * @param ai   builds a fresh brain per game
	 */
	public record Variant(String name, Supplier<Ai> ai) {
	}

	/**
	 * A competitor that plays {@link MyAi}'s search with a given weight vector.
	 *
	 * <p>
	 * {@link MyAi} itself takes no weights — the game instantiates it reflectively
	 * through its no-arg constructor, so it cannot. The sweep therefore assembles the
	 * same two objects {@code MyAi} assembles ({@link Evaluator} over {@link Distances},
	 * driven by {@link Search}) and hands the evaluator the weights under test. It is
	 * the same brain; only the knobs differ, which is the only way the comparison means
	 * anything.
	 *
	 * @param weights the knobs under test
	 * @return a factory for a fresh brain per game
	 */
	public static Supplier<Ai> weighted(EvalWeights weights) {
		return () -> new WeightedAi(weights);
	}

	/** Fixes the root tie-breaking rng, so a swept variant plays the same games twice. */
	private static final long SWEEP_SEED = 20250713L;

	/** {@link MyAi}, but with the weight vector chosen by the caller rather than by default. */
	private static final class WeightedAi implements Ai {

		private final EvalWeights weights;
		private Search search;

		WeightedAi(EvalWeights weights) {
			this.weights = weights;
		}

		@Nonnull
		@Override
		public String name() {
			return "Boris (swept)";
		}

		@Override
		public void onStart() {
			this.search = null;
		}

		@Nonnull
		@Override
		public Move pickMove(@Nonnull Board board, Pair<Long, TimeUnit> timeoutPair) {
			final Move fallback = board.getAvailableMoves().iterator().next();
			try {
				if (this.search == null) {
					final Distances distances = new Distances(board.getSetup().graph);
					// Both halves get the SAME vector: Search keeps its own copy of the knobs
					// it owns (gating, belief search, tie band, coverage) and the Evaluator
					// keeps the scoring ones. Handing the weights to only one of them would
					// silently ablate half of each variant — which is worse than not sweeping.
					// The rng is seeded so a variant's games are reproducible.
					this.search = new Search(new Evaluator(distances, this.weights), distances,
							this.weights, new Random(SWEEP_SEED));
				}
				final long budget = timeoutPair.right().toNanos(timeoutPair.left());
				final long deadline = System.nanoTime() + Math.max(budget - budget / 5, 0L);
				final boolean mrXToMove = board.getAvailableMoves().stream()
						.anyMatch(move -> move.commencedBy().isMrX());
				final Move chosen = mrXToMove
						? this.search.bestMoveForMrX(board, deadline)
						: this.search.bestMoveForDetective(board, deadline);
				return board.getAvailableMoves().contains(chosen) ? chosen : fallback;
			} catch (RuntimeException | StackOverflowError anything) {
				return fallback;
			}
		}
	}

	/** One row of a sweep: a variant and how it did. */
	public record SweepRow(String name, List<GameResult> results) {
		public SweepRow {
			results = List.copyOf(results);
		}

		/** @return the fraction of games the variant's own side won */
		public double winRate(boolean variantIsMrX) {
			if (this.results.isEmpty()) return 0;
			final GameResult.Winner mine = variantIsMrX
					? GameResult.Winner.MRX
					: GameResult.Winner.DETECTIVES;
			return 100.0 * this.results.stream().filter(r -> r.winner() == mine).count()
					/ this.results.size();
		}

		private double mean(java.util.function.ToDoubleFunction<GameResult> of) {
			return this.results.stream().mapToDouble(of).average().orElse(0);
		}
	}

	/**
	 * Plays every variant against the <b>same</b> fixed opponent, from the <b>same</b>
	 * seeded starting positions, and hands back one row per variant. Holding both fixed
	 * is the entire point: a sweep in which the opponent or the openings differ between
	 * rows is a sweep that measures the openings.
	 *
	 * @param variants     the competitors, in the order they should be printed
	 * @param opponent     the fixed other side
	 * @param variantIsMrX whether the variants play Mr X (otherwise they play the detectives)
	 * @param games        games per variant
	 * @param seed         fixes the starting positions, shared by every variant
	 * @param budgetMillis the per-move deadline
	 * @throws IOException if the standard graph cannot be read
	 */
	public static List<SweepRow> sweep(List<Variant> variants, Supplier<Ai> opponent,
			boolean variantIsMrX, int games, long seed, long budgetMillis) throws IOException {
		final List<SweepRow> rows = new ArrayList<>();
		for (Variant variant : variants) {
			final Arena arena = variantIsMrX
					? new Arena(variant.ai(), opponent, budgetMillis)
					: new Arena(opponent, variant.ai(), budgetMillis);
			rows.add(new SweepRow(variant.name(), arena.playMany(games, seed)));
		}
		return rows;
	}

	/**
	 * @param rows         the sweep's rows
	 * @param variantIsMrX whether the variants played Mr X
	 * @return a table with one row per variant, best first — win rate, survival, and the
	 *         mechanism columns that say whether a win rate was earned or lucky
	 */
	public static String sweepTable(List<SweepRow> rows, boolean variantIsMrX) {
		if (rows.isEmpty()) return "no variants swept";

		final List<SweepRow> ranked = new ArrayList<>(rows);
		ranked.sort((a, b) -> Double.compare(b.winRate(variantIsMrX), a.winRate(variantIsMrX)));

		final StringBuilder out = new StringBuilder();
		out.append(String.format(java.util.Locale.ROOT,
				"%-20s %8s %8s %10s %10s %10s %10s%n",
				variantIsMrX ? "variant (Mr X)" : "variant (dets)",
				"games", "win%", "rounds", "meanSecR", "endCands", "endEntropy"));
		for (SweepRow row : ranked) {
			out.append(String.format(java.util.Locale.ROOT,
					"%-20s %8d %8.1f %10.2f %10.2f %10.2f %10.3f%n",
					row.name(), row.results().size(), row.winRate(variantIsMrX),
					row.mean(GameResult::travelLogSize),
					meanOfRounds(row.results(), GameResult::secretSpentRounds),
					row.mean(GameResult::candidateSetSizeAtEnd),
					row.mean(GameResult::beliefEntropyAtEnd)));
		}
		return out.toString();
	}

	// ---------------------------------------------------------------- summary

	/** @return a human-readable block of statistics over a finished batch */
	public static String summary(List<GameResult> results) {
		if (results.isEmpty()) return "no games played";

		final int games = results.size();
		final long mrXWins = results.stream()
				.filter(r -> r.winner() == GameResult.Winner.MRX).count();
		final long detectiveWins = results.stream()
				.filter(r -> r.winner() == GameResult.Winner.DETECTIVES).count();
		final long unfinished = results.stream()
				.filter(r -> r.winner() == GameResult.Winner.NONE).count();
		final int illegal = results.stream().mapToInt(GameResult::illegalMoveAttempts).sum();
		final double meanRounds = results.stream()
				.mapToInt(GameResult::travelLogSize).average().orElse(0);
		final double meanMoves = results.stream()
				.mapToInt(GameResult::totalMoves).average().orElse(0);
		final double meanSlowest = results.stream()
				.mapToLong(GameResult::slowestMoveMillis).average().orElse(0);
		final long slowest = results.stream()
				.mapToLong(GameResult::slowestMoveMillis).max().orElse(0);

		// The mechanism half of the report. A secret-spend histogram that clusters on
		// 4/9/14/19 is the fingerprint of Mr X spending secrets when they are worth most —
		// the round after a reveal, when the detectives' belief has collapsed to a point.
		// Without these columns an improved win rate is uninterpretable: it could be the
		// gates working, or it could be noise.
		final List<Integer> secrets = allRounds(results, GameResult::secretSpentRounds);
		final List<Integer> doubles = allRounds(results, GameResult::doubleSpentRounds);
		final double meanCapture = results.stream()
				.filter(r -> r.capturedAtRound() >= 0)
				.mapToInt(GameResult::capturedAtRound).average().orElse(Double.NaN);
		final long captures = results.stream().filter(r -> r.capturedAtRound() >= 0).count();
		final double meanCandidates = results.stream()
				.mapToInt(GameResult::candidateSetSizeAtEnd).average().orElse(0);
		final double meanEntropy = results.stream()
				.mapToDouble(GameResult::beliefEntropyAtEnd).average().orElse(0);

		// Locale.ROOT: this is a data report, not prose — a comma for a decimal point
		// would break every CSV and every eye that reads the two side by side.
		return String.format(java.util.Locale.ROOT,
				"games                 %d%n"
				+ "Mr X wins             %d (%.1f%%)%n"
				+ "detective wins        %d (%.1f%%)%n"
				+ "unfinished            %d%n"
				+ "mean rounds survived  %.2f%n"
				+ "mean moves per game   %.2f%n"
				+ "mean slowest move     %.0f ms%n"
				+ "worst move            %d ms%n"
				+ "illegal moves         %d%n"
				+ "secrets spent         %d (%.2f per game)%n"
				+ "  mean secret round   %s%n"
				+ "  median secret round %s%n"
				+ "  secret histogram    %s%n"
				+ "doubles spent         %d (%.2f per game)%n"
				+ "  mean double round   %s%n"
				+ "captures              %d%n"
				+ "  mean capture round  %s%n"
				+ "mean end candidates   %.2f%n"
				+ "mean end entropy      %.3f",
				games,
				mrXWins, 100.0 * mrXWins / games,
				detectiveWins, 100.0 * detectiveWins / games,
				unfinished, meanRounds, meanMoves, meanSlowest, slowest, illegal,
				secrets.size(), (double) secrets.size() / games,
				number(mean(secrets)), number(median(secrets)), histogram(secrets),
				doubles.size(), (double) doubles.size() / games,
				number(mean(doubles)),
				captures, number(meanCapture),
				meanCandidates, meanEntropy);
	}

	/** @return every recorded round across the batch, flattened, for the histograms */
	private static List<Integer> allRounds(List<GameResult> results,
			java.util.function.Function<GameResult, ImmutableList<Integer>> of) {
		return results.stream().flatMap(r -> of.apply(r).stream()).collect(Collectors.toList());
	}

	private static double meanOfRounds(List<GameResult> results,
			java.util.function.Function<GameResult, ImmutableList<Integer>> of) {
		return mean(allRounds(results, of));
	}

	private static double mean(List<Integer> values) {
		return values.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
	}

	private static double median(List<Integer> values) {
		if (values.isEmpty()) return Double.NaN;
		final List<Integer> sorted = values.stream().sorted().collect(Collectors.toList());
		final int middle = sorted.size() / 2;
		return sorted.size() % 2 == 1
				? sorted.get(middle)
				: (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
	}

	/** @return "4x12 5x3 9x8", the rounds that were actually used and how often. */
	private static String histogram(List<Integer> rounds) {
		if (rounds.isEmpty()) return "-";
		final Map<Integer, Long> counts = rounds.stream().collect(
				Collectors.groupingBy(r -> r, java.util.TreeMap::new, Collectors.counting()));
		return counts.entrySet().stream()
				.map(e -> e.getKey() + "x" + e.getValue())
				.collect(Collectors.joining(" "));
	}

	/** NaN means "it never happened", which is not the same claim as 0.00. */
	private static String number(double value) {
		return Double.isNaN(value)
				? "-"
				: String.format(java.util.Locale.ROOT, "%.2f", value);
	}
}
