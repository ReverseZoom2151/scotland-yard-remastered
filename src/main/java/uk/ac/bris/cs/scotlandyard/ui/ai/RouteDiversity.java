package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;

/**
 * Spreads the detectives out.
 *
 * <p>
 * <b>The problem.</b> Five detectives each independently walking towards the single
 * most likely Mr X station is five detectives walking down the same corridor. They
 * duplicate one another's coverage, and whole regions of the belief are left with
 * nobody within reach of them. Mr X leaves through the region nobody is watching.
 * Note that the herd is not a bug in any one detective's reasoning — each is playing
 * its own best move. It is an emergent failure of five agents optimising the same
 * scalar objective with no term for what the others are doing.
 *
 * <p>
 * <b>The fix.</b> Treat the turn as a <i>facility location</i> problem over Mr X's
 * belief and solve it with submodular greedy. Each detective is assigned a target
 * station; the value of an assignment is the probability mass it <i>covers</i>, and
 * coverage saturates — a candidate already watched closely by one detective is worth
 * almost nothing to a second. Greedy on a saturating objective fans the detectives
 * out on its own, with no explicit "stay apart" penalty, because the objective simply
 * stops rewarding a detective for re-covering ground.
 *
 * <p>
 * <b>The coverage kernel.</b> A detective {@code d} standing at {@code from} and
 * assigned target {@code t} covers candidate {@code c} with weight
 *
 * <pre>{@code   w(d, t, c) = reach(detectiveHops(from, t)) * capture(detectiveHops(t, c))}</pre>
 *
 * and the assignment's score is {@code sum over c of p(c) * max over assigned (d,t) of w}.
 * Two decays, because they answer two different questions.
 *
 * <ul>
 * <li>{@code capture(k) = 0.5^k} — <i>once parked at t, how much of the belief does
 * this detective actually threaten?</i> Detectives win by landing <i>on</i> Mr X, so
 * the threat radius is genuinely tiny: a station two hops from where the detective
 * stands is far less covered than the one under its feet, and mass five hops away is
 * to all intents unwatched. A geometric decay is the honest shape; a gentle
 * {@code 1/(1+k)} would call a detective on the far side of the map a 17% guard of
 * everything, which is exactly the flattery that produces herding in the first place.
 * <li>{@code reach(k) = 1/(1+k)} — <i>how long until it gets there?</i> This one is
 * deliberately gentle. A target eight hops away is worth less than one two hops away,
 * but it is not worthless: somebody has to walk to the far cluster, and a harsh decay
 * here would make every detective decide the far cluster is not its problem — herding
 * again, by a different route.
 * </ul>
 *
 * <p>
 * Distances are {@link Distances#detectiveHops(int, int)} throughout, never
 * {@link Distances#hops(int, int)}: detectives hold no SECRET ticket and a ferry edge
 * demands one, so {@code hops} would route them by boat and make them look nearer to
 * the belief than they can ever get.
 *
 * <p>
 * Pure, stateless, deterministic and free of JavaFX. Every distance is an O(1) lookup
 * in the table {@link Distances} precomputed once, so there is no BFS on this path and
 * the whole assignment is cheap enough to sit inside a search.
 */
public final class RouteDiversity {

	private RouteDiversity() {}

	/**
	 * Per-hop attenuation of a detective's threat around the station it is heading for.
	 * See the class notes: detectives capture by <i>landing on</i> Mr X, so the radius
	 * they truly cover is small.
	 */
	private static final double CAPTURE_DECAY = 0.5;

	/**
	 * Beyond this many hops the capture weight is under 1% and contributes nothing but
	 * arithmetic; skipping it keeps the inner loop honest.
	 */
	private static final int CAPTURE_HORIZON = 7;

	/**
	 * How many of the heaviest candidates are eligible to be a <i>target</i>. Coverage
	 * is still scored against the whole candidate set — this bounds only the number of
	 * sites considered, keeping the greedy at O(|D|^2 * K * |C|) rather than
	 * O(|D|^2 * |C|^2). With |D| = 5 and K = 64 that is a few hundred thousand array
	 * reads at worst, i.e. well under a millisecond.
	 */
	private static final int MAX_TARGET_SITES = 64;

	// ------------------------------------------------------------------ assignment

