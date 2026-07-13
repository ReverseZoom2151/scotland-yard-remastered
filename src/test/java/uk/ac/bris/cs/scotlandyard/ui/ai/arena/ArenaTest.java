package uk.ac.bris.cs.scotlandyard.ui.ai.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.ui.ai.GreedyAi;
import uk.ac.bris.cs.scotlandyard.ui.ai.RandomAi;

/**
 * The arena is measuring apparatus, and apparatus that lies is worse than no
 * apparatus at all. These tests pin the two properties the numbers depend on:
 * the same seed plays the same games, and a bot that cheats is disqualified
 * rather than quietly corrected.
 *
 * <p>
 * Budgets are tiny on purpose — this checks the arena, not the strength of any
 * AI, so the cheap bots are the ones under the lamp.
 */
public class ArenaTest {

	/** Enough for the greedy one-ply bots; the search is not what is being tested. */
	private static final long BUDGET_MILLIS = 50L;

	private static final int MRX_START = ScotlandYard.MRX_LOCATIONS.get(0);
	private static final ImmutableList<Integer> DETECTIVE_STARTS =
			ImmutableList.of(26, 29, 50, 53, 91);

	private static Supplier<Ai> random() {
		return () -> new RandomAi(7);
	}

	private static Supplier<Ai> greedy() {
		return GreedyAi::new;
	}

	@Test(timeout = 60_000)
	public void playsAGameToCompletionAndNamesAWinner() throws IOException {
		final GameResult result = new Arena(random(), random(), BUDGET_MILLIS)
				.playOne(Arena.standardSetup(), MRX_START, DETECTIVE_STARTS);

		assertThat(result.winner())
				.withFailMessage("the arena finished a game with no winner: %s", result)
				.isIn(GameResult.Winner.MRX, GameResult.Winner.DETECTIVES);
		assertThat(result.illegalMoveAttempts()).isZero();
		assertThat(result.totalMoves()).isPositive();
		assertThat(result.mrXStart()).isEqualTo(MRX_START);
		assertThat(result.detectiveStarts()).isEqualTo(DETECTIVE_STARTS);
		assertThat(result.toCsvRow()).isNotEmpty();
		assertThat(GameResult.csvHeader().split(",")).hasSize(13);
		assertThat(result.toCsvRow().split(",", -1)).hasSize(13);
	}

	/**
	 * The telemetry is the apparatus that tells a change that worked from a change that
	 * got lucky, so it has to be true of the game that was actually played: rounds within
	 * the log, a real final station, a candidate set that contains it, an entropy in range.
	 */
	@Test(timeout = 60_000)
	public void recordsTheTelemetryTheMechanismClaimsAreJudgedOn() throws IOException {
		final GameResult result = new Arena(random(), random(), BUDGET_MILLIS)
				.playOne(Arena.standardSetup(), MRX_START, DETECTIVE_STARTS);

		assertThat(result.mrXFinalLocation()).isPositive();
		assertThat(result.candidateSetSizeAtEnd()).isPositive();
		assertThat(result.beliefEntropyAtEnd()).isBetween(0.0, 1.0);

		// Every recorded spend belongs to a round that was actually played.
		assertThat(result.secretSpentRounds())
				.allSatisfy(round -> assertThat(round).isBetween(1, result.travelLogSize()));
		assertThat(result.doubleSpentRounds())
				.allSatisfy(round -> assertThat(round).isBetween(1, result.travelLogSize()));

		// RandomAi spends at most the tickets it was dealt.
		assertThat(result.secretSpentRounds().size()).isLessThanOrEqualTo(
				ScotlandYard.defaultMrXTickets().get(ScotlandYard.Ticket.SECRET));
		assertThat(result.doubleSpentRounds().size()).isLessThanOrEqualTo(
				ScotlandYard.defaultMrXTickets().get(ScotlandYard.Ticket.DOUBLE));

		if (result.winner() == GameResult.Winner.MRX) {
			assertThat(result.capturedAtRound()).isEqualTo(-1);
		} else {
			assertThat(result.capturedAtRound()).isIn(-1, result.travelLogSize());
		}
	}

