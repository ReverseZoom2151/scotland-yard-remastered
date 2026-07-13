package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Scores a position from Mr X's point of view: high is good for Mr X, low is
 * good for the detectives. The search maximises for Mr X and minimises for them,
 * so both sides read the same number.
 *
 * <p>
 * Three things decide how safe a station is, and they multiply rather than add,
 * so that a position which is catastrophic on any single axis is vetoed outright
 * instead of being averaged back into respectability:
 *
 * <ul>
 * <li><b>distance</b> — how far the detectives are, counted in tickets they can
 * actually pay for, and weighted towards the <i>nearest</i> one, since that is
 * what catches you;
 * <li><b>freedom</b> — how many onward moves the station leaves; a dead end is a
 * trap even if nobody is near it yet;
 * <li><b>safety</b> — whether a detective can reach the station on its next move,
 * which is fatal regardless of the other two.
 * </ul>
 *
 * Tickets are hoarded: spending a secret or a double is a real cost, worth paying
 * only to escape.
 */
public final class Evaluator {

	/** Mr X has been caught, or is about to be with no way out. */
	public static final int MRX_CAPTURED = Integer.MIN_VALUE + 1;

	/** Mr X has survived the full travel log. */
	public static final int MRX_ESCAPED = Integer.MAX_VALUE - 1;

	/**
	 * A detective is one move from Mr X. Not quite capture — a double move or a
	 * secret ticket may still save him — but bad enough that he must never pick it
	 * when anything else exists. Kept far above {@link #MRX_CAPTURED} so that
	 * actual capture is still strictly worse.
	 */
	private static final int NEAR_CAPTURE = MRX_CAPTURED / 2;

	/**
	 * Ceiling and floor for ordinary positions. Everything the multiplicative
	 * combination produces is clamped into this band, which is orders of magnitude
	 * inside the sentinels: alpha-beta compares scores, so a normal position that
	 * happened to land on a sentinel would be read as a terminal one and prune the
	 * wrong subtree.
	 */
	private static final int MAX_NORMAL = 1_000_000;
	private static final int MIN_NORMAL = -1_000_000;

	/**
	 * Distances are capped before they enter the arithmetic. Unreachable detectives
	 * come back as {@link Integer#MAX_VALUE}, which would otherwise dominate the
	 * mean and overflow the product; and beyond a handful of hops extra distance
	 * buys Mr X nothing anyway, so the cap costs no information.
	 */
	private static final int MAX_USEFUL_DISTANCE = 20;

	/** Weight on the nearest detective — the one that actually catches you. */
	private static final double NEAREST_WEIGHT = 0.6;

	/** Weight on the mean of the others — they still cut off escape routes. */
	private static final double REST_WEIGHT = 0.4;

	/**
	 * Scale on the proximity × freedom product. Large enough that the ticket
	 * penalties below are a nudge rather than a veto, small enough that the product
	 * (at most 20 × ~10 × 100 = 20 000) stays far inside the clamp band.
	 */
	private static final int PRODUCT_SCALE = 100;

	/**
	 * Cost of a spent secret and of a spent double. A double is dearer: it is the
	 * only way to jump two stations clear of a closing net, and there are just two
	 * of them. Both are priced well below a single hop of distance (100 × freedom),
	 * so Mr X will always spend one to escape — he just will not spend one idly.
	 */
	private static final int SECRET_SPENT_PENALTY = 60;
	private static final int DOUBLE_SPENT_PENALTY = 250;

	private final Distances distances;

	public Evaluator(Distances distances) {
		this.distances = distances;
	}

	/**
	 * Scores a board on which Mr X's location is known.
	 *
	 * @param board       the position to score
	 * @param mrXLocation where Mr X stands
	 * @return the score, from Mr X's point of view
	 */
	public int score(Board board, int mrXLocation) {
		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			final Optional<Integer> at = detectiveLocation(board, piece);
			if (at.isPresent() && at.get() == mrXLocation) return MRX_CAPTURED;
		}

		if (reachableByDetectiveNextMove(board, mrXLocation)) return NEAR_CAPTURE;

		if (board.getMrXTravelLog().size() >= board.getSetup().rounds.size()) return MRX_ESCAPED;

		final long proximity = detectiveProximity(board, mrXLocation);
		final long room = freedom(board, mrXLocation);

		// Multiplicative: no amount of open space rescues a station with a detective
		// breathing on it, and no amount of distance rescues a dead end.
		long value = proximity * room * PRODUCT_SCALE;

		value -= (long) spent(board, Ticket.SECRET) * SECRET_SPENT_PENALTY;
		value -= (long) spent(board, Ticket.DOUBLE) * DOUBLE_SPENT_PENALTY;

