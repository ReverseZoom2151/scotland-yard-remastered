package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.LogEntry;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;

/**
 * {@link MyAi} as an {@link Explains}: the UI's "Explain AI" overlay reads
 * {@link Explains#lastEvaluation()} and draws the top few moves as arrows, so the list
 * must be legal, ranked best-first, and — the part that is easy to get backwards —
 * ranked best-first <i>for whoever was to move</i>, detectives included.
 */
public class ExplainsTest {

	private static final int MRX_LOCATION = 106;

	private static Pair<Long, TimeUnit> budget(long millis) {
		return new Pair<>(millis, TimeUnit.MILLISECONDS);
	}

	private static GameSetup setup() throws IOException {
		return new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
	}

	/** A standard-graph, five-detective opening position. */
	private static Board.GameState freshGame() throws IOException {
		Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), MRX_LOCATION);
		Player red = new Player(Piece.Detective.RED, ScotlandYard.defaultDetectiveTickets(), 91);
		Player green = new Player(Piece.Detective.GREEN, ScotlandYard.defaultDetectiveTickets(), 29);
		Player blue = new Player(Piece.Detective.BLUE, ScotlandYard.defaultDetectiveTickets(), 94);
		Player white = new Player(Piece.Detective.WHITE, ScotlandYard.defaultDetectiveTickets(), 155);
		Player yellow = new Player(Piece.Detective.YELLOW, ScotlandYard.defaultDetectiveTickets(), 138);
		return new MyGameStateFactory().build(setup(), mrX,
				ImmutableList.of(red, green, blue, white, yellow));
	}

	/** A different opening position, for the staleness test. */
	private static Board.GameState otherGame() throws IOException {
		Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), 35);
		Player red = new Player(Piece.Detective.RED, ScotlandYard.defaultDetectiveTickets(), 26);
		Player green = new Player(Piece.Detective.GREEN, ScotlandYard.defaultDetectiveTickets(), 50);
		return new MyGameStateFactory().build(setup(), mrX, ImmutableList.of(red, green));
	}

	private static boolean mrXToMove(Board board) {
		return board.getAvailableMoves().iterator().next().commencedBy().isMrX();
	}

	private static void assertSortedBestFirst(List<Explains.ScoredMove> evaluation) {
		assertThat(evaluation)
				.as("the UI draws them in order; they must already be ranked best first")
				.isSortedAccordingTo(Comparator.comparingInt(Explains.ScoredMove::score).reversed());
	}

	private static void assertAllLegal(List<Explains.ScoredMove> evaluation, Board board) {
		for (Explains.ScoredMove scored : evaluation) {
			assertThat(board.getAvailableMoves()).contains(scored.move());
		}
	}

	// 1. The UI probes with an instanceof; without this the menu item does nothing.

	@Test
	public void myAiExplains() {
		assertThat(new MyAi()).isInstanceOf(Explains.class);
	}

	// 2. Nothing to explain before anything has been thought about.

	@Test
	public void emptyBeforeAnyPickMove() {
		assertThat(new MyAi().lastEvaluation()).isEmpty();
	}

	// 3. Mr X's turn.

	@Test
	public void explainsMrXTurn() throws IOException {
		MyAi ai = new MyAi();
		Board.GameState state = freshGame();
		assertThat(mrXToMove(state)).isTrue();

		Move chosen = ai.pickMove(state, budget(1000L));
		List<Explains.ScoredMove> evaluation = ai.lastEvaluation();

		assertThat(evaluation).isNotEmpty();
		assertAllLegal(evaluation, state);
		assertSortedBestFirst(evaluation);

		// The search samples among near-tied root moves, so the move played need not be the
		// top-scored one — but it must be one the search actually looked at.
		assertThat(evaluation.stream().map(Explains.ScoredMove::move)).contains(chosen);
	}

	// 4. A detective's turn: the normalisation check, and the point of the whole exercise.

	@Test
	public void explainsDetectiveTurnWithHigherMeaningBetterForTheDetective() throws IOException {
		MyAi ai = new MyAi();
		Board.GameState state = freshGame();

		// Play on to the first detective turn after a reveal round, so Mr X's location is
		// public and "good move" versus "bad move" is not a matter of opinion.
		while (state.getWinner().isEmpty()
				&& (mrXToMove(state) || revealedLocation(state).isEmpty())) {
			state = state.advance(ai.pickMove(state, budget(200L)));
		}
		assertThat(state.getWinner()).isEmpty();
		assertThat(mrXToMove(state)).isFalse();
		final int mrX = revealedLocation(state).orElseThrow();

		Move chosen = ai.pickMove(state, budget(1000L));
		List<Explains.ScoredMove> evaluation = ai.lastEvaluation();

		assertThat(evaluation).isNotEmpty();
		assertAllLegal(evaluation, state);
		assertSortedBestFirst(evaluation);
		assertThat(evaluation.stream().map(Explains.ScoredMove::move)).contains(chosen);

		// The detective search minimises Mr X's score, so if its numbers were exposed raw
		// the move it chose would sit at the BOTTOM of the list. It must sit at the top.
		int topScore = evaluation.get(0).score();
		int chosenScore = evaluation.stream()
				.filter(scored -> scored.move().equals(chosen))
				.mapToInt(Explains.ScoredMove::score)
				.max()
				.orElseThrow();
		assertThat(chosenScore)
				.as("detective scores are not normalised: the chosen move is not the best-scored")
				.isEqualTo(topScore);

		// And, directly: closing on the revealed Mr X must outrank running away from him.
		Distances distances = new Distances(state.getSetup().graph);
		Piece mover = evaluation.get(0).move().commencedBy();
		List<Explains.ScoredMove> mine = evaluation.stream()
				.filter(scored -> scored.move().commencedBy().equals(mover))
				.toList();
		Explains.ScoredMove closing = mine.stream()
				.min(Comparator.comparingInt(scored -> distances.hops(destinationOf(scored.move()), mrX)))
				.orElseThrow();
		Explains.ScoredMove fleeing = mine.stream()
				.max(Comparator.comparingInt(scored -> distances.hops(destinationOf(scored.move()), mrX)))
				.orElseThrow();
		int near = distances.hops(destinationOf(closing.move()), mrX);
		int far = distances.hops(destinationOf(fleeing.move()), mrX);
		if (far - near >= 2) { // only meaningful when the two moves really do disagree
			assertThat(closing.score())
					.as("the move towards Mr X (%d hops) must outrank the move away (%d hops)", near, far)
					.isGreaterThan(fleeing.score());
		}
	}

	// 5. No leakage across turns: a second board's explanation is about the second board.

	@Test
	public void doesNotLeakThePreviousTurnsEvaluation() throws IOException {
		MyAi ai = new MyAi();
		Board.GameState first = freshGame();
		ai.pickMove(first, budget(500L));
		assertThat(ai.lastEvaluation()).isNotEmpty();

		Board.GameState second = otherGame();
		ai.pickMove(second, budget(500L));
		List<Explains.ScoredMove> evaluation = ai.lastEvaluation();

		assertThat(evaluation).isNotEmpty();
		assertAllLegal(evaluation, second);
		assertSortedBestFirst(evaluation);
	}

	// --- helpers ---------------------------------------------------------------------

	/** @return Mr X's location, if the newest log entry is a reveal. */
	private static Optional<Integer> revealedLocation(Board board) {
		ImmutableList<LogEntry> log = board.getMrXTravelLog();
		if (log.isEmpty()) return Optional.empty();
		return log.get(log.size() - 1).location();
	}

	private static int destinationOf(Move move) {
		return move.visit(new Move.FunctionalVisitor<Integer>(
				single -> single.destination,
				dubble -> dubble.destination2));
	}
}
