package uk.ac.bris.cs.scotlandyard.ui.ai.arena;

import static org.assertj.core.api.Assertions.assertThat;

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
		assertThat(GameResult.csvHeader().split(",")).hasSize(7);
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
	public void summaryCountsBothSides() {
		final ImmutableList<Integer> starts = DETECTIVE_STARTS;
		final List<GameResult> results = ImmutableList.of(
				new GameResult(GameResult.Winner.MRX, 35, starts, 20, 10, 30, 0),
				new GameResult(GameResult.Winner.DETECTIVES, 45, starts, 14, 6, 50, 0),
				new GameResult(GameResult.Winner.DETECTIVES, 51, starts, 8, 3, 90, 1));

		final String summary = Arena.summary(results);
		assertThat(summary)
				.contains("games                 3")
				.contains("Mr X wins             1 (33.3%)")
				.contains("detective wins        2 (66.7%)")
				.contains("illegal moves         1");
		assertThat(Arena.summary(ImmutableList.of())).isEqualTo("no games played");
	}
}