		return clamp(value);
	}

	/**
	 * @return how far the detectives are from {@code mrXLocation}, weighted towards
	 *         the nearest
	 */
	int detectiveProximity(Board board, int mrXLocation) {
		int nearest = MAX_USEFUL_DISTANCE;
		long restTotal = 0;
		int restCount = 0;
		boolean any = false;

		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			final Optional<Integer> at = detectiveLocation(board, piece);
			if (at.isEmpty()) continue;
			final int distance = cap(this.distances.ticketAwareDistance(board, piece, at.get(), mrXLocation));
			if (!any || distance < nearest) {
				// The old nearest demotes into the "rest" pool.
				if (any) {
					restTotal += nearest;
					restCount++;
				}
				nearest = distance;
				any = true;
			} else {
				restTotal += distance;
				restCount++;
			}
		}

		if (!any) return MAX_USEFUL_DISTANCE; // no detectives on the board: maximally safe

		final double restMean = restCount == 0 ? nearest : (double) restTotal / restCount;
		final double weighted = NEAREST_WEIGHT * nearest + REST_WEIGHT * restMean;

		// Never zero: a zero here would annihilate the product and make every
		// crowded position look identical.
		return Math.max(1, (int) Math.round(weighted));
	}

	/**
	 * @return how many unoccupied stations Mr X could move to from
	 *         {@code mrXLocation}, given the tickets he holds
	 */
	int freedom(Board board, int mrXLocation) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		if (!graph.nodes().contains(mrXLocation)) return 0;

		final Set<Integer> occupied = occupiedStations(board);
		final Board.TicketBoard tickets = ticketsOf(board, mrXPiece(board));
		if (tickets == null) return 0;

		// Distinct destinations, not distinct moves: two transports to the same
		// station leave Mr X in the same place, so they are one escape, not two.
		final Set<Integer> destinations = new HashSet<>();
		for (int neighbour : graph.adjacentNodes(mrXLocation)) {
			if (occupied.contains(neighbour)) continue;
			for (Transport transport : graph.edgeValueOrDefault(mrXLocation, neighbour, ImmutableSet.of())) {
				if (tickets.getCount(transport.requiredTicket()) > 0 || tickets.getCount(Ticket.SECRET) > 0) {
					destinations.add(neighbour);
					break;
				}
			}
		}
		return destinations.size();
	}

	/**
	 * @return whether any detective can land on {@code mrXLocation} on its very next
	 *         move
	 */
	boolean reachableByDetectiveNextMove(Board board, int mrXLocation) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		if (!graph.nodes().contains(mrXLocation)) return false;

		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			final Optional<Integer> at = detectiveLocation(board, piece);
			if (at.isEmpty()) continue;
			final int from = at.get();
			if (!graph.nodes().contains(from)) continue;
			if (!graph.adjacentNodes(from).contains(mrXLocation)) continue;

			final Board.TicketBoard tickets = ticketsOf(board, piece);
			if (tickets == null) continue;
			for (Transport transport : graph.edgeValueOrDefault(from, mrXLocation, ImmutableSet.of())) {
				// Detectives never hold SECRET, so the required ticket is the only way over.
				if (tickets.getCount(transport.requiredTicket()) > 0) return true;
			}
		}
		return false;
	}

	// --- helpers ---------------------------------------------------------------

	/** @return the distance, with unreachable and far-away folded onto one ceiling. */
	private static int cap(int distance) {
		if (distance < 0 || distance > MAX_USEFUL_DISTANCE) return MAX_USEFUL_DISTANCE;
		return distance;
	}

	/** @return {@code value} squeezed into the band reserved for ordinary positions. */
	private static int clamp(long value) {
		if (value > MAX_NORMAL) return MAX_NORMAL;
		if (value < MIN_NORMAL) return MIN_NORMAL;
		return (int) value;
	}

	/** @return how many of {@code ticket} Mr X has already spent, from his starting hand. */
	private static int spent(Board board, Ticket ticket) {
		final Board.TicketBoard tickets = ticketsOf(board, mrXPiece(board));
		if (tickets == null) return 0;
		final Integer start = ScotlandYard.defaultMrXTickets().get(ticket);
		if (start == null) return 0;
		return Math.max(0, start - tickets.getCount(ticket));
	}

	/** @return Mr X's piece as the board reports it, falling back on the singleton. */
	private static Piece mrXPiece(Board board) {
		for (Piece piece : board.getPlayers()) {
			if (piece.isMrX()) return piece;
		}
		return Piece.MrX.MRX;
	}

	/** @return the piece's ticket board, or {@code null} if the board has none for it. */
	private static Board.TicketBoard ticketsOf(Board board, Piece piece) {
		return board.getPlayerTickets(piece).orElse(null);
	}

	/** @return where a detective piece stands, if the board will say. */
	private static Optional<Integer> detectiveLocation(Board board, Piece piece) {
		if (!(piece instanceof Piece.Detective detective)) return Optional.empty();
		return board.getDetectiveLocation(detective);
	}

	/** @return every station a detective currently stands on. */
	private static Set<Integer> occupiedStations(Board board) {
		final Set<Integer> occupied = new HashSet<>();
		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			detectiveLocation(board, piece).ifPresent(occupied::add);
		}
		return occupied;
	}
}