	/**
	 * Assign each detective a target station drawn from Mr X's likely locations, so that
	 * the detectives spread across the belief rather than all chasing the same one.
	 *
	 * <p>
	 * Submodular greedy: repeatedly take the (unassigned detective, target) pair whose
	 * <i>marginal</i> coverage gain — the mass it would cover that nobody assigned before
	 * it already covers — is largest. Because the gain is marginal, the second detective
	 * to consider a target already owned by a neighbour sees almost no gain there and
	 * goes elsewhere. Ties are broken deterministically (nearer target, then lower
	 * station id, then piece name), so the same board always produces the same map.
	 *
	 * @param board     a board on which it is a detective's turn (any board works; the
	 *                  belief is read from the public travel log)
	 * @param distances the precomputed distance tables
	 * @return every detective on the board, mapped to the station it should cover.
	 *         Empty only if the board has no detectives or no candidate stations.
	 */
	public static Map<Piece, Integer> assignTargets(Board board, Distances distances) {
		final List<Player> detectives = new ArrayList<>(BoardStates.detectivesOf(board));
		final Map<Integer, Double> belief = candidateBelief(board);
		final Map<Piece, Integer> assignment = new LinkedHashMap<>();
		if (detectives.isEmpty() || belief.isEmpty()) return assignment;

		// Fixed iteration order in, fixed answer out.
		detectives.sort(Comparator.comparing(d -> d.piece().webColour()));
		final int[] candidates = keysSortedById(belief);
		final double[] mass = new double[candidates.length];
		for (int i = 0; i < candidates.length; i++) mass[i] = belief.get(candidates[i]);
		final int[] sites = heaviestSites(belief);

		// covered[i]: the best weight any already-assigned detective brings to candidate i.
		// A new detective is only paid for the amount by which it *improves* on this, which
		// is what makes the objective saturate and the detectives fan out.
		final double[] covered = new double[candidates.length];
		final boolean[] taken = new boolean[detectives.size()];

		for (int round = 0; round < detectives.size(); round++) {
			int bestDetective = -1;
			int bestSite = -1;
			double bestGain = Double.NEGATIVE_INFINITY;
			int bestTravel = Integer.MAX_VALUE;

			for (int d = 0; d < detectives.size(); d++) {
				if (taken[d]) continue;
				final int from = detectives.get(d).location();
				for (int site : sites) {
					final int travel = distances.detectiveHops(from, site);
					final double gain = marginalGain(distances, site, travel, candidates, mass, covered);
					// Strictly-greater keeps the first-found winner, and the loops are ordered,
					// so this is deterministic. The travel tie-break sends the nearer detective
					// when two of them value a target identically.
					if (gain > bestGain || (gain == bestGain && travel < bestTravel)) {
						bestGain = gain;
						bestTravel = travel;
						bestDetective = d;
						bestSite = site;
					}
				}
			}
			if (bestDetective < 0) break; // no sites at all; cannot happen with a non-empty belief

			taken[bestDetective] = true;
			assignment.put(detectives.get(bestDetective).piece(), bestSite);
			// Saturate: fold the winner's coverage into the running maximum.
			final int from = detectives.get(bestDetective).location();
			final double reach = reach(distances.detectiveHops(from, bestSite));
			for (int i = 0; i < candidates.length; i++) {
				final double w = reach * capture(distances.detectiveHops(bestSite, candidates[i]));
				if (w > covered[i]) covered[i] = w;
			}
		}
		return assignment;
	}

	/**
	 * The probability mass an assignment covers, under the kernel described in the class
	 * notes. Exposed because it is the number that says whether a set of targets is
	 * actually better than the naive one — the tests compare it against the "everybody
	 * chases the argmax" baseline.
	 *
	 * @param board      the board the assignment was made for
	 * @param distances  the precomputed distance tables
	 * @param assignment detective piece to target station
	 * @return covered mass, in [0, 1]
	 */
	public static double coveredMass(Board board, Distances distances, Map<Piece, Integer> assignment) {
		final Map<Integer, Double> belief = candidateBelief(board);
		final Map<Piece, Integer> where = new LinkedHashMap<>();
		for (Player detective : BoardStates.detectivesOf(board)) {
			where.put(detective.piece(), detective.location());
		}
		double total = 0.0;
		for (Map.Entry<Integer, Double> entry : belief.entrySet()) {
			double best = 0.0;
			for (Map.Entry<Piece, Integer> assigned : assignment.entrySet()) {
				final Integer from = where.get(assigned.getKey());
				if (from == null) continue;
				final int target = assigned.getValue();
				final double w = reach(distances.detectiveHops(from, target))
						* capture(distances.detectiveHops(target, entry.getKey()));
				if (w > best) best = w;
			}
			total += entry.getValue() * best;
		}
		return total;
	}

	/**
	 * Mr X's believed locations, restricted to the stations {@link MrXLocator} still
	 * admits as possible. {@link Suspicion#likelihoods(Board)} is the weighted view and
	 * mass is what we are trying to cover, so it is the one used; the intersection just
	 * guarantees every target we hand out is a genuine candidate.
	 *
	 * @param board the current board
	 * @return station to probability; renormalised over the surviving stations
	 */
	public static Map<Integer, Double> candidateBelief(Board board) {
		final Map<Integer, Double> belief = Suspicion.likelihoods(board);
		final ImmutableSet<Integer> possible = MrXLocator.possibleLocations(board);
		final Map<Integer, Double> kept = new LinkedHashMap<>();
		double total = 0.0;
		for (Map.Entry<Integer, Double> entry : belief.entrySet()) {
			if (possible.contains(entry.getKey()) && entry.getValue() > 0.0) {
				kept.put(entry.getKey(), entry.getValue());
				total += entry.getValue();
			}
		}
		// The two disagreeing at all would be a bug in one of them, but a detective that
		// stops moving because of it would be a worse bug. Fall back on the raw belief.
		if (kept.isEmpty() || total <= 0.0) return belief;
		final Map<Integer, Double> normalised = new LinkedHashMap<>();
		for (Map.Entry<Integer, Double> entry : kept.entrySet()) {
			normalised.put(entry.getKey(), entry.getValue() / total);
		}
		return normalised;
	}