	/** A belief that has collapsed to one station is zero entropy; ignorance is not. */
	@Test(timeout = 10_000)
	public void entropyIsZeroWhenMrXIsPinnedAndRisesWithDoubt() {
		assertThat(Arena.normalisedEntropy(java.util.Map.of(42, 1.0), 199)).isZero();

		final double spread = Arena.normalisedEntropy(
				java.util.Map.of(1, 0.25, 2, 0.25, 3, 0.25, 4, 0.25), 199);
		assertThat(spread).isBetween(0.0, 1.0).isGreaterThan(0.2);

		// Uniform over the whole map is total ignorance: the top of the scale.
		final java.util.Map<Integer, Double> everywhere = new java.util.LinkedHashMap<>();
		for (int station = 1; station <= 199; station++) everywhere.put(station, 1.0 / 199);
		assertThat(Arena.normalisedEntropy(everywhere, 199)).isCloseTo(1.0, within(1e-9));
	}

	/** A sweep must play every variant the same games, or it measures the openings. */
	@Test(timeout = 120_000)
	public void aSweepPlaysEveryVariantTheSameOpeningsAndRanksThem() throws IOException {
		final List<Arena.Variant> variants = ImmutableList.of(
				new Arena.Variant("random-a", random()),
				new Arena.Variant("random-b", random()));

		final List<Arena.SweepRow> rows =
				Arena.sweep(variants, greedy(), true, 2, 5L, BUDGET_MILLIS);

		assertThat(rows).hasSize(2);
		for (Arena.SweepRow row : rows) {
			assertThat(row.results()).hasSize(2);
			assertThat(row.winRate(true)).isBetween(0.0, 100.0);
		}
		// Same seed, same side, same brain: the two rows saw identical openings.
		assertThat(rows.get(0).results().get(0).mrXStart())
				.isEqualTo(rows.get(1).results().get(0).mrXStart());

		final String table = Arena.sweepTable(rows, true);
		assertThat(table).contains("random-a").contains("random-b").contains("win%");
		assertThat(Arena.sweepTable(ImmutableList.of(), true)).isEqualTo("no variants swept");
	}

	@Test(timeout = 120_000)
	public void playManyIsReproducibleFromItsSeed() throws IOException {
		final Arena arena = new Arena(random(), random(), BUDGET_MILLIS);

		final List<GameResult> first = arena.playMany(6, 42L);
		final List<GameResult> second = arena.playMany(6, 42L);

		assertThat(first).hasSize(6);
		assertThat(second).hasSize(6);
		for (int i = 0; i < first.size(); i++) {
			assertThat(first.get(i).mrXStart())
					.withFailMessage("seed 42 gave a different Mr X start on game %d", i)
					.isEqualTo(second.get(i).mrXStart());
			assertThat(first.get(i).detectiveStarts())
					.withFailMessage("seed 42 gave different detective starts on game %d", i)
					.isEqualTo(second.get(i).detectiveStarts());
		}

		// And a different seed really does deal a different hand.
		assertThat(Arena.positions(6, 42L)).isNotEqualTo(Arena.positions(6, 43L));
	}

	/** The starting positions have to be legal, or every game after them is nonsense. */
	@Test(timeout = 10_000)
	public void startingPositionsAreDistinctAndDrawnFromTheStandardTables() {
		for (Pair<Integer, ImmutableList<Integer>> position : Arena.positions(24, 3L)) {
			assertThat(ScotlandYard.MRX_LOCATIONS).contains(position.left());
			assertThat(position.right()).hasSize(5).doesNotHaveDuplicates()
					.doesNotContain(position.left())
					.allSatisfy(at -> assertThat(ScotlandYard.DETECTIVE_LOCATIONS).contains(at));
		}
	}

	@Test(timeout = 120_000)
	public void theCheapBotsNeverPlayAnIllegalMove() throws IOException {
		final List<GameResult> games = ImmutableList.<GameResult>builder()
				.addAll(new Arena(random(), greedy(), BUDGET_MILLIS).playMany(3, 1L))
				.addAll(new Arena(greedy(), random(), BUDGET_MILLIS).playMany(3, 2L))
				.build();

		assertThat(games).hasSize(6).allSatisfy(game -> {
			assertThat(game.hadIllegalMove())
					.withFailMessage("RandomAi or GreedyAi returned an illegal move: %s", game)
					.isFalse();
			assertThat(game.winner()).isNotEqualTo(GameResult.Winner.NONE);
		});
	}

