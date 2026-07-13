package uk.ac.bris.cs.scotlandyard.ui.ai.arena;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

		while (state.getWinner().isEmpty()) {
			if (moves >= MOVE_CEILING) {
				return new GameResult(GameResult.Winner.NONE, mrXStart, starts, moves,
						state.getMrXTravelLog().size(), slowestMillis, 0);
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
				return new GameResult(winner, mrXStart, starts, moves,
						state.getMrXTravelLog().size(), slowestMillis, 1);
			}

			state = state.advance(move);
			moves++;
		}

		return new GameResult(winnerOf(state.getWinner()), mrXStart, starts, moves,
				state.getMrXTravelLog().size(), slowestMillis, 0);
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
				+ "illegal moves         %d",
				games,
				mrXWins, 100.0 * mrXWins / games,
				detectiveWins, 100.0 * detectiveWins / games,
				unfinished, meanRounds, meanMoves, meanSlowest, slowest, illegal);
	}
}
