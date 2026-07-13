package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;

/**
 * Drives {@link MyAi} against itself, on both sides, for whole games.
 *
 * <p>
 * The contract the application enforces is narrow: return a move from
 * {@link Board#getAvailableMoves()}, return it before the deadline, and do not
 * throw. Nothing short of a complete game exercises that contract, so these
 * tests play complete games and check every single move against the board that
 * produced it.
 */
public class SelfPlayTest {

	/**
	 * Small on purpose: the search deepens iteratively against the deadline, so a
	 * short budget buys a shallower search rather than a broken one, and keeps a few
	 * hundred moves inside a sane wall clock.
	 *
	 * <p>
	 * Note that {@code MyAi} hands back a 500ms safety buffer, so a budget this
	 * small leaves the search no thinking time at all. That is deliberate here — it
	 * is the degenerate end of the deadline contract, and the AI still has to return
	 * a legal move — but it means these games do not exercise the search itself.
	 * {@link #aiPlaysAWholeGameWithRealThinkingTime()} covers that.
	 */
	private static final long BUDGET_MILLIS = 250L;

	/** Comfortably above the 500ms safety buffer, so the search actually runs. */
	private static final long THINKING_BUDGET_MILLIS = 1_500L;

	/** The app kills the AI one second after the deadline; that is the real bound. */
	private static long hardLimitNanos(long budgetMillis) {
		return TimeUnit.MILLISECONDS.toNanos(budgetMillis) + TimeUnit.SECONDS.toNanos(1);
	}

	/** A game that outruns this is looping, not playing: 24 rounds, six players. */
	private static final int MOVE_CEILING = 200;

	/** The outcome of one self-played game, for reporting. */
	private static final class Outcome {
		private final ImmutableSet<Piece> winner;
		private final int moves;
		private final int logSize;
		private final long slowestNanos;

		private Outcome(ImmutableSet<Piece> winner, int moves, int logSize, long slowestNanos) {
			this.winner = winner;
			this.moves = moves;
			this.logSize = logSize;
			this.slowestNanos = slowestNanos;
		}
	}

	private static Board.GameState gameFrom(int mrXLocation, List<Integer> detectiveLocations)
			throws IOException {
		final GameSetup setup =
				new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
		final Player mrX =
				new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), mrXLocation);
		final Piece.Detective[] pieces = {
				Piece.Detective.RED, Piece.Detective.GREEN, Piece.Detective.BLUE,
				Piece.Detective.WHITE, Piece.Detective.YELLOW
		};
		final List<Player> detectives = new ArrayList<>();
		for (int i = 0; i < pieces.length; i++) {
			detectives.add(new Player(pieces[i], ScotlandYard.defaultDetectiveTickets(),
					detectiveLocations.get(i)));
		}
		return new MyGameStateFactory().build(setup, mrX, ImmutableList.copyOf(detectives));
	}

	/**
	 * Plays one whole game with a single AI on both sides, checking the legality and
	 * the timing of every move as it goes.
	 */
	private static Outcome playOut(Board.GameState initial, long budgetMillis) {
		final long hardLimit = hardLimitNanos(budgetMillis);
		final MyAi ai = new MyAi();
		ai.onStart();
		try {
			final Pair<Long, TimeUnit> timeout = new Pair<>(budgetMillis, TimeUnit.MILLISECONDS);
			Board.GameState state = initial;
			int moves = 0;
			long slowest = 0;

			while (state.getWinner().isEmpty()) {
				assertThat(moves)
						.withFailMessage("game did not terminate within %d moves — the AI or the "
								+ "model is looping", MOVE_CEILING)
						.isLessThan(MOVE_CEILING);

				final long start = System.nanoTime();
				final Move move = ai.pickMove(state, timeout);
				final long elapsed = System.nanoTime() - start;
				slowest = Math.max(slowest, elapsed);

				assertThat(move)
						.withFailMessage("AI returned null on move %d", moves + 1)
						.isNotNull();
				assertThat(state.getAvailableMoves())
						.withFailMessage("AI returned an illegal move on move %d: %s",
								moves + 1, move)
						.contains(move);
				assertThat(elapsed)
						.withFailMessage("move %d took %d ms; the app hard-kills the AI at %d ms",
								moves + 1, TimeUnit.NANOSECONDS.toMillis(elapsed),
								TimeUnit.NANOSECONDS.toMillis(hardLimit))
						.isLessThan(hardLimit);

				state = state.advance(move);
				moves++;
			}

			return new Outcome(state.getWinner(), moves, state.getMrXTravelLog().size(), slowest);
		} finally {
			ai.onTerminate();
		}
	}

	/** The only two legal endings: Mr X alone, or every detective together. */
	private static void assertRealConclusion(ImmutableSet<Piece> winner) {
		assertThat(winner).withFailMessage("game ended with no winner").isNotEmpty();
		final ImmutableSet<Piece> allDetectives = ImmutableSet.of(
				Piece.Detective.RED, Piece.Detective.GREEN, Piece.Detective.BLUE,
				Piece.Detective.WHITE, Piece.Detective.YELLOW);
		assertThat(winner)
				.withFailMessage("winner was neither {MRX} nor the full detective set: %s", winner)
				.isIn(ImmutableSet.of(Piece.MrX.MRX), allDetectives);
	}

	private static void report(String label, Outcome outcome) {
		System.out.printf(
				"[SelfPlay] %s: winner=%s moves=%d travelLog=%d slowestMove=%dms%n",
				label, outcome.winner, outcome.moves, outcome.logSize,
				TimeUnit.NANOSECONDS.toMillis(outcome.slowestNanos));
	}

	@Test(timeout = 120_000)
	public void aiPlaysBothSidesThroughAWholeGame() throws IOException {
		final Outcome outcome =
				playOut(gameFrom(106, ImmutableList.of(91, 29, 94, 155, 138)), BUDGET_MILLIS);
		report("standard start", outcome);
		assertRealConclusion(outcome.winner);
	}

	/**
	 * The same game, but with a budget above the AI's own safety buffer, so the
	 * search really does deepen against the deadline. This is the one that would
	 * catch a search that overruns, throws, or never returns.
	 */
	@Test(timeout = 120_000)
	public void aiPlaysAWholeGameWithRealThinkingTime() throws IOException {
		final Outcome outcome = playOut(gameFrom(106, ImmutableList.of(91, 29, 94, 155, 138)),
				THINKING_BUDGET_MILLIS);
		report("standard start, " + THINKING_BUDGET_MILLIS + "ms budget", outcome);
		assertRealConclusion(outcome.winner);
	}

	@Test(timeout = 120_000)
	public void aiFinishesGamesFromSeveralStartingPositions() throws IOException {
		final List<Integer> mrXStarts = ImmutableList.of(35, 45, 71, 127);
		final List<List<Integer>> detectiveStarts = ImmutableList.of(
				ImmutableList.of(26, 50, 53, 91, 94),
				ImmutableList.of(103, 112, 117, 123, 138),
				ImmutableList.of(29, 94, 141, 155, 174),
				ImmutableList.of(26, 29, 50, 53, 94));

		for (int i = 0; i < mrXStarts.size(); i++) {
			final Outcome outcome =
					playOut(gameFrom(mrXStarts.get(i), detectiveStarts.get(i)), BUDGET_MILLIS);
			report("mrX@" + mrXStarts.get(i) + " detectives" + detectiveStarts.get(i), outcome);
			assertRealConclusion(outcome.winner);
		}
	}
}
