package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

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
 * Contract tests for {@link MyAi}. The application treats an AI that throws, or
 * that returns a move outside {@link Board#getAvailableMoves()}, as fatal. These
 * tests assert only legality and timeliness — never move quality.
 */
public class MyAiContractTest {

	private static final int MRX_LOCATION = 106;

	private static Pair<Long, TimeUnit> timeout(long amount, TimeUnit unit) {
		return new Pair<>(amount, unit);
	}

	/** A standard-graph, five-detective opening position. */
	private static Board.GameState freshGame() throws IOException {
		GameSetup setup = new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
		Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), MRX_LOCATION);
		Player red = new Player(Piece.Detective.RED, ScotlandYard.defaultDetectiveTickets(), 91);
		Player green = new Player(Piece.Detective.GREEN, ScotlandYard.defaultDetectiveTickets(), 29);
		Player blue = new Player(Piece.Detective.BLUE, ScotlandYard.defaultDetectiveTickets(), 94);
		Player white = new Player(Piece.Detective.WHITE, ScotlandYard.defaultDetectiveTickets(), 155);
		Player yellow = new Player(Piece.Detective.YELLOW, ScotlandYard.defaultDetectiveTickets(), 138);
		return new MyGameStateFactory().build(setup, mrX,
				ImmutableList.of(red, green, blue, white, yellow));
	}

	private static boolean mrXToMove(Board board) {
		return board.getAvailableMoves().iterator().next().commencedBy().isMrX();
	}

	/** Plays one move using the AI, asserting it is legal, and returns the next state. */
	private static Board.GameState playOne(Ai ai, Board.GameState state, Pair<Long, TimeUnit> budget) {
		Move chosen = ai.pickMove(state, budget);
		assertThat(chosen).isNotNull();
		assertThat(state.getAvailableMoves())
				.as("AI returned a move outside getAvailableMoves()")
				.contains(chosen);
		return state.advance(chosen);
	}

	// 1. Reflective instantiation contract.

	@Test
	public void publicNoArgConstructorAndSaneName() throws Exception {
		Ai ai = MyAi.class.getDeclaredConstructor().newInstance();
		assertThat(ai.name()).isNotNull();
		assertThat(ai.name().trim()).isNotEmpty();
	}

	// 2. MrX's turn.

	@Test
	public void picksLegalMoveOnMrXTurn() throws IOException {
		Board.GameState state = freshGame();
		assertThat(mrXToMove(state)).isTrue();

		Move chosen = new MyAi().pickMove(state, timeout(500L, TimeUnit.MILLISECONDS));
		assertThat(state.getAvailableMoves()).contains(chosen);
		assertThat(chosen.commencedBy().isMrX()).isTrue();
	}

	// 3. A detective's turn.

	@Test
	public void picksLegalMoveOnDetectiveTurn() throws IOException {
		MyAi ai = new MyAi();
		Board.GameState state = freshGame();
		// Advance past MrX's opening move to reach a detective turn.
		state = playOne(ai, state, timeout(500L, TimeUnit.MILLISECONDS));
		assertThat(mrXToMove(state)).isFalse();

		Move chosen = ai.pickMove(state, timeout(500L, TimeUnit.MILLISECONDS));
		assertThat(state.getAvailableMoves()).contains(chosen);
		assertThat(chosen.commencedBy().isDetective()).isTrue();
	}

	// 4. Deadline: comfortably inside a generous budget.

	@Test
	public void returnsWellWithinGenerousDeadline() throws IOException {
		Board.GameState state = freshGame();
		long budgetNanos = TimeUnit.SECONDS.toNanos(5L);

		long start = System.nanoTime();
		Move chosen = new MyAi().pickMove(state, timeout(5L, TimeUnit.SECONDS));
		long elapsed = System.nanoTime() - start;

		assertThat(state.getAvailableMoves()).contains(chosen);
		assertThat(elapsed)
				.as("pickMove overran its 5s budget (%d ms elapsed)", TimeUnit.NANOSECONDS.toMillis(elapsed))
				.isLessThan(budgetNanos);
	}

	// 5. Absurdly short deadlines still yield a legal move (fallback path).

	@Test
	public void oneMillisecondDeadlineStillReturnsLegalMove() throws IOException {
		Board.GameState state = freshGame();
		Move chosen = new MyAi().pickMove(state, timeout(1L, TimeUnit.MILLISECONDS));
		assertThat(state.getAvailableMoves()).contains(chosen);
	}

	@Test
	public void tenMillisecondDeadlineStillReturnsLegalMove() throws IOException {
		MyAi ai = new MyAi();
		Board.GameState state = freshGame();
		Move mrXMove = ai.pickMove(state, timeout(10L, TimeUnit.MILLISECONDS));
		assertThat(state.getAvailableMoves()).contains(mrXMove);

		// And on a detective turn too.
		Board.GameState next = state.advance(mrXMove);
		Move detectiveMove = ai.pickMove(next, timeout(10L, TimeUnit.MILLISECONDS));
		assertThat(next.getAvailableMoves()).contains(detectiveMove);
	}

	@Test
	public void shortDeadlineDoesNotThrow() throws IOException {
		Board.GameState state = freshGame();
		MyAi ai = new MyAi();
		assertThatCode(() -> ai.pickMove(state, timeout(1L, TimeUnit.NANOSECONDS)))
				.doesNotThrowAnyException();
	}

	// 6. Robustness sweep: every move over many successive positions must be legal.

	@Test
	public void everyMoveOverManyPositionsIsLegal() throws IOException {
		Pair<Long, TimeUnit> budget = timeout(150L, TimeUnit.MILLISECONDS);

		// Play whole games until enough positions have been seen, rather than leaning on
		// any single game running long. A game can end in a handful of moves once the
		// detectives close in, and asserting that one game is long enough made this test
		// fail at random as the AI got better at catching Mr X.
		int positions = 0;
		int games = 0;
		while (positions < 24 && games < 8) {
			MyAi ai = new MyAi();
			ai.onStart();
			Board.GameState state = freshGame();
			while (state.getWinner().isEmpty()) {
				state = playOne(ai, state, budget); // asserts legality of every move
				positions++;
			}
			ai.onTerminate();
			games++;
		}

		assertThat(positions)
				.as("sweep should have exercised a decent number of positions")
				.isGreaterThanOrEqualTo(10);
	}

	// 7. Lifecycle hooks.

	@Test
	public void lifecycleHooksDoNotThrowAndPickMoveStillWorks() throws IOException {
		MyAi ai = new MyAi();
		assertThatCode(ai::onStart).doesNotThrowAnyException();

		Board.GameState state = freshGame();
		Move chosen = ai.pickMove(state, timeout(200L, TimeUnit.MILLISECONDS));
		assertThat(state.getAvailableMoves()).contains(chosen);

		assertThatCode(ai::onTerminate).doesNotThrowAnyException();

		// Restarting must not corrupt the AI either.
		assertThatCode(ai::onStart).doesNotThrowAnyException();
		Move again = ai.pickMove(state, timeout(200L, TimeUnit.MILLISECONDS));
		assertThat(state.getAvailableMoves()).contains(again);
	}
}
