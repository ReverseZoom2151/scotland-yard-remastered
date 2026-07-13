package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Scores a position from Mr X's point of view: high is good for Mr X, low is
 * good for the detectives. The search maximises for Mr X and minimises for them,
 * so both sides read the same number.
 *
 * <p>
 * Four things decide how safe a station is, and they multiply rather than add, so
 * that a position which is catastrophic on any single axis is vetoed outright
 * instead of being averaged back into respectability:
 *
 * <ul>
 * <li><b>distance</b> — how far the detectives are, counted in tickets they can
 * actually pay for, weighted towards the <i>nearest</i> one, and <b>saturated</b>:
 * see below;
 * <li><b>freedom</b> — how many onward moves the station leaves; a dead end is a
 * trap even if nobody is near it yet;
 * <li><b>threat</b> — whether a detective can reach the station on its next move,
 * graded by how many ways out remain if one does;
 * <li><b>ambiguity</b> — how uncertain the detectives' inferred belief about Mr X's
 * location is, measured as Shannon entropy.
 * </ul>
 *
 * <h2>Why the distance term saturates</h2>
 *
 * A monotone distance term says "further is always better", and the furthest points
 * on the London map are its corners: low-degree stations with two ways out, which is
 * where a monotone term will happily walk Mr X and where the detectives will
 * cheerfully corner him. Beyond about four hops the marginal hop buys nothing — the
 * detectives cannot close four hops in one turn either way — so the term is capped
 * (see {@link EvalWeights#distanceCap()}) and freedom decides among the positions
 * that are all "far enough". A measured prior on strong hiders is humped rather than
 * flat, peaking at two to three hops, and is available as an alternative weighting
 * via {@link EvalWeights#useHumpPrior()}.
 *
 * <h2>Why the threat term is graded and not a sentinel</h2>
 *
 * "A detective can reach me next move" used to return a huge negative constant. In
 * the endgame that is true of every move Mr X has, so every root move tied at the
 * same value and alpha-beta returned whichever it happened to see first: in the
 * crisis, where the search matters most, Mr X played at random. A non-terminal node
 * must never return a sentinel. Instead the threat is a multiplicative discount
 * graded by {@code escapeCount} — the onward stations that are payable, unoccupied
 * and not themselves covered by a detective — so that "cornered with four ways out"
 * is properly better than "cornered with none".
 *
 * <h2>Why ambiguity is not cheating</h2>
 *
 * The belief is {@link Suspicion#likelihoods(Board)}, which is computed from the
 * <b>public</b> board alone: the travel log, the map and where the detectives stand.
 * Mr X is reasoning about what his opponents can infer, which is exactly what a
 * fugitive does. Entropy rather than candidate-set size, because a hundred candidates
 * all clustered on top of him is worth nothing and two candidates at opposite ends of
 * the map is worth a great deal.
 */
public final class Evaluator {

	/** Mr X has been caught. A true terminal: the game is over. */
	public static final int MRX_CAPTURED = Integer.MIN_VALUE + 1;

	/** Mr X has survived the full travel log. A true terminal: the game is over. */
	public static final int MRX_ESCAPED = Integer.MAX_VALUE - 1;

	/**
	 * Ceiling and floor for ordinary positions. Everything the multiplicative
	 * combination produces is clamped into this band, which is orders of magnitude
	 * inside the sentinels: alpha-beta compares scores, so a normal position that
	 * happened to land on a sentinel would be read as a terminal one and prune the
	 * wrong subtree.
	 */
	private static final int MAX_NORMAL = 1_000_000;

	/**
	 * The floor for an ordinary position. Strictly positive, which is what keeps the
	 * multiplicative discounts below meaningful: halving a negative score improves it.
	 */
	private static final int MIN_NORMAL = 1;

	/**
	 * Distances are capped before they enter the arithmetic. Unreachable detectives
	 * come back as {@link Integer#MAX_VALUE}, which would otherwise dominate the mean
	 * and overflow the product.
	 */
	private static final int MAX_USEFUL_DISTANCE = 20;

	/** Scale on the whole product; keeps it far inside the clamp band. */
	private static final int PRODUCT_SCALE = 100;

	/**
	 * The measured prior on where a strong hider stands, relative to the nearest
	 * detective: indexed by distance, from 1. It peaks at two to three hops — close
	 * enough that the detectives cannot commit to a direction, far enough that they
	 * cannot reach him — and falls away past four, because a Mr X who has run to the
	 * edge of the map has run out of map.
	 */
	private static final double[] HUMP_PRIOR = {0.196, 0.671, 0.540, 0.384, 0.196};

	/** Scale on the hump prior, so its output is comparable with the saturating term. */
	private static final double HUMP_SCALE = 4.0;

	/** The worst a graded threat can discount a position to, when there is no way out. */
	private static final double THREAT_FLOOR = 0.03;

	/** How much of the position each remaining escape route buys back. */
	private static final double THREAT_PER_ESCAPE = 0.05;

	/** A threatened position is never worth more than this share of a safe one. */
	private static final double THREAT_CEILING = 0.33;

	private final Distances distances;
	private final EvalWeights weights;

	/** Builds an evaluator with the tuned default weights. */
	public Evaluator(Distances distances) {
		this(distances, EvalWeights.defaults());
	}

	public Evaluator(Distances distances, EvalWeights weights) {
		this.distances = distances;
		this.weights = weights;
	}

	public EvalWeights weights() {
		return this.weights;
	}

	/**
	 * Scores a board on which Mr X's location is known, with no belief information —
	 * the entropy term falls back to the belief the public board itself implies.
	 *
	 * @param board       the position to score
	 * @param mrXLocation where Mr X stands
	 * @return the score, from Mr X's point of view
	 */
	public int score(Board board, int mrXLocation) {
		return score(board, mrXLocation, entropyOf(Suspicion.likelihoods(board)), false);
	}

	/**
	 * Scores a board, given what the detectives believe.
	 *
	 * <p>
	 * The search maintains the belief incrementally down each line, so it can hand it
	 * in rather than paying for {@link Suspicion#likelihoods(Board)} at every leaf —
	 * which, measured, costs about a third of the search's whole node budget.
	 *
	 * @param board             the position to score
	 * @param mrXLocation       where Mr X stands (his <i>true</i> location)
	 * @param beliefEntropy     the normalised Shannon entropy, in [0, 1], of the
	 *                          detectives' inferred distribution over his location
	 * @param arrivedAtAReveal  whether the move into this position was published, so
	 *                          that Mr X must re-expand from a station the detectives
	 *                          know the number of
	 * @return the score, from Mr X's point of view
	 */
	public int score(Board board, int mrXLocation, double beliefEntropy, boolean arrivedAtAReveal) {
		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			final Optional<Integer> at = detectiveLocation(board, piece);
			if (at.isPresent() && at.get() == mrXLocation) return MRX_CAPTURED;
		}

		if (board.getMrXTravelLog().size() >= board.getSetup().rounds.size()) return MRX_ESCAPED;

		final double proximity = detectiveProximity(board, mrXLocation);

		// A reveal collapses the detectives' belief to a point. The only thing that
		// undoes that is re-expansion, and re-expansion is exactly what a high-degree,
		// multi-modal station gives him — so on the reveal itself, freedom is what he is
		// really buying, and it is priced accordingly.
		final double freedomWeight = arrivedAtAReveal
				? this.weights.freedomWeight() * this.weights.revealFreedomBoost()
				: this.weights.freedomWeight();
		final int room = Math.min(freedom(board, mrXLocation), this.weights.freedomCap());

		double value = PRODUCT_SCALE * proximity * (1.0 + freedomWeight * room);

		// Ambiguity. Multiplicative, so that being hard to locate is worth more when
		// there is more position to protect — which is the right way round.
		final double entropy = Math.max(0.0, Math.min(1.0, beliefEntropy));
		value *= 1.0 + this.weights.entropyAlpha() * entropy;

		// Graded threat, never a sentinel.
		if (reachableByDetectiveNextMove(board, mrXLocation)) {
			value *= threatFactor(escapeCount(board, mrXLocation));
		}

		return clamp(value);
	}

	/**
	 * @param escapes onward stations that are payable, unoccupied, and not themselves
	 *                reachable by a detective next move
	 * @return the share of its face value a threatened position keeps
	 */
	private static double threatFactor(int escapes) {
		return Math.min(THREAT_CEILING, THREAT_FLOOR + THREAT_PER_ESCAPE * escapes);
	}

	/**
	 * @return how far the detectives are from {@code mrXLocation}, weighted towards the
	 *         nearest and saturated at {@link EvalWeights#distanceCap()}
	 */
	double detectiveProximity(Board board, int mrXLocation) {
		int nearest = MAX_USEFUL_DISTANCE;
		long restTotal = 0;
		int restCount = 0;
		boolean any = false;

		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			final Optional<Integer> at = detectiveLocation(board, piece);
			if (at.isEmpty()) continue;
			final int distance =
					cap(this.distances.ticketAwareDistance(board, piece, at.get(), mrXLocation));
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

		if (!any) return saturate(MAX_USEFUL_DISTANCE); // no detectives: maximally safe

		final double restMean = restCount == 0 ? nearest : (double) restTotal / restCount;
		final double weighted = this.weights.nearestWeight() * saturate(nearest)
				+ this.weights.restWeight() * saturate((int) Math.round(restMean));

		// Never zero: a zero here would annihilate the product and make every crowded
		// position look identical — the very flattening this evaluator exists to avoid.
		return Math.max(0.1, weighted);
	}

	/**
	 * The distance term proper: either linear-and-capped, or the measured hump prior.
	 */
	private double saturate(int distance) {
		if (this.weights.useHumpPrior()) {
			final int index = Math.max(1, Math.min(distance, HUMP_PRIOR.length)) - 1;
			return HUMP_SCALE * HUMP_PRIOR[index];
		}
		return Math.min(Math.max(distance, 0), this.weights.distanceCap());
	}

	/**
	 * @return how many unoccupied stations Mr X could move to from {@code mrXLocation},
	 *         given the tickets he holds
	 */
	int freedom(Board board, int mrXLocation) {
		return onwardStations(board, mrXLocation).size();
	}

	/**
	 * @return how many of Mr X's onward stations are still there once the ones a
	 *         detective covers next move are struck off — the number that decides how
	 *         bad "a detective is one step away" actually is
	 */
	int escapeCount(Board board, int mrXLocation) {
		int escapes = 0;
		for (int onward : onwardStations(board, mrXLocation)) {
			if (!reachableByDetectiveNextMove(board, onward)) escapes++;
		}
		return escapes;
	}

	/** @return the unoccupied stations Mr X can pay to reach in one hop. */
	private Set<Integer> onwardStations(Board board, int mrXLocation) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		if (!graph.nodes().contains(mrXLocation)) return Set.of();

		final Set<Integer> occupied = occupiedStations(board);
		final Board.TicketBoard tickets = ticketsOf(board, mrXPiece(board));
		if (tickets == null) return Set.of();

		// Distinct destinations, not distinct moves: two transports to the same station
		// leave Mr X in the same place, so they are one escape, not two.
		final Set<Integer> destinations = new HashSet<>();
		for (int neighbour : graph.adjacentNodes(mrXLocation)) {
			if (occupied.contains(neighbour)) continue;
			for (Transport transport : graph.edgeValueOrDefault(mrXLocation, neighbour, ImmutableSet.of())) {
				if (tickets.getCount(transport.requiredTicket()) > 0
						|| tickets.getCount(Ticket.SECRET) > 0) {
					destinations.add(neighbour);
					break;
				}
			}
		}
		return destinations;
	}

	/**
	 * @return whether any detective can land on {@code station} on its very next move
	 */
	boolean reachableByDetectiveNextMove(Board board, int station) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		if (!graph.nodes().contains(station)) return false;

		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective()) continue;
			final Optional<Integer> at = detectiveLocation(board, piece);
			if (at.isEmpty()) continue;
			final int from = at.get();
			if (!graph.nodes().contains(from)) continue;
			if (!graph.adjacentNodes(from).contains(station)) continue;

			final Board.TicketBoard tickets = ticketsOf(board, piece);
			if (tickets == null) continue;
			for (Transport transport : graph.edgeValueOrDefault(from, station, ImmutableSet.of())) {
				// Detectives never hold SECRET, so the required ticket is the only way over.
				if (tickets.getCount(transport.requiredTicket()) > 0) return true;
			}
		}
		return false;
	}

	/**
	 * The normalised Shannon entropy of a belief: 0 when the detectives know exactly
	 * where Mr X is, 1 when they are maximally spread over the candidates they have.
	 *
	 * <p>
	 * Normalised by {@code log(support)} rather than by {@code log(199)}: a belief
	 * spread evenly over six candidates is <i>total</i> ignorance given six candidates,
	 * and the search should not read it as near-certainty just because the map is
	 * large. Support size is separately rewarded by nothing at all, which is
	 * deliberate — a large but sharply peaked belief is not safety.
	 *
	 * @param belief station to probability, summing to one
	 * @return the entropy, in [0, 1]
	 */
	public static double entropyOf(Map<Integer, Double> belief) {
		if (belief == null || belief.size() <= 1) return 0.0;
		double entropy = 0;
		double total = 0;
		for (double p : belief.values()) {
			total += p;
		}
		if (total <= 0) return 0.0;
		for (double raw : belief.values()) {
			final double p = raw / total;
			if (p > 0) entropy -= p * Math.log(p);
		}
		final double max = Math.log(belief.size());
		if (max <= 0) return 0.0;
		return Math.max(0.0, Math.min(1.0, entropy / max));
	}

	// --- helpers ---------------------------------------------------------------

	/** @return the distance, with unreachable and far-away folded onto one ceiling. */
	private static int cap(int distance) {
		if (distance < 0 || distance > MAX_USEFUL_DISTANCE) return MAX_USEFUL_DISTANCE;
		return distance;
	}

	/** @return {@code value} squeezed into the band reserved for ordinary positions. */
	private static int clamp(double value) {
		if (Double.isNaN(value)) return MIN_NORMAL;
		if (value > MAX_NORMAL) return MAX_NORMAL;
		if (value < MIN_NORMAL) return MIN_NORMAL;
		return (int) Math.round(value);
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
