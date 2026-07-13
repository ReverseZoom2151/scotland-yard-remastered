package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Alpha-beta search over the game tree, deepened until the clock runs out.
 *
 * <p>
 * The AI is handed a deadline and is killed one second after it passes, so depth
 * cannot be fixed in advance: the search completes a full ply, keeps the best move
 * it found, and only then starts the next ply — abandoning it the moment the budget
 * is gone. Whatever the last completed ply returned is what gets played, so the
 * search is always safe to interrupt.
 *
 * <h2>The simulated detectives chase the <i>inferred</i> Mr X, not the real one</h2>
 *
 * This is the single most important thing in the class. The search used to advance
 * the simulated detectives greedily towards Mr X's <b>true</b> location, which meant
 * that inside his own tree Mr X was fleeing an omniscient pack. Two consequences,
 * both fatal:
 *
 * <ul>
 * <li>nearly every line lost, so the value landscape was flat and alpha-beta was
 * choosing between shades of doom;
 * <li><b>ambiguity earned him nothing</b>. A secret ticket is worthless in a tree
 * where the detectives already know where you are, and so is a double, and so is
 * standing on a station with six ways out of it. Mr X could not see the point of the
 * things that actually keep him alive, and no amount of tuning the penalty constants
 * could have shown it to him — the information simply was not in the tree.
 * </ul>
 *
 * So the search carries the detectives' <b>belief</b> down each line instead. It is
 * seeded at the root from {@link Suspicion#likelihoods(Board)} — computed from the
 * public board, so this is Mr X reasoning about what his opponents can infer, not Mr
 * X peeking — and pushed forward one hop at a time exactly as the real inference
 * does: a taxi ticket spreads the mass along taxi edges, a secret ticket spreads it
 * along every edge, and a reveal round collapses it onto the station the log names.
 * The simulated detectives then walk towards the <i>believed</i> location.
 *
 * <p>
 * <b>Capture is still adjudicated against the truth.</b> The rebuilt
 * {@link GameState} holds Mr X's real location, so a detective that steps onto it is
 * a capture whatever anyone believed; and the evaluator's threat term is measured
 * against his real position too. The belief governs where the detectives <i>go</i>,
 * not what happens when they get there. That asymmetry is the whole game.
 *
 * <p>
 * (The tempting shortcut — read the belief off the simulated state's travel log,
 * which is public by construction — does not work here: {@link BoardStates#rebuild}
 * starts the rebuilt state with an <i>empty</i> log, so its log is not the game's log
 * and the round indices do not line up. The belief is therefore propagated
 * explicitly, and the true round index is carried alongside it.)
 *
 * <h2>Detectives are not branched</h2>
 *
 * Every detective moving in every combination explodes the tree far beyond what fits
 * in the budget, so each is advanced one at a time, taking the single move that
 * minimises its own ticket-aware distance to where Mr X is <i>believed</i> to be.
 */
public final class Search {

	/** Deepest Mr X ply we will ever attempt; a guard against spinning on a trivial position. */
	private static final int MAX_DEPTH = 8;

	/** How far ahead of the caller's deadline we stop, so we always return before the kill. */
	private static final long SAFETY_MARGIN_NANOS = 100_000_000L;

	/**
	 * At most this many of Mr X's possible locations are considered when playing a
	 * detective. Late in a game the candidate set blooms into the hundreds; scoring
	 * every one of them for every available move would blow the budget for no real
	 * gain, since the candidates are heavily clustered and a bounded sample of them
	 * points in the same direction as the whole set.
	 */
	private static final int MAX_CANDIDATES = 30;

	/** Weight on a detective's distance to the candidate it has been assigned to cover. */
	private static final double COVERAGE_WEIGHT = 2.5;

	/** Unreachable, for costs that must stay finite. */
	private static final int FAR = 99;

	private final Evaluator evaluator;
	private final Distances distances;
	private final EvalWeights weights;
	private final Random rng;

	public Search(Evaluator evaluator, Distances distances) {
		this(evaluator, distances, EvalWeights.defaults(), new Random());
	}

	public Search(Evaluator evaluator, Distances distances, EvalWeights weights, Random rng) {
		this.evaluator = evaluator;
		this.distances = distances;
		this.weights = weights;
		this.rng = rng;
	}

	/** Thrown to unwind the recursion the instant the budget is gone. Carries no stack trace. */
	private static final class TimeoutException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		TimeoutException() {
			super(null, null, false, false);
		}
	}

	/**
	 * Searches for Mr X's best move, deepening until {@code deadlineNanos}.
	 *
	 * @param board         the current board, on which it is Mr X's turn
	 * @param deadlineNanos when to stop, as a {@link System#nanoTime()} value
	 * @return the best move found by the deepest ply that finished in time
	 */
	public Move bestMoveForMrX(Board board, long deadlineNanos) {
		final long stopAt = deadlineNanos - SAFETY_MARGIN_NANOS;
		final ImmutableSet<Move> legal = board.getAvailableMoves();
		final int mrXLocation = BoardStates.mrXLocationOf(board);
		final int rootRound = board.getMrXTravelLog().size();

		// The detectives' belief about Mr X, as of right now, from the public board.
		final Map<Integer, Double> rootBelief = this.weights.beliefSearch()
				? Suspicion.likelihoods(board)
				: Map.of(mrXLocation, 1.0);

		// Branch over the *real* board's moves, so whatever we return is guaranteed to be
		// an element of board.getAvailableMoves(); the gates only ever remove moves from
		// that set, and never all of them.
		final List<Move> branch = this.weights.gateMoves()
				? MoveGates.filter(board, mrXLocation, rootRound, legal, this.distances)
				: ImmutableList.copyOf(legal);

		// The incumbent, before a single node has been searched, must already be a move
		// the gates approve of. It used to be legal.iterator().next() — an arbitrary
		// element of an unordered set — and when the budget was tight enough that not even
		// the first ply finished, THAT was the move Mr X played: an unsearched, ungated
		// one, which on a fresh board is as likely as not to be a secret or a double. The
		// arena caught it, as a rash of round-one secrets that the gates had supposedly
		// forbidden. A fallback has to be a defensible move, not just a legal one.
		Move best = branch.get(0);

		// Iterative deepening: a ply is only allowed to replace the incumbent once it has
		// finished in full. A ply abandoned halfway has seen an arbitrary subset of the
		// moves and its "best so far" is not comparable with anything.
		for (int depth = 1; depth <= MAX_DEPTH; depth++) {
			try {
				final Move candidate =
						rootSearch(board, mrXLocation, rootRound, rootBelief, branch, depth, stopAt);
				if (candidate != null) best = candidate;
			} catch (TimeoutException expired) {
				break;
			}
			if (System.nanoTime() > stopAt) break;
		}
		return best;
	}

	/**
	 * One complete ply of Mr X's search, rooted at the real board.
	 *
	 * <p>
	 * CHOICE: the root children are searched with a <b>full window</b> rather than with
	 * a rising alpha. Alpha at the root turns every non-improving child's score into a
	 * bound rather than a value, and a bound cannot be compared with the incumbent to
	 * within two percent — which is exactly what the tie-breaking below needs to do.
	 * The pruning given up is only the root's; every node beneath it still prunes.
	 * TRADEOFF: perhaps a fifth of a ply of depth, bought back many times over by not
	 * playing a deterministic, and therefore predictable, Mr X.
	 */
	private Move rootSearch(Board board, int mrXLocation, int rootRound,
			Map<Integer, Double> rootBelief, List<Move> branch, int depth, long stopAt) {
		final GameState root = BoardStates.rebuild(board, mrXLocation);
		final List<Move> best = new ArrayList<>();
		int bestValue = Integer.MIN_VALUE;

		final List<Move> scored = new ArrayList<>();
		final List<Integer> values = new ArrayList<>();

		for (Move move : branch) {
			checkClock(stopAt);
			final GameState next;
			try {
				next = root.advance(move);
			} catch (IllegalArgumentException notPlayableHere) {
				continue;
			}
			final int round = rootRound + hopsOf(move);
			final Map<Integer, Double> belief = advanceBelief(board, rootBelief, move, rootRound);
			final int value = detectivePly(next, destinationOf(move), belief, round, depth - 1,
					Integer.MIN_VALUE, Integer.MAX_VALUE, stopAt);
			scored.add(move);
			values.add(value);
			if (value > bestValue) bestValue = value;
		}
		if (scored.isEmpty()) return null;

		// RANDOMISE among near-ties. A deterministic Mr X is a Mr X whose next move an
		// inference engine can simply compute; and when several moves are within the
		// noise of each other the search has no opinion worth defending anyway.
		final double band = Math.abs((double) bestValue) * this.weights.rootTieBand();
		for (int i = 0; i < scored.size(); i++) {
			if (values.get(i) >= bestValue - band) best.add(scored.get(i));
		}
		if (best.isEmpty()) return scored.get(0);
		return best.get(this.rng.nextInt(best.size()));
	}

	/** A maximising node: Mr X to move. */
	private int maxNode(GameState state, int mrXLocation, Map<Integer, Double> belief, int round,
			int depth, int alpha, int beta, long stopAt) {
		checkClock(stopAt);
		final Integer terminal = terminalScore(state);
		if (terminal != null) return terminal;
		if (depth <= 0) return leafScore(state, mrXLocation, belief, round);

		final ImmutableSet<Move> legal = state.getAvailableMoves();
		final List<Move> branch = this.weights.gateMoves()
				? MoveGates.filter(state, mrXLocation, round, legal, this.distances)
				: ImmutableList.copyOf(legal);

		int value = Integer.MIN_VALUE;
		for (Move move : branch) {
			final Map<Integer, Double> next = advanceBelief(state, belief, move, round);
			final int child = detectivePly(state.advance(move), destinationOf(move), next,
					round + hopsOf(move), depth - 1, alpha, beta, stopAt);
			if (child > value) value = child;
			if (value > alpha) alpha = value;
			if (alpha >= beta) break; // fail-high: the parent already has something better
		}
		return value == Integer.MIN_VALUE ? leafScore(state, mrXLocation, belief, round) : value;
	}

	/**
	 * The detectives' reply, modelled greedily rather than branched, and aimed at the
	 * station they <i>believe</i> Mr X is on.
	 */
	private int detectivePly(GameState state, int mrXLocation, Map<Integer, Double> belief,
			int round, int depth, int alpha, int beta, long stopAt) {
		checkClock(stopAt);
		GameState current = state;
		final int believed = believedLocation(belief, mrXLocation);
		while (true) {
			final Integer terminal = terminalScore(current);
			if (terminal != null) return terminal;

			final ImmutableSet<Move> moves = current.getAvailableMoves();
			if (moves.isEmpty()) return leafScore(current, mrXLocation, belief, round);
			if (moves.iterator().next().commencedBy().isMrX()) {
				// Every detective has now moved; back to Mr X.
				return maxNode(current, mrXLocation, belief, round, depth, alpha, beta, stopAt);
			}

			checkClock(stopAt);
			current = current.advance(greediestDetectiveMove(moves, believed));
		}
	}

	/** Scores a leaf, handing the evaluator the belief the line arrived with. */
	private int leafScore(Board state, int mrXLocation, Map<Integer, Double> belief, int round) {
		final double entropy = this.weights.entropyAlpha() == 0
				? 0.0
				: Evaluator.entropyOf(belief);
		return this.evaluator.score(state, mrXLocation, entropy, isReveal(state, round - 1));
	}

	/**
	 * @return the detective move that gets its mover closest to {@code target}
	 *
	 *         <p>
	 *         PERF: on the precomputed hop table, not the ticket-aware distance. The
	 *         ticket-aware one is a fresh BFS over 199 nodes <i>per candidate move</i>,
	 *         and this runs for every detective at every node of the tree: measured, it
	 *         was about nine tenths of the entire search, and it was starving the search
	 *         so badly that at a 300 ms budget not even the first ply completed — so Mr
	 *         X fell back on an unsearched move and the arena watched him spend secrets
	 *         on round one. Ticket-awareness still decides what the position is
	 *         <i>worth</i> (see {@link Evaluator#detectiveProximity}); here it only has
	 *         to rank one detective's dozen moves against each other, which a hop count
	 *         does just as well and a thousand times faster.
	 */
	private Move greediestDetectiveMove(ImmutableSet<Move> moves, int target) {
		Move best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (Move move : moves) {
			final int destination = destinationOf(move);
			if (destination == target) return move; // where they think he is; nothing beats it
			final int distance = this.distances.hops(destination, target);
			if (best == null || distance < bestDistance) {
				bestDistance = distance;
				best = move;
			}
		}
		return best;
	}

	// --- belief ------------------------------------------------------------------

	/**
	 * Pushes the detectives' belief forward across one of Mr X's moves.
	 *
	 * <p>
	 * Exactly the inference the detectives themselves run, and nothing more: the mass
	 * on each candidate is split evenly over the stations the logged ticket could have
	 * carried it to, and mass arriving at a station along several branches sums. A
	 * SECRET ticket spreads along <i>every</i> edge, which is what makes it worth
	 * spending. A reveal round collapses the whole distribution onto the one station
	 * the log names — Mr X's true destination — which is what makes arriving at a
	 * reveal on a high-degree station worth planning for.
	 *
	 * @param board  any board carrying the right setup
	 * @param belief the belief before the move
	 * @param move   Mr X's move (one hop, or two for a double)
	 * @param round  the zero-based log index the move's first hop writes
	 * @return the belief after it
	 */
	private Map<Integer, Double> advanceBelief(Board board, Map<Integer, Double> belief, Move move,
			int round) {
		if (!this.weights.beliefSearch()) {
			return Map.of(destinationOf(move), 1.0);
		}
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		final List<Ticket> tickets = new ArrayList<>(2);
		move.tickets().forEach(tickets::add);

		Map<Integer, Double> mass = belief;
		for (int i = 0; i < tickets.size(); i++) {
			final int hop = round + i;
			mass = step(graph, mass, tickets.get(i));
			if (isReveal(board, hop)) {
				// The log publishes the station: every other candidate dies.
				mass = Map.of(hopDestination(move, i), 1.0);
			}
			if (mass.isEmpty()) return Map.of(hopDestination(move, i), 1.0);
		}
		return mass;
	}

	/** One logged move's worth of diffusion. See {@link Suspicion}. */
	private static Map<Integer, Double> step(
			ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			Map<Integer, Double> mass, Ticket ticket) {
		final Map<Integer, Double> next = new LinkedHashMap<>(mass.size() * 4);
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

	/** @return the station the belief is heaviest on; ties break low, for determinism. */
	private static int believedLocation(Map<Integer, Double> belief, int fallback) {
		int best = fallback;
		double bestMass = -1;
		for (Map.Entry<Integer, Double> entry : belief.entrySet()) {
			final double mass = entry.getValue();
			if (mass > bestMass || (mass == bestMass && entry.getKey() < best)) {
				bestMass = mass;
				best = entry.getKey();
			}
		}
		return best;
	}

	/** @return whether the log entry at {@code round} names Mr X's station. */
	private static boolean isReveal(Board board, int round) {
		final ImmutableList<Boolean> rounds = board.getSetup().rounds;
		if (round < 0 || round >= rounds.size()) return false;
		return Boolean.TRUE.equals(rounds.get(round));
	}

	// --- detectives ---------------------------------------------------------------

	/**
	 * Searches for a detective's best move.
	 *
	 * <p>
	 * Mr X's location is unknown, so the move is scored against every location he could
	 * be in — see {@link MrXLocator} — rather than a single assumed position.
	 *
	 * <p>
	 * COVERAGE (see {@link EvalWeights#detectiveCoverage()}): scoring every detective
	 * against the same candidate set makes them all walk towards the same place, and
	 * five detectives standing on the same station cover one station. So each detective
	 * is first <i>assigned</i> a distinct high-mass candidate from
	 * {@link Suspicion#likelihoods(Board)} — greedily, nearest detective to heaviest
	 * candidate — and pays a premium for closing on the one it owns. They spread, and
	 * the candidate set collapses from several sides at once.
	 *
	 * @param board         the current board, on which it is a detective's turn
	 * @param deadlineNanos when to stop, as a {@link System#nanoTime()} value
	 * @return the best move found
	 */
	public Move bestMoveForDetective(Board board, long deadlineNanos) {
		// CHOICE: a greedy, ticket-aware one-ply evaluation, averaged over the candidate
		// set. TRADEOFF: a full alpha-beta *per candidate* is what a detective would
		// ideally run, but the candidate set routinely holds dozens of stations and each
		// one needs its own rebuilt game state, so the budget buys a depth of about one
		// anyway — and a search that overruns is worse than no search at all, since the AI
		// is hard-killed. Worse, the candidates disagree with each other far more than the
		// plies disagree with each other: the dominant error is *where Mr X is*, not how
		// deeply we read the position.
		final long stopAt = deadlineNanos - SAFETY_MARGIN_NANOS;
		final ImmutableSet<Move> legal = board.getAvailableMoves();
		Move best = legal.iterator().next();

		final List<Integer> candidates = sample(MrXLocator.possibleLocations(board));
		if (candidates.isEmpty()) return best;

		final Map<Piece, Integer> assignment = this.weights.detectiveCoverage()
				? assignCoverage(board)
				: Map.of();

		double bestCost = Double.POSITIVE_INFINITY;
		for (Move move : legal) {
			if (System.nanoTime() > stopAt) break; // keep the incumbent; never overrun
			final double cost = cost(board, move, candidates, assignment);
			if (cost < bestCost) {
				bestCost = cost;
				best = move;
			}
		}
		return best;
	}

	/**
	 * Hands each detective a distinct candidate to cover: the heaviest candidate goes
	 * to whichever detective is closest to it, then the next heaviest to the closest of
	 * those left, and so on. Greedy, not optimal — the optimal assignment is a bipartite
	 * matching, and the extra fidelity is not worth a Hungarian algorithm on a set of
	 * five.
	 *
	 * @return detective piece to the station it should be closing on; possibly partial
	 */
	private Map<Piece, Integer> assignCoverage(Board board) {
		final Map<Integer, Double> belief = Suspicion.likelihoods(board);
		final List<Map.Entry<Integer, Double>> ranked = new ArrayList<>(belief.entrySet());
		ranked.sort(Comparator.<Map.Entry<Integer, Double>>comparingDouble(Map.Entry::getValue)
				.reversed()
				.thenComparingInt(Map.Entry::getKey));

		final List<Player> detectives = new ArrayList<>(BoardStates.detectivesOf(board));
		final Map<Piece, Integer> assignment = new HashMap<>();
		for (Map.Entry<Integer, Double> entry : ranked) {
			if (detectives.isEmpty()) break;
			final int candidate = entry.getKey();
			Player nearest = null;
			int nearestDistance = Integer.MAX_VALUE;
			for (Player detective : detectives) {
				final int distance = this.distances.ticketAwareDistance(
						board, detective.piece(), detective.location(), candidate);
				if (nearest == null || distance < nearestDistance) {
					nearestDistance = distance;
					nearest = detective;
				}
			}
			assignment.put(nearest.piece(), candidate);
			detectives.remove(nearest);
		}
		return assignment;
	}

	/**
	 * The cost of a detective move, averaged over the candidate locations. Low is good
	 * for the detectives, mirroring {@link Evaluator}'s sign convention.
	 *
	 * <p>
	 * Averaging rather than taking the worst case: the candidate set is a set of
	 * possibilities, and a worst-case rule lets one unreachable outlier veto a move that
	 * closes the net on everything else.
	 */
	private double cost(Board board, Move move, List<Integer> candidates,
			Map<Piece, Integer> assignment) {
		final Piece mover = move.commencedBy();
		final int destination = destinationOf(move);
		double total = 0;
		for (int candidate : candidates) {
			if (destination == candidate) {
				total += Evaluator.MRX_CAPTURED / (double) candidates.size();
				continue;
			}
			final int mine = this.distances.ticketAwareDistance(board, mover, destination, candidate);
			// Unreachable candidates would otherwise saturate the average; cap them at a
			// distance no real path on a 199-node map exceeds.
			final int capped = mine == Integer.MAX_VALUE ? FAR : mine;
			// The mover's own distance is what counts most; the rest of the team is worth
			// something too, so that detectives do not all pile onto the same station.
			total += 2.0 * capped + nearestOtherDetective(board, mover, candidate);
		}
		double cost = total / candidates.size();

		final Integer mine = assignment.get(mover);
		if (mine != null) {
			final int toMine =
					this.distances.ticketAwareDistance(board, mover, destination, mine);
			cost += COVERAGE_WEIGHT * (toMine == Integer.MAX_VALUE ? FAR : toMine);
		}
		return cost;
	}

	/** @return the ticket-aware distance from the nearest detective other than {@code mover}. */
	private int nearestOtherDetective(Board board, Piece mover, int candidate) {
		int nearest = FAR;
		for (Player detective : BoardStates.detectivesOf(board)) {
			if (detective.piece().equals(mover)) continue;
			final int distance = this.distances
					.ticketAwareDistance(board, detective.piece(), detective.location(), candidate);
			if (distance != Integer.MAX_VALUE && distance < nearest) nearest = distance;
		}
		return nearest;
	}

	/** Bounds the candidate set at {@link #MAX_CANDIDATES}, keeping iteration order. */
	private static List<Integer> sample(ImmutableSet<Integer> candidates) {
		final List<Integer> sampled = new ArrayList<>(Math.min(candidates.size(), MAX_CANDIDATES));
		// Stride through the set rather than taking a prefix, so the sample spreads over
		// the whole of it instead of clumping in whichever corner comes first.
		final int stride = Math.max(1, candidates.size() / MAX_CANDIDATES);
		int index = 0;
		for (int candidate : candidates) {
			if (index % stride == 0 && sampled.size() < MAX_CANDIDATES) sampled.add(candidate);
			index++;
		}
		return sampled;
	}

	// --- odds and ends -------------------------------------------------------------

	/** @return the score of a finished game, or {@code null} if it is still running. */
	private static Integer terminalScore(Board board) {
		final ImmutableSet<Piece> winner = board.getWinner();
		if (winner.isEmpty()) return null;
		for (Piece piece : winner) {
			if (piece.isMrX()) return Evaluator.MRX_ESCAPED;
		}
		return Evaluator.MRX_CAPTURED;
	}

	/** @return where a move ends up; the second hop, for a double move. */
	private static int destinationOf(Move move) {
		return move.visit(new Move.FunctionalVisitor<Integer>(
				single -> single.destination,
				dubble -> dubble.destination2));
	}

	/** @return the station a move's {@code index}-th hop lands on (0-based). */
	private static int hopDestination(Move move, int index) {
		return move.visit(new Move.FunctionalVisitor<Integer>(
				single -> single.destination,
				dubble -> index == 0 ? dubble.destination1 : dubble.destination2));
	}

	/** @return how many log entries a move writes: one, or two for a double. */
	private static int hopsOf(Move move) {
		return move.visit(new Move.FunctionalVisitor<Integer>(single -> 1, dubble -> 2));
	}

	private static void checkClock(long stopAt) {
		if (System.nanoTime() > stopAt) throw new TimeoutException();
	}
}
