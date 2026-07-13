package uk.ac.bris.cs.scotlandyard.persistence;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nonnull;

import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;

/**
 * The undo/redo engine. UI agnostic and free of any inverse-command machinery:
 * because {@link GameState} is immutable, every state the game has ever been in
 * is still alive, so undo is nothing but moving a cursor back along a list.
 *
 * <p>
 * States and moves are kept in lockstep: {@code states[i]} is the state before
 * {@code moves[i]}, and {@code states[i + 1]} the state after it.
 */
public final class History {

	private final List<GameState> states = new ArrayList<>();
	private final List<Move> moves = new ArrayList<>();
	private int cursor;

	/**
	 * @param initial the state the game started from
	 */
	public History(@Nonnull GameState initial) {
		states.add(Objects.requireNonNull(initial, "initial"));
		this.cursor = 0;
	}

	/**
	 * Records a move made from the current state. Anything that had been undone is
	 * discarded, exactly as a text editor would.
	 *
	 * @param move the move that was made
	 * @param next the state it produced
	 */
	public void push(@Nonnull Move move, @Nonnull GameState next) {
		Objects.requireNonNull(move, "move");
		Objects.requireNonNull(next, "next");
		// drop the redo tail, it is no longer reachable
		while (states.size() > cursor + 1) {
			states.remove(states.size() - 1);
			moves.remove(moves.size() - 1);
		}
		moves.add(move);
		states.add(next);
		cursor++;
	}

	/** @return the number of states, which is one more than the number of moves */
	public int size() {
		return states.size();
	}

	/** @return how many moves have been made to reach {@link #current()} */
	public int cursorIndex() {
		return cursor;
	}

	/**
	 * @param index the index of the state
	 * @return the state at that index; index 0 is the initial state
	 */
	@Nonnull
	public GameState stateAt(int index) {
		return states.get(index);
	}

	/** @return the state the game is in now */
	@Nonnull
	public GameState current() {
		return states.get(cursor);
	}

	/** @return whether a move has been made that can be taken back */
	public boolean canUndo() {
		return cursor > 0;
	}

	/** @return whether a move has been undone that can be put back */
	public boolean canRedo() {
		return cursor < states.size() - 1;
	}

	/**
	 * Steps back one half-move.
	 *
	 * @return the previous state, or the current one if there is nothing to undo
	 */
	@Nonnull
	public GameState undo() {
		if (canUndo()) {
			cursor--;
		}
		return current();
	}

	/**
	 * Steps forward one half-move.
	 *
	 * @return the next state, or the current one if there is nothing to redo
	 */
	@Nonnull
	public GameState redo() {
		if (canRedo()) {
			cursor++;
		}
		return current();
	}

	/**
	 * Rewinds until it is the given piece's turn again. A plain {@link #undo()} is
	 * useless against an AI opponent, which would simply move again; this steps back
	 * a whole ply so the human gets the turn they asked for.
	 *
	 * @param piece the piece that should be to move afterwards
	 * @return the state reached; the start of the game if that turn never comes
	 *         round again
	 */
	@Nonnull
	public GameState undoUntilPieceToMove(@Nonnull Piece piece) {
		Objects.requireNonNull(piece, "piece");
		while (canUndo()) {
			undo();
			if (pieceToMove(current()).filter(piece::equals).isPresent()) {
				break;
			}
		}
		return current();
	}

	/** @return the moves that lead to {@link #current()}, in order */
	@Nonnull
	public ImmutableList<Move> movesToCurrent() {
		return ImmutableList.copyOf(moves.subList(0, cursor));
	}

	/** @return every move, including any that have been undone */
	@Nonnull
	public ImmutableList<Move> allMoves() {
		return ImmutableList.copyOf(moves);
	}

	/**
	 * @param state the state
	 * @return the piece due to move, empty if the game is over. Detectives move in
	 *         one rotation, so during their half of a round this is simply whichever
	 *         of them the model offers first.
	 */
	@Nonnull
	public static Optional<Piece> pieceToMove(@Nonnull GameState state) {
		Objects.requireNonNull(state, "state");
		return state.getAvailableMoves().stream().findFirst().map(Move::commencedBy);
	}
}