	/** Returns a move nobody offered it. */
	private static final class CheatingAi implements Ai {
		@Nonnull
		@Override
		public String name() {
			return "Cheating Charlie";
		}

		@Nonnull
		@Override
		public Move pickMove(@Nonnull Board board, Pair<Long, TimeUnit> timeoutPair) {
			// A move from nowhere to nowhere, by a piece that may not even be to play.
			return new Move.SingleMove(Piece.MrX.MRX, 1, ScotlandYard.Ticket.SECRET, 199);
		}
	}

	/** Does not return at all. */
	private static final class ThrowingAi implements Ai {
		@Nonnull
		@Override
		public String name() {
			return "Exploding Eric";
		}

		@Nonnull
		@Override
		public Move pickMove(@Nonnull Board board, Pair<Long, TimeUnit> timeoutPair) {
			throw new IllegalStateException("deliberately broken");
		}
	}

	@Test(timeout = 60_000)
	public void anIllegalMoveForfeitsTheGameAndIsRecorded() throws IOException {
		final GameResult result = new Arena(CheatingAi::new, random(), BUDGET_MILLIS)
				.playOne(Arena.standardSetup(), MRX_START, DETECTIVE_STARTS);

		assertThat(result.illegalMoveAttempts())
				.withFailMessage("the arena accepted a move that was never on offer")
				.isPositive();
		assertThat(result.winner())
				.withFailMessage("a cheating Mr X must not be credited with a win")
				.isEqualTo(GameResult.Winner.DETECTIVES);
		assertThat(result.totalMoves()).isZero();
	}

	@Test(timeout = 60_000)
	public void anAiThatThrowsForfeitsRatherThanCrashingTheArena() throws IOException {
		final GameResult result = new Arena(random(), ThrowingAi::new, BUDGET_MILLIS)
				.playOne(Arena.standardSetup(), MRX_START, DETECTIVE_STARTS);

		assertThat(result.illegalMoveAttempts()).isPositive();
		assertThat(result.winner())
				.withFailMessage("detectives that threw must not be credited with a win")
				.isEqualTo(GameResult.Winner.MRX);
	}

	@Test(timeout = 10_000)
	public void summaryCountsBothSidesAndReportsTheMechanism() {
		final ImmutableList<Integer> starts = DETECTIVE_STARTS;
		final List<GameResult> results = ImmutableList.of(
				result(GameResult.Winner.MRX, 20, 10, 0, ImmutableList.of(4, 9),
						ImmutableList.of(9), -1, 12, 0.6),
				result(GameResult.Winner.DETECTIVES, 14, 6, 0, ImmutableList.of(4),
						ImmutableList.of(), 6, 1, 0.0),
				result(GameResult.Winner.DETECTIVES, 8, 3, 1, ImmutableList.of(),
						ImmutableList.of(), -1, 3, 0.3));

		final String summary = Arena.summary(results);
		assertThat(summary)
				.contains("games                 3")
				.contains("Mr X wins             1 (33.3%)")
				.contains("detective wins        2 (66.7%)")
				.contains("illegal moves         1")
				// The three secrets went on rounds 4, 4 and 9: mean 5.67, median 4.
				.contains("secrets spent         3")
				.contains("mean secret round   5.67")
				.contains("median secret round 4.00")
				.contains("secret histogram    4x2 9x1")
				.contains("mean double round   9.00")
				.contains("captures              1")
				.contains("mean capture round  6.00");
		assertThat(Arena.summary(ImmutableList.of())).isEqualTo("no games played");
	}

	private static GameResult result(GameResult.Winner winner, int moves, int rounds, int illegal,
			ImmutableList<Integer> secrets, ImmutableList<Integer> doubles, int capturedAt,
			int candidates, double entropy) {
		return new GameResult(winner, 35, DETECTIVE_STARTS, moves, rounds, 30, illegal,
				secrets, doubles, capturedAt, candidates, entropy, 100);
	}
}
