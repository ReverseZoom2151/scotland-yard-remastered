package uk.ac.bris.cs.scotlandyard.ui.ai;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.LogEntry;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * A probability distribution over the stations Mr X might be standing on.
 *
 * <p>
 * {@link MrXLocator#possibleLocations(Board)} answers the yes/no question — is
 * this station consistent with the travel log? This class answers the softer
 * one: <i>how</i> consistent. The support of the distribution is exactly the
 * candidate set; what it adds is mass. Mass is seeded on the last reveal (1.0
 * on the named station; if he has never surfaced, spread evenly over
 * {@link ScotlandYard#MRX_LOCATIONS}) and then pushed forward one logged entry
 * at a time. At each entry a candidate's mass is split evenly across the
 * stations its logged ticket could have carried it to, and mass that arrives at
 * the same station along different branches of the log is summed. Stations that
 * many log-consistent paths funnel into therefore end up heavier than stations
 * hanging off a single thin branch — which is the honest reading of the log,
 * under the assumption that Mr X picked uniformly among his options.
 *
 * <p>
 * This is computed from the <b>public</b> {@link Board} alone: the travel log,
 * the map, and where the detectives stand. It never asks an {@code Ai} anything,
 * so it works identically when Mr X is a human.
 *
 * <p>
 * As in {@link MrXLocator}, occupied stations are pruned only at the <b>end</b>.
 * A detective standing on a station now says nothing about whether Mr X passed
 * through it three rounds ago; pruning mid-propagation severs branches he may
 * really have taken and can drop his true location from the distribution
 * entirely.
 */
public final class Suspicion {

	/**
	 * @param board the current board, as the detectives see it
	 * @return station -&gt; likelihood that Mr X is standing there, summing to 1.0.
	 *         Never empty.
	 */
	public static Map<Integer, Double> likelihoods(Board board) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		final ImmutableList<LogEntry> log = board.getMrXTravelLog();

		int lastReveal = -1;
		for (int i = log.size() - 1; i >= 0; i--) {
			if (log.get(i).location().isPresent()) {
				lastReveal = i;
				break;
			}
		}

		Map<Integer, Double> mass = new LinkedHashMap<>();
		if (lastReveal >= 0) {
			mass.put(log.get(lastReveal).location().orElseThrow(), 1.0);
		} else {
			Set<Integer> seeds = new HashSet<>();
			for (int start : ScotlandYard.MRX_LOCATIONS) {
				if (graph.nodes().contains(start)) seeds.add(start);
			}
			if (seeds.isEmpty()) seeds.addAll(graph.nodes());
			final double share = 1.0 / seeds.size();
			for (int seed : seeds) {
				mass.put(seed, share);
			}
		}

		for (int i = lastReveal + 1; i < log.size() && !mass.isEmpty(); i++) {
			mass = step(graph, mass, log.get(i).ticket());
		}

		// Only now: he cannot be standing where a detective is standing.
		for (int occupied : detectiveLocations(board)) {
			mass.remove(occupied);
		}
		// Nothing is dropped for being merely small: an entry only exists because some
		// log-consistent path reaches it, and after sixteen four-way splits a genuine
		// candidate's raw mass can be tiny. Normalisation restores it to a real number.
		// Thresholding here would silently shrink the support below MrXLocator's
		// candidate set — the same class of bug as pruning occupancy too early.

		// A contradiction means our model of the log is wrong. Rather than hand the UI
		// an empty map, fall back on the candidate set and admit total ignorance over it.
		if (mass.isEmpty()) {
			final ImmutableSet<Integer> fallback = MrXLocator.possibleLocations(board);
			final double share = 1.0 / fallback.size();
			for (int candidate : fallback) {
				mass.put(candidate, share);
			}
			return mass;
		}
		return normalise(mass);
	}

	/**
	 * One logged move. Each candidate hands its mass out in equal shares to the
	 * stations its ticket could have taken it to; shares landing on the same station
	 * add up. A candidate with no legal successor (an isolated node) simply loses its
	 * mass, which the final normalisation absorbs.
	 */
	private static Map<Integer, Double> step(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			Map<Integer, Double> mass, Ticket ticket) {
		final Map<Integer, Double> next = new LinkedHashMap<>();
		final boolean secret = ticket == Ticket.SECRET;
		for (Map.Entry<Integer, Double> entry : mass.entrySet()) {
			final int from = entry.getKey();
			if (!graph.nodes().contains(from)) continue;
			final Set<Integer> successors = new HashSet<>();
			for (int to : graph.adjacentNodes(from)) {
				if (secret || servedBy(graph, from, to, ticket)) successors.add(to);
			}
			if (successors.isEmpty()) continue;
			final double share = entry.getValue() / successors.size();
			for (int to : successors) {
				next.merge(to, share, Double::sum);
			}
		}
		return next;
	}

	/** @return whether some transport on edge {@code (a, b)} is paid for by {@code ticket}. */
	private static boolean servedBy(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			int a, int b, Ticket ticket) {
		for (Transport transport : graph.edgeValueOrDefault(a, b, ImmutableSet.of())) {
			if (transport.requiredTicket() == ticket) return true;
		}
		return false;
	}

	/** Rescales so the masses sum to exactly 1.0. */
	private static Map<Integer, Double> normalise(Map<Integer, Double> mass) {
		double total = 0;
		for (double m : mass.values()) {
			total += m;
		}
		if (total <= 0) return mass;
		final Map<Integer, Double> out = new LinkedHashMap<>(mass.size() * 2);
		for (Map.Entry<Integer, Double> entry : mass.entrySet()) {
			out.put(entry.getKey(), entry.getValue() / total);
		}
		return out;
	}

	/** @return the stations the detectives currently stand on. */
	private static Set<Integer> detectiveLocations(Board board) {
		final Set<Integer> occupied = new HashSet<>();
		for (Piece piece : board.getPlayers()) {
			if (piece.isDetective() && piece instanceof Piece.Detective detective) {
				board.getDetectiveLocation(detective).ifPresent(occupied::add);
			}
		}
		return occupied;
	}

	/**
	 * The distribution pushed one Mr X turn into the <i>future</i>, spending only the
	 * tickets he actually holds — where he will be standing when the detectives next
	 * get to look at him, which is the thing a detective actually wants to intercept.
	 *
	 * <p>
	 * This is where his ticket counts earn their keep, and the only place they do.
	 * Pruning the <i>past</i> on a ticket budget is vacuous — the log names the exact
	 * ticket paid for every entry, so every log-consistent path spends the same
	 * tickets and none can be told from another on cost (see the long note in
	 * {@link MrXLocator}). The future is a different matter: his SECRET and DOUBLE
	 * tickets are never replenished, their live counts are on the board, and once they
	 * run out those moves are gone for good. With no secrets left he cannot cross a
	 * ferry edge and cannot move except along a transport he holds the ticket for;
	 * with no doubles left he cannot reach two stations away. A forward model that
	 * ignores this spreads mass over moves the rules forbid.
	 *
	 * <p>
	 * The mass model is the one this class already assumes: Mr X picks <b>uniformly
	 * among his legal moves</b>. Legal here mirrors {@code MyGameStateFactory}: one
	 * move per transport-ticket he holds for each incident edge, plus a secret move
	 * over any edge if he holds a secret, plus every two-leg combination if he holds a
	 * double and the log has two rounds left — with the second leg costed against the
	 * purse the first leg leaves behind. Note this weights <i>destinations by how many
	 * ways there are to reach them</i>: a station he can get to by taxi, by bus and by
	 * secret is three of his options, not one, and is correspondingly likelier.
	 *
	 * <p>
	 * Detective-occupied stations are <b>not</b> removed. The detectives move before he
	 * does, so where they stand now is not where they will stand when he chooses; the
	 * support is deliberately a superset, for the same reason the rest of this class
	 * prunes occupancy only at the very end.
	 *
	 * @param board the current board, as the detectives see it
	 * @return station -&gt; likelihood Mr X is standing there after his next move,
	 *         summing to 1.0. Never empty.
	 */
	public static Map<Integer, Double> nextLikelihoods(Board board) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		final Map<Integer, Double> now = likelihoods(board);
		final EnumMap<Ticket, Integer> purse = MrXLocator.mrXPurse(board);
		final boolean doubles = MrXLocator.canStillPlayDouble(board);

		final Map<Integer, Double> next = new LinkedHashMap<>();
		for (Map.Entry<Integer, Double> entry : now.entrySet()) {
			final int from = entry.getKey();
			if (!graph.nodes().contains(from)) continue;

			// Enumerate his legal moves as destinations-with-multiplicity: one slot per
			// (ticket, destination) for a single, one per (ticket, mid, ticket, end) for a
			// double. Counting first, then dividing, keeps every option equally weighted.
			final Map<Integer, Integer> ways = new LinkedHashMap<>();
			int options = 0;
			for (int first : graph.adjacentNodes(from)) {
				for (Ticket paid : MrXLocator.affordable(graph, purse, from, first)) {
					ways.merge(first, 1, Integer::sum);
					options++;
					if (!doubles) continue;
					purse.merge(paid, -1, Integer::sum);
					for (int second : graph.adjacentNodes(first)) {
						final int legs = MrXLocator.affordable(graph, purse, first, second).size();
						if (legs > 0) {
							ways.merge(second, legs, Integer::sum);
							options += legs;
						}
					}
					purse.merge(paid, 1, Integer::sum);
				}
			}
			if (options == 0) continue;

			final double share = entry.getValue() / options;
			for (Map.Entry<Integer, Integer> way : ways.entrySet()) {
				next.merge(way.getKey(), share * way.getValue(), Double::sum);
			}
		}

		// He is boxed in everywhere, or the board would not show us his tickets. Rather
		// than hand back nothing, stand still: the present is the honest answer.
		if (next.isEmpty()) return now;
		return normalise(next);
	}

	/**
	 * How many distinct stations sit within two moves of each station — a static
	 * property of the map, and a decent proxy for how good a place it is to hide:
	 * the more ways out, the harder the station is to corner you in. Ignores tickets,
	 * exactly as {@link Distances#hops(int, int)} does.
	 *
	 * @param distances precomputed all-pairs hop counts for the map
	 * @param nodes     the stations of the map
	 * @return station -&gt; size of its 2-hop reachable set (excluding itself)
	 */
	public static Map<Integer, Integer> ambiguity(Distances distances, Set<Integer> nodes) {
		final Map<Integer, Integer> out = new HashMap<>(nodes.size() * 2);
		for (int from : nodes) {
			int count = 0;
			for (int to : nodes) {
				if (from == to) continue;
				final int hops = distances.hops(from, to);
				if (hops >= 1 && hops <= 2) count++;
			}
			out.put(from, count);
		}
		return out;
	}

	private Suspicion() {
	}
}