	/** The mass a detective at {@code travel} hops from {@code site} would newly cover. */
	private static double marginalGain(Distances distances, int site, int travel,
			int[] candidates, double[] mass, double[] covered) {
		final double reach = reach(travel);
		if (reach <= 0.0) return 0.0;
		double gain = 0.0;
		for (int i = 0; i < candidates.length; i++) {
			final double w = reach * capture(distances.detectiveHops(site, candidates[i]));
			if (w > covered[i]) gain += mass[i] * (w - covered[i]);
		}
		return gain;
	}

	/** How much of the belief around it a detective parked on a station really threatens. */
	private static double capture(int hops) {
		if (hops == Integer.MAX_VALUE || hops > CAPTURE_HORIZON) return 0.0;
		return Math.pow(CAPTURE_DECAY, hops);
	}

	/** How much a target is discounted for being a long walk away. */
	private static double reach(int hops) {
		if (hops == Integer.MAX_VALUE) return 0.0;
		return 1.0 / (1.0 + hops);
	}

	/** The candidate stations eligible to be targets: the heaviest {@value #MAX_TARGET_SITES}. */
	private static int[] heaviestSites(Map<Integer, Double> belief) {
		final List<Map.Entry<Integer, Double>> ranked = new ArrayList<>(belief.entrySet());
		ranked.sort(Comparator.<Map.Entry<Integer, Double>>comparingDouble(Map.Entry::getValue)
				.reversed()
				.thenComparingInt(Map.Entry::getKey));
		final int n = Math.min(MAX_TARGET_SITES, ranked.size());
		final int[] sites = new int[n];
		for (int i = 0; i < n; i++) sites[i] = ranked.get(i).getKey();
		return sites;
	}

	/** Candidate stations in ascending id order, so the coverage arrays line up stably. */
	private static int[] keysSortedById(Map<Integer, Double> belief) {
		final int[] keys = belief.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
		return keys;
	}

	// ------------------------------------------------------------------ movement

	/**
	 * @param board      the current board
	 * @param distances  the precomputed distance tables
	 * @param mover      the detective to move
	 * @param target     the station it has been told to cover
	 * @param legalMoves the moves actually on offer this turn; the answer is always one
	 *                   of these
	 * @return the move, from the given legal moves, that best advances {@code mover}
	 *         toward {@code target}. Closest available if none actually shortens the
	 *         distance (a detective boxed in still has to move somewhere). Empty only if
	 *         {@code legalMoves} holds no move for {@code mover}.
	 */
	public static Optional<Move> bestMoveToward(Board board, Distances distances, Piece mover,
			int target, Collection<Move> legalMoves) {
		Move best = null;
		int bestHops = Integer.MAX_VALUE;
		int bestSpare = Integer.MIN_VALUE;
		int bestDestination = Integer.MAX_VALUE;

		for (Move move : legalMoves) {
			if (!move.commencedBy().equals(mover)) continue;
			final int destination = destinationOf(move);
			final int hops = distances.detectiveHops(destination, target);
			// TIE-BREAK: among moves that land equally close, spend the ticket we hold most
			// of. That is nearly always the taxi, and it is how a detective avoids burning
			// its four underground tickets to save a single hop it did not need to save.
			final int spare = spareTicketsAfter(board, mover, move);

			final boolean better = best == null
					|| hops < bestHops
					|| (hops == bestHops && spare > bestSpare)
					|| (hops == bestHops && spare == bestSpare && destination < bestDestination);
			if (better) {
				best = move;
				bestHops = hops;
				bestSpare = spare;
				bestDestination = destination;
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * How many tickets of the scarcest type this move touches would be left afterwards.
	 * Bigger is better: it prefers the abundant ticket, and a double move (which spends
	 * two) over a single only if the single is scarcer still.
	 */
	private static int spareTicketsAfter(Board board, Piece mover, Move move) {
		final Optional<Board.TicketBoard> held = board.getPlayerTickets(mover);
		if (held.isEmpty()) return 0;
		final Board.TicketBoard purse = held.get();
		int worst = Integer.MAX_VALUE;
		int spent = 0;
		for (Ticket ticket : move.tickets()) {
			spent++;
			worst = Math.min(worst, purse.getCount(ticket));
		}
		if (worst == Integer.MAX_VALUE) return 0;
		return worst - spent;
	}

	/** Where a move leaves the mover standing. */
	private static int destinationOf(Move move) {
		return move.visit(new Move.FunctionalVisitor<>(m -> m.destination, m -> m.destination2));
	}
}
