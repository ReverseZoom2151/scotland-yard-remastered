package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;

/**
 * Rebuilds an advanceable {@link GameState} from a {@link Board}.
 *
 * <p>
 * The AI is handed a {@code Board}, which has no {@code advance}, so a search
 * cannot step it forward. Everything needed to rebuild one is on the board —
 * the setup, the detectives' locations, everyone's tickets and the travel log —
 * with one exception: <b>Mr X's location is not</b>. Playing as Mr X you can
 * recover it, since {@code move.source()} on any available move is where he
 * stands. Playing as a detective you cannot, and must supply a hypothesis; see
 * {@link MrXLocator}.
 *
 * <p>
 * The board handed to the AI may in practice <i>be</i> a {@code GameState}, but
 * downcasting it would hand a detective Mr X's true hidden location. Don't.
 */
public final class BoardStates {

	private BoardStates() {
	}

	/**
	 * @param board a board on which it is Mr X's turn
	 * @return Mr X's location, read off the source of any move he can make
	 * @throws IllegalArgumentException if it is not Mr X's turn
	 */
	public static int mrXLocationOf(Board board) {
		for (Move move : board.getAvailableMoves()) {
			if (move.commencedBy().isMrX()) return move.source();
		}
		throw new IllegalArgumentException(
				"Mr X has no available moves on this board; it is not his turn, so his location cannot be recovered");
	}

	/**
	 * @return the tickets {@code piece} holds, as a map
	 */
	public static ImmutableMap<Ticket, Integer> ticketsOf(Board board, Piece piece) {
		final Optional<Board.TicketBoard> tickets = board.getPlayerTickets(piece);
		if (tickets.isEmpty()) {
			throw new IllegalArgumentException("No such player on this board: " + piece);
		}
		final Board.TicketBoard held = tickets.get();
		final ImmutableMap.Builder<Ticket, Integer> builder = ImmutableMap.builder();
		for (Ticket ticket : Ticket.values()) {
			builder.put(ticket, held.getCount(ticket));
		}
		return builder.build();
	}

	/** @return every detective on the board, as {@link Player}s. */
	public static ImmutableList<Player> detectivesOf(Board board) {
		final ImmutableList.Builder<Player> detectives = ImmutableList.builder();
		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			final Optional<Integer> location = board.getDetectiveLocation((Piece.Detective) piece);
			if (location.isEmpty()) {
				throw new IllegalArgumentException("Detective " + piece + " has no location on this board");
			}
			detectives.add(new Player(piece, detectiveTickets(board, piece), location.get()));
		}
		return detectives.build();
	}

	/**
	 * Detective ticket maps with SECRET and DOUBLE zeroed: the game-state factory
	 * rejects a detective holding either, and a board's ticket board may report
	 * them (as zero, in practice) for every ticket type.
	 */
	private static ImmutableMap<Ticket, Integer> detectiveTickets(Board board, Piece piece) {
		final ImmutableMap<Ticket, Integer> tickets = ticketsOf(board, piece);
		final ImmutableMap.Builder<Ticket, Integer> builder = ImmutableMap.builder();
		for (Ticket ticket : Ticket.values()) {
			final boolean forbidden = ticket == Ticket.SECRET || ticket == Ticket.DOUBLE;
			builder.put(ticket, forbidden ? 0 : tickets.getOrDefault(ticket, 0));
		}
		return builder.build();
	}

	/**
	 * Rebuilds a searchable state, placing Mr X at {@code mrXLocation}.
	 *
	 * <p>
	 * Playing as Mr X, pass {@link #mrXLocationOf(Board)}. Playing as a detective,
	 * pass a candidate from {@link MrXLocator} — the returned state is then one
	 * hypothesis among many, not the truth.
	 *
	 * @param board       the board to rebuild from
	 * @param mrXLocation where to place Mr X
	 * @return a state that can be advanced
	 */
	public static GameState rebuild(Board board, int mrXLocation) {
		final Player mrX = new Player(Piece.MrX.MRX, ticketsOf(board, Piece.MrX.MRX), mrXLocation);
		return new MyGameStateFactory().build(board.getSetup(), mrX, detectivesOf(board));
	}
}
