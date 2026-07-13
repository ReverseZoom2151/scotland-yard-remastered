package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Hard filters on Mr X's action generation.
 *
 * <p>
 * The special tickets used to be priced: the evaluator subtracted a flat cost for
 * every secret and double already spent, and the search was left to decide whether
 * the escape was worth the money. That does not work, for a reason that is
 * arithmetic rather than tuning. The evaluator is <i>multiplicative</i>, so an
 * ordinary position scores anywhere from a couple of hundred to a few thousand;
 * a flat penalty of sixty is therefore two percent of the score when Mr X is in the
 * open and a third of it when he is cornered. The secret ticket looks most expensive
 * at exactly the moment he most needs it — precisely backwards.
 *
 * <p>
 * So the tickets are not priced, they are <b>gated</b>: a secret or a double is
 * simply not generated in the positions where it cannot possibly be worth it, and is
 * generated at full value everywhere else. A filter has no scale to get wrong.
 *
 * <p>
 * The gates are deliberately conservative — they only ever remove a move that is
 * <i>provably</i> pointless — and {@link #filter} will never hand back an empty
 * list. Mr X must always have a legal move.
 */
public final class MoveGates {

	/** Mean detective distance at or below which a double move is an escape, not a flourish. */
	private static final double DOUBLE_MEAN_DISTANCE = 3.0;

	/** Unreachable detectives, folded onto a ceiling so they cannot dominate the mean. */
	private static final int UNREACHABLE = 20;

	private MoveGates() {
	}

	/**
	 * Whether a secret ticket can buy any ambiguity at all in this round.
	 *
	 * <p>
	 * Two rounds where it cannot:
	 * <ul>
	 * <li><b>Rounds 1 and 2.</b> Mr X has not been revealed yet, so the detectives'
	 * belief is still the whole start pool blurred forward. Hiding <i>which</i> kind
	 * of ticket he spent narrows nothing they had narrowed; the secret is spent for
	 * literally zero information.
	 * <li><b>Any reveal round.</b> The log names the station. Concealing the transport
	 * used to arrive at a station the detectives are about to be told the number of is
	 * paying for a curtain in front of an open window.
	 * </ul>
	 *
	 * @param board the board (for the reveal schedule; never hardcode 3/8/13/18/24 —
	 *              the setup is authoritative and the tests use non-standard schedules)
	 * @param round the zero-based index of the log entry this move would write, i.e.
	 *              {@code board.getMrXTravelLog().size()} for the first hop of a move
	 * @return whether a secret ticket is worth generating
	 */
	public static boolean secretAllowed(Board board, int round) {
		// Zero-based: log indices 0 and 1 are the game's rounds 1 and 2.
		if (round <= 1) return false;
		final ImmutableList<Boolean> rounds = board.getSetup().rounds;
		if (round < 0 || round >= rounds.size()) return false;
		return !Boolean.TRUE.equals(rounds.get(round));
	}

	/**
	 * Whether a secret ticket out of {@code source} tells the detectives less than the
	 * ordinary ticket for the same edge would.
	 *
	 * <p>
	 * The detectives infer from the <i>ticket</i>: a logged taxi means Mr X is
	 * somewhere in the taxi-neighbourhood of where he was, a logged secret means he is
	 * somewhere in the <i>whole</i> neighbourhood. If every edge out of {@code source}
	 * is a taxi edge, those two sets are the same set, and the secret has bought
	 * nothing whatsoever. So: allow the secret only when the set of stations reachable
	 * by <i>any</i> transport is strictly larger than the set reachable by the ticket
	 * he would otherwise have spent.
	 *
	 * <p>
	 * SHARPER VERSION (documented, not implemented): the structural test above compares
	 * successor sets of a <i>single</i> station, but the detectives' belief is a set of
	 * candidate stations, and the quantity that actually matters is how much
	 * <i>that</i> set grows. The sharper gate projects the real candidate set
	 * ({@link MrXLocator#possibleLocations(Board)}) forward twice — once under the
	 * plain ticket, once under a secret — and allows the secret only if the projected
	 * candidate set grows by at least two stations. It is strictly better and strictly
	 * more expensive: it is O(|candidates| x degree) per generated move rather than
	 * O(degree), and it runs inside the branching of every node of Mr X's tree. The
	 * simple version captures the large majority of the wasted secrets (they are
	 * overwhelmingly spent on the map's many taxi-only stations) at a hundredth of the
	 * cost, so that is what ships.
	 *
	 * @param board       the board, for the map and Mr X's tickets
	 * @param source      where Mr X stands
	 * @param destination where the secret would take him
	 * @return whether the secret conceals anything
	 */
	public static boolean secretIsInformative(Board board, int source, int destination) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		if (!graph.nodes().contains(source) || !graph.nodes().contains(destination)) return false;

		final ImmutableSet<Transport> onEdge =
				graph.edgeValueOrDefault(source, destination, ImmutableSet.of());
		if (onEdge.isEmpty()) return false;

		// The ticket he would otherwise have spent on this edge. A ferry edge has no
		// such ticket — its required ticket *is* SECRET — so the secret is the only way
		// across and the gate must let it through.
		final Optional<Ticket> alternative = plainTicketFor(board, onEdge);
		if (alternative.isEmpty()) return true;

		final Set<Integer> byAnyTransport = new HashSet<>(graph.adjacentNodes(source));
		final Set<Integer> byPlainTicket = new HashSet<>();
		for (int neighbour : graph.adjacentNodes(source)) {
			for (Transport transport : graph.edgeValueOrDefault(source, neighbour, ImmutableSet.of())) {
				if (transport.requiredTicket() == alternative.get()) {
					byPlainTicket.add(neighbour);
					break;
				}
			}
		}
		return byAnyTransport.size() > byPlainTicket.size();
	}

	/**
	 * Whether a double move is an escape rather than a flourish.
	 *
	 * <p>
	 * There are two doubles in the whole game and they are the only way to put two
	 * stations between Mr X and a closing net in one turn. Spent in the open they buy a
	 * tempo he did not need and cost him the one card that gets him out of the corner
	 * he will be in on round nineteen. So: only when the detectives are, on average,
	 * genuinely close.
	 *
	 * @param board       the board
	 * @param mrXLocation where Mr X stands
	 * @param distances   precomputed map distances
	 * @return whether a double is worth generating
	 */
	public static boolean doubleAllowed(Board board, int mrXLocation, Distances distances) {
		double total = 0;
		int count = 0;
		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			if (!(piece instanceof Piece.Detective detective)) continue;
			final Optional<Integer> at = board.getDetectiveLocation(detective);
			if (at.isEmpty()) continue;
			final int distance =
					distances.ticketAwareDistance(board, piece, at.get(), mrXLocation);
			total += (distance < 0 || distance > UNREACHABLE) ? UNREACHABLE : distance;
			count++;
		}
		if (count == 0) return false; // nobody to escape from
		return total / count <= DOUBLE_MEAN_DISTANCE;
	}

	/**
	 * Applies every gate to the moves Mr X's search would branch over.
	 *
	 * @param board       the board the moves came from
	 * @param mrXLocation where Mr X stands
	 * @param round       the zero-based log index the next hop would write
	 * @param moves       Mr X's legal moves
	 * @param distances   precomputed map distances
	 * @return the moves worth searching — never empty, and always a subset of
	 *         {@code moves}, so anything picked from it is legal
	 */
	public static ImmutableList<Move> filter(Board board, int mrXLocation, int round,
			Collection<Move> moves, Distances distances) {
		final boolean doublesAllowed = doubleAllowed(board, mrXLocation, distances);
		final List<Move> kept = new ArrayList<>(moves.size());
		for (Move move : moves) {
			if (allowed(board, round, move, doublesAllowed)) kept.add(move);
		}
		// The gates are a heuristic; legality is not. If they reject everything — a
		// cornered Mr X whose only moves are doubles, say — the unfiltered set stands.
		if (kept.isEmpty()) return ImmutableList.copyOf(moves);
		return ImmutableList.copyOf(kept);
	}

	/** @return whether a single move survives the gates. */
	private static boolean allowed(Board board, int round, Move move, boolean doublesAllowed) {
		final List<Ticket> tickets = new ArrayList<>();
		move.tickets().forEach(tickets::add);
		final boolean isDouble = tickets.size() > 1;
		if (isDouble && !doublesAllowed) return false;

		int hop = round;
		int from = move.source();
		for (Ticket ticket : tickets) {
			final int to = hopDestination(move, hop - round);
			if (ticket == Ticket.SECRET) {
				if (!secretAllowed(board, hop)) return false;
				if (!secretIsInformative(board, from, to)) return false;
			}
			from = to;
			hop++;
		}
		return true;
	}

	/** @return the station a move's {@code index}-th hop lands on (0-based). */
	private static int hopDestination(Move move, int index) {
		return move.visit(new Move.FunctionalVisitor<Integer>(
				single -> single.destination,
				dubble -> index == 0 ? dubble.destination1 : dubble.destination2));
	}

	/**
	 * @return the ticket Mr X would have spent on this edge had he not used a secret,
	 *         preferring one he actually holds; empty if the edge can only be crossed
	 *         with a secret (a ferry)
	 */
	private static Optional<Ticket> plainTicketFor(Board board, ImmutableSet<Transport> onEdge) {
		final Optional<Board.TicketBoard> held = board.getPlayerTickets(Piece.MrX.MRX);
		Ticket fallback = null;
		for (Transport transport : onEdge) {
			final Ticket ticket = transport.requiredTicket();
			if (ticket == Ticket.SECRET) continue;
			if (held.isPresent() && held.get().getCount(ticket) > 0) return Optional.of(ticket);
			if (fallback == null) fallback = ticket;
		}
		return Optional.ofNullable(fallback);
	}
}
