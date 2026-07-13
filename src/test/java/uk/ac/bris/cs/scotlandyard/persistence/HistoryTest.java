package uk.ac.bris.cs.scotlandyard.persistence;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece.Detective;
import uk.ac.bris.cs.scotlandyard.model.Piece.MrX;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Undo is list indexing, because the model never mutates a state.
 */
public class HistoryTest {

	private static final Player MR_X = new Player(MrX.MRX, ImmutableMap.of(
			Ticket.TAXI, 4, Ticket.BUS, 3, Ticket.UNDERGROUND, 3,
			Ticket.DOUBLE, 2, Ticket.SECRET, 5), 106);

	private static Player detective(Detective piece, int location) {
		return new Player(piece, ImmutableMap.of(
				Ticket.TAXI, 11, Ticket.BUS, 8, Ticket.UNDERGROUND, 4,
				Ticket.DOUBLE, 0, Ticket.SECRET, 0), location);
	}

	private static GameState initialState() {
		return new MyGameStateFactory().build(
				new GameSetup(GameRecord.standardGraph(), ScotlandYard.STANDARD24ROUNDS),
				MR_X,
				ImmutableList.of(detective(Detective.RED, 26), detective(Detective.GREEN, 29)));
	}

	/** Plays {@code count} half-moves into a fresh history. */
	private static History playInto(int count) {
		GameState state = initialState();
		History history = new History(state);
		int mrXLocation = MR_X.location();
		for (int i = 0; i < count; i++) {
			final int hidingAt = mrXLocation;
			Move move = state.getAvailableMoves().stream()
					.filter(m -> m.commencedBy().isMrX() || lastDestination(m) != hidingAt)
					.filter(m -> m.visit(new Move.FunctionalVisitor<>(single -> true, doubleMove -> false)))
					.findFirst().orElseThrow();
			if (move.commencedBy().isMrX()) {
				mrXLocation = lastDestination(move);
			}
			state = state.advance(move);
			history.push(move, state);
		}
		return history;
	}

	private static int lastDestination(Move move) {
		return move.visit(new Move.FunctionalVisitor<>(
				single -> single.destination, doubleMove -> doubleMove.destination2));
	}

	@Test
	public void aFreshHistoryHoldsOnlyTheInitialState() {
		GameState initial = initialState();
		History history = new History(initial);
		assertThat(history.size()).isEqualTo(1);
		assertThat(history.current()).isSameAs(initial);
		assertThat(history.canUndo()).isFalse();
		assertThat(history.canRedo()).isFalse();
		assertThat(history.movesToCurrent()).isEmpty();
	}

	@Test
	public void pushingKeepsStatesAndMovesInLockstep() {
		History history = playInto(3);
		assertThat(history.size()).isEqualTo(4);
		assertThat(history.allMoves()).hasSize(3);
		assertThat(history.cursorIndex()).isEqualTo(3);
		for (int i = 0; i < 3; i++) {
			// states[i] advanced by moves[i] is states[i + 1]
			assertThat(history.stateAt(i).getAvailableMoves()).contains(history.allMoves().get(i));
		}
	}

	@Test
	public void undoAndRedoWalkBackAndForthOverTheSameStates() {
		History history = playInto(4);
		GameState last = history.current();
		GameState third = history.stateAt(3);

		assertThat(history.canUndo()).isTrue();
		assertThat(history.undo()).isSameAs(third);
		assertThat(history.cursorIndex()).isEqualTo(3);
		assertThat(history.movesToCurrent()).hasSize(3);
		assertThat(history.canRedo()).isTrue();
		assertThat(history.redo()).isSameAs(last);
		assertThat(history.canRedo()).isFalse();
		assertThat(history.redo()).isSameAs(last);
	}

	@Test
	public void undoPastTheStartOfTheGameIsANoOp() {
		History history = playInto(2);
		history.undo();
		history.undo();
		assertThat(history.canUndo()).isFalse();
		GameState start = history.current();
		assertThat(history.undo()).isSameAs(start);
		assertThat(history.undo()).isSameAs(start);
		assertThat(history.cursorIndex()).isZero();
		assertThat(History.pieceToMove(start)).contains(MrX.MRX);
	}

	@Test
	public void pushingAfterAnUndoDiscardsTheRedoTail() {
		History history = playInto(3);
		Move discarded = history.allMoves().get(2);
		history.undo();

		GameState from = history.current();
		Move other = from.getAvailableMoves().stream()
				.filter(m -> !m.equals(discarded))
				.findFirst().orElseThrow();
		history.push(other, from.advance(other));

		assertThat(history.size()).isEqualTo(4);
		assertThat(history.canRedo()).isFalse();
		assertThat(history.allMoves()).hasSize(3).doesNotContain(discarded).contains(other);
	}

	@Test
	public void undoUntilPieceToMoveRewindsAWholePly() {
		// MrX, then both detectives: three half-moves, and it is MrX's turn again
		History history = playInto(3);
		assertThat(History.pieceToMove(history.current())).contains(MrX.MRX);

		GameState rewound = history.undoUntilPieceToMove(MrX.MRX);

		// back to MrX's *previous* turn, not merely one half-move back
		assertThat(History.pieceToMove(rewound)).contains(MrX.MRX);
		assertThat(history.cursorIndex()).isZero();
		assertThat(rewound.getMrXTravelLog()).isEmpty();
		assertThat(history.canRedo()).isTrue();
	}

	@Test
	public void undoUntilPieceToMoveAlwaysStepsBackAtLeastOnce() {
		History history = playInto(1);
		// it is a detective's turn; asking for the detective still gives up MrX's move
		assertThat(History.pieceToMove(history.current()).orElseThrow().isDetective()).isTrue();
		GameState rewound = history.undoUntilPieceToMove(Detective.RED);
		assertThat(history.cursorIndex()).isZero();
		assertThat(History.pieceToMove(rewound)).contains(MrX.MRX);
	}

	@Test
	public void undoUntilPieceToMoveStopsAtTheStartWhenThatTurnNeverComesRound() {
		History history = playInto(2);
		GameState rewound = history.undoUntilPieceToMove(Detective.BLUE);
		assertThat(history.cursorIndex()).isZero();
		assertThat(rewound).isSameAs(history.stateAt(0));
	}

	@Test
	public void statesRebuiltByReplayMatchTheStatesWalkedThroughByHand() {
		History history = playInto(5);
		GameRecord record = GameRecord.of(history.stateAt(0).getSetup(), MR_X,
				ImmutableList.of(detective(Detective.RED, 26), detective(Detective.GREEN, 29)),
				new ArrayList<>(history.allMoves()));

		List<GameState> replayed = record.replay().states();
		assertThat(replayed).hasSize(history.size());
		for (int i = 0; i < history.size(); i++) {
			assertThat(replayed.get(i).getMrXTravelLog()).isEqualTo(history.stateAt(i).getMrXTravelLog());
			assertThat(replayed.get(i).getAvailableMoves()).isEqualTo(history.stateAt(i).getAvailableMoves());
			assertThat(replayed.get(i).getDetectiveLocation(Detective.RED))
					.isEqualTo(history.stateAt(i).getDetectiveLocation(Detective.RED));
		}
	}
}
