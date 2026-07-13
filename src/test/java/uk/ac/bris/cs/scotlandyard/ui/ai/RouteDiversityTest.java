package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Tests for {@link RouteDiversity}.
 *
 * <p>
 * The load-bearing property is <b>anti-herding</b>. Five detectives all walking at
 * the single most likely station is the failure this class exists to fix, so the
 * central test builds a belief deliberately split across well-separated clusters and
 * demands that the assignment spans them — and that it beats, by a measured margin,
 * the naive baseline in which everybody chases the argmax. Everything else here
 * (targets are real candidates, the chosen move is legal and closes the distance,
 * determinism, degenerate boards) is a guard rail around that.
 *
 * <p>
 * Headless: a {@code GameState} is a {@code Board}, and nothing here touches JavaFX.
 */
public class RouteDiversityTest {

	// ---------------------------------------------------------------- fixtures

	private static GameSetup standardSetup() throws IOException {
		return new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
	}

	/** Where a move leaves the mover standing. */
	private static int destinationOf(Move move) {
		return move.visit(new Move.FunctionalVisitor<>(m -> m.destination, m -> m.destination2));
	}

	private static boolean mrXToMove(Board board) {
		final ImmutableSet<Move> moves = board.getAvailableMoves();
		return !moves.isEmpty() && moves.iterator().next().commencedBy().isMrX();
	}

	/**
	 * The station whose own neighbours are furthest apart <i>as a detective walks</i>.
	 *
	 * <p>
	 * This is the ferry pier. A SECRET move out of it puts mass both on the stations
	 * around the pier and on the far side of the water, and a detective cannot follow
	 * over the water — a ferry demands a SECRET ticket and no detective holds one — so
	 * in the metric that governs detectives the two lumps of belief are the length of
	 * the map apart. Exactly the board on which herding loses the game: whichever lump
	 * the five of them pile onto, the other is unwatched.
	 *
	 * <p>
	 * Deliberately derived from the graph rather than hardcoded, so the test says what
	 * it means instead of asserting on magic station numbers.
	 */
	private static int widestScatterStation(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			Distances distances) {
		int best = -1;
		int bestGap = -1;
		final List<Integer> nodes = new ArrayList<>(graph.nodes());
		nodes.sort(Comparator.naturalOrder()); // node iteration order is not fixed; ours is
		for (int node : nodes) {
			if (!hasTaxiNeighbour(graph, node)) continue; // Mr X must be able to walk onto it
			final List<Integer> around = new ArrayList<>(graph.adjacentNodes(node));
			final int gap = widestSeparation(distances, around);
			if (gap > bestGap) {
				bestGap = gap;
				best = node;
			}
		}
		return best;
	}

	private static boolean hasTaxiNeighbour(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			int node) {
		for (int neighbour : graph.adjacentNodes(node)) {
			if (graph.edgeValueOrDefault(node, neighbour, ImmutableSet.<Transport>of())
					.contains(Transport.TAXI)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Five detective starts a middle distance from {@code pier}: near enough to be a real
	 * threat, far enough that they neither capture Mr X while the scenario is being set
	 * up nor stand on a candidate station (which would prune it out of the belief).
	 */
	private static List<Integer> detectiveStations(Distances distances,
			ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph, int pier, int avoid) {
		final List<Integer> nodes = new ArrayList<>(graph.nodes());
		nodes.removeIf(n -> {
			final int hops = distances.detectiveHops(pier, n);
			return n == avoid || hops < 3 || hops > 6;
		});
		nodes.sort(Comparator.naturalOrder());
		assertThat(nodes).as("enough room to place five detectives").hasSizeGreaterThanOrEqualTo(5);
		return new ArrayList<>(nodes.subList(0, 5));
	}

	/** Mr X walks straight at {@code hub}; deterministic, plain tickets only. */
	private static Move mrXToward(Board board, Distances distances, int hub) {
		Move best = null;
		int bestScore = Integer.MAX_VALUE;
		int bestDestination = Integer.MAX_VALUE;
		for (Move move : board.getAvailableMoves()) {
			if (!(move instanceof Move.SingleMove single) || single.ticket == Ticket.SECRET) continue;
			final int score = distances.hops(single.destination, hub);
			if (score < bestScore || (score == bestScore && single.destination < bestDestination)) {
				bestScore = score;
				bestDestination = single.destination;
				best = move;
			}
		}
		return best;
	}

	/** Mr X's lowest-numbered move on the given ticket. */
	private static Move mrXBy(Board board, Ticket ticket) {
		Move best = null;
		int bestDestination = Integer.MAX_VALUE;
		for (Move move : board.getAvailableMoves()) {
			if (!(move instanceof Move.SingleMove single) || single.ticket != ticket) continue;
			if (single.destination < bestDestination) {
				bestDestination = single.destination;
				best = move;
			}
		}
		return best;
	}

	/**
	 * Runs every detective one step <i>away</i> from the hub. They must move — the rules
	 * say so — but they must not blunder into Mr X and end the game before the position
	 * under test exists.
	 */
	private static Board.GameState detectivesRetreat(Board.GameState state, Distances distances, int hub) {
		Board.GameState current = state;
		while (current.getWinner().isEmpty()
				&& !current.getAvailableMoves().isEmpty()
				&& !mrXToMove(current)) {
			Move best = null;
			int bestScore = Integer.MIN_VALUE;
			int bestDestination = Integer.MAX_VALUE;
			for (Move move : current.getAvailableMoves()) {
				final int destination = destinationOf(move);
				final int score = distances.detectiveHops(destination, hub);
				final int safe = score == Integer.MAX_VALUE ? Integer.MAX_VALUE - 1 : score;
				if (safe > bestScore || (safe == bestScore && destination < bestDestination)) {
					bestScore = safe;
					bestDestination = destination;
					best = move;
				}
			}
			if (best == null) break;
			current = current.advance(best);
		}
		return current;
	}

	/** The three boards the tests need, all reached by one deterministic scripted game. */
	private static final class Scenario {
		/** Detective turn, Mr X just revealed: the belief is a single station. */
		private final Board.GameState afterReveal;
		/** Detective turn, Mr X has since gone secret: the belief is two separated lumps. */
		private final Board.GameState afterScatter;
		/** Detective turn, one move later again: those lumps have bloomed into clusters. */
		private final Board.GameState spread;
		/** The stations the clusters in {@link #spread} bloomed out of. */
		private final Set<Integer> seeds;

		private Scenario(Board.GameState afterReveal, Board.GameState afterScatter,
				Board.GameState spread, Set<Integer> seeds) {
			this.afterReveal = afterReveal;
			this.afterScatter = afterScatter;
			this.spread = spread;
			this.seeds = seeds;
		}
	}

	/**
	 * Scripts a game to a detective turn on which Mr X's belief is split across two
	 * clusters that are far apart <i>for a detective</i>: he is revealed standing on the
	 * ferry pier, goes SECRET (so the belief lands both around the pier and across the
	 * water, which no detective can follow him over), then takes one more ordinary move,
	 * so each lump blooms into a cluster of stations.
	 */
	private static Scenario scenario(Distances distances) throws IOException {
		final GameSetup setup = standardSetup();
		final var graph = setup.graph;
		final int pier = widestScatterStation(graph, distances);

		// Start Mr X a taxi step off the pier so that his third move — the first reveal
		// round — lands him back on it and the log names it.
		int start = -1;
		final List<Integer> neighbours = new ArrayList<>(graph.adjacentNodes(pier));
		neighbours.sort(Comparator.naturalOrder());
		for (int neighbour : neighbours) {
			if (graph.edgeValueOrDefault(pier, neighbour, ImmutableSet.<Transport>of())
					.contains(Transport.TAXI)) {
				start = neighbour;
				break;
			}
		}
		assertThat(start).as("the pier has a taxi neighbour to start from").isNotEqualTo(-1);

		final Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), start);
		final List<Integer> stations = detectiveStations(distances, graph, pier, start);
		final Piece.Detective[] colours = {Piece.Detective.RED, Piece.Detective.GREEN,
				Piece.Detective.BLUE, Piece.Detective.WHITE, Piece.Detective.YELLOW};
		final List<Player> detectives = new ArrayList<>();
		for (int i = 0; i < colours.length; i++) {
			detectives.add(new Player(colours[i], ScotlandYard.defaultDetectiveTickets(), stations.get(i)));
		}
		Board.GameState state = new MyGameStateFactory()
				.build(setup, mrX, ImmutableList.copyOf(detectives));

		// Rounds 1..3: walk him onto the pier. Round 3 is a reveal round, so the log names it.
		for (int round = 0; round < 3; round++) {
			state = state.advance(mrXToward(state, distances, pier));
			if (round < 2) state = detectivesRetreat(state, distances, pier);
		}
		final Board.GameState afterReveal = state;
		assertThat(RouteDiversity.candidateBelief(afterReveal))
				.as("a reveal pins him to one station").hasSize(1);

		// Round 4: SECRET. The belief now covers the pier's neighbours — including the far
		// side of the water, which is where the second cluster comes from.
		state = detectivesRetreat(state, distances, pier);
		final Move secret = mrXBy(state, Ticket.SECRET);
		assertThat(secret).as("Mr X can go secret from the pier").isNotNull();
		state = state.advance(secret);
		final Board.GameState afterScatter = state;
		final Set<Integer> seeds = new HashSet<>(RouteDiversity.candidateBelief(afterScatter).keySet());

		// Round 5: one taxi move, so each lump blooms into a cluster.
		state = detectivesRetreat(state, distances, pier);
		final Move taxi = mrXBy(state, Ticket.TAXI);
		assertThat(taxi).as("Mr X can move on by taxi").isNotNull();
		state = state.advance(taxi);

		return new Scenario(afterReveal, afterScatter, state, seeds);
	}

	// ----------------------------------------------------------------- helpers

	/** The naive detective AI: everybody chases the single heaviest candidate. */
	private static Map<Piece, Integer> naiveAssignment(Board board) {
		final Map<Integer, Double> belief = RouteDiversity.candidateBelief(board);
		int argmax = -1;
		double best = -1.0;
		for (Map.Entry<Integer, Double> entry : belief.entrySet()) {
			if (entry.getValue() > best || (entry.getValue() == best && entry.getKey() < argmax)) {
				best = entry.getValue();
				argmax = entry.getKey();
			}
		}
		final Map<Piece, Integer> assignment = new LinkedHashMap<>();
		for (Player detective : BoardStates.detectivesOf(board)) {
			assignment.put(detective.piece(), argmax);
		}
		return assignment;
	}

	/** Which cluster a station belongs to: the hub it is nearest, by the detective metric. */
	private static int clusterOf(Distances distances, Set<Integer> hubs, int station) {
		int best = -1;
		int bestHops = Integer.MAX_VALUE;
		final List<Integer> ordered = new ArrayList<>(hubs);
		ordered.sort(Comparator.naturalOrder());
		for (int hub : ordered) {
			final int hops = distances.detectiveHops(station, hub);
			if (hops < bestHops) {
				bestHops = hops;
				best = hub;
			}
		}
		return best;
	}

	private static int locationOf(Board board, Piece piece) {
		for (Player detective : BoardStates.detectivesOf(board)) {
			if (detective.piece().equals(piece)) return detective.location();
		}
		throw new IllegalArgumentException("no such detective: " + piece);
	}

	// ------------------------------------------------------- 1. THE anti-herding test

	/**
	 * THE test. Mr X's belief is split across several well-separated clusters. The naive
	 * detective sends all five at the argmax and covers one cluster; {@link RouteDiversity}
	 * must spread them over more than one, and must cover strictly more probability mass
	 * for doing so.
	 */
	@Test
	public void assignTargetsSpreadsAcrossSeparatedClustersUnlikeTheNaiveBaseline() throws IOException {
		final Distances distances = new Distances(standardSetup().graph);
		final Scenario scenario = scenario(distances);
		final Board board = scenario.spread;

		// The belief really is split over regions a long way apart — otherwise the test
		// proves nothing. (Not every pair of seeds is distant; what matters is that the
		// belief has genuinely separated regions in it, so that covering only one of them
		// leaves real mass unwatched.)
		final List<Integer> seeds = new ArrayList<>(scenario.seeds);
		assertThat(seeds).as("the belief bloomed out of several stations").hasSizeGreaterThan(1);
		assertThat(widestSeparation(distances, seeds))
				.as("the belief spans regions far apart in the detective metric; seeds=%s", seeds)
				.isGreaterThanOrEqualTo(4);

		final Map<Piece, Integer> assignment = RouteDiversity.assignTargets(board, distances);
		final Map<Piece, Integer> naive = naiveAssignment(board);

		final Set<Integer> distinct = new HashSet<>(assignment.values());
		final Set<Integer> naiveDistinct = new HashSet<>(naive.values());
		final Set<Integer> clusters = new HashSet<>();
		for (int target : assignment.values()) clusters.add(clusterOf(distances, scenario.seeds, target));
		final Set<Integer> naiveClusters = new HashSet<>();
		for (int target : naive.values()) naiveClusters.add(clusterOf(distances, scenario.seeds, target));

		final double covered = RouteDiversity.coveredMass(board, distances, assignment);
		final double naiveCovered = RouteDiversity.coveredMass(board, distances, naive);
		final int spread = widestSeparation(distances, new ArrayList<>(assignment.values()));
		final int naiveSpread = widestSeparation(distances, new ArrayList<>(naive.values()));

		final String report = String.format(
				"RouteDiversity: %d distinct targets over %d clusters, widest gap %d hops, mass %.4f%n"
						+ "naive argmax:   %d distinct targets over %d clusters, widest gap %d hops, mass %.4f",
				distinct.size(), clusters.size(), spread, covered,
				naiveDistinct.size(), naiveClusters.size(), naiveSpread, naiveCovered);

		assertThat(distinct).as("detectives must not all pile onto one station%n%s", report)
				.hasSizeGreaterThan(1);
		assertThat(clusters).as("the targets must span more than one cluster%n%s", report)
				.hasSizeGreaterThan(1);
		assertThat(clusters.size()).as("strictly more spread than the naive baseline%n%s", report)
				.isGreaterThan(naiveClusters.size());
		assertThat(distinct.size()).as("strictly more distinct targets than the naive baseline%n%s", report)
				.isGreaterThan(naiveDistinct.size());
		assertThat(covered).as("strictly more of Mr X's probability mass covered%n%s", report)
				.isGreaterThan(naiveCovered);
		assertThat(spread)
				.as("the assigned targets are far apart, where the naive ones coincide%n%s", report)
				.isGreaterThanOrEqualTo(4)
				.isGreaterThan(naiveSpread);
	}

	/** The widest detective-metric gap between any two of the given stations. */
	private static int widestSeparation(Distances distances, List<Integer> stations) {
		int widest = 0;
		for (int i = 0; i < stations.size(); i++) {
			for (int j = i + 1; j < stations.size(); j++) {
				final int hops = distances.detectiveHops(stations.get(i), stations.get(j));
				if (hops != Integer.MAX_VALUE) widest = Math.max(widest, hops);
			}
		}
		return widest;
	}

	// ------------------------------------------------------- 2. targets are candidates

	@Test
	public void everyAssignedTargetIsACandidateStation() throws IOException {
		final Distances distances = new Distances(standardSetup().graph);
		final Scenario scenario = scenario(distances);
		for (Board board : List.of(scenario.afterReveal, scenario.afterScatter, scenario.spread)) {
			final ImmutableSet<Integer> candidates = MrXLocator.possibleLocations(board);
			final Map<Piece, Integer> assignment = RouteDiversity.assignTargets(board, distances);
			assertThat(assignment.keySet())
					.as("every detective gets a target")
					.hasSize(BoardStates.detectivesOf(board).size());
			assertThat(candidates).as("targets are drawn from the candidate set")
					.containsAll(assignment.values());
		}
	}

	// ------------------------------------------------------- 3. bestMoveToward

	@Test
	public void bestMoveTowardReturnsALegalMoveThatClosesTheDistance() throws IOException {
		final Distances distances = new Distances(standardSetup().graph);
		final Scenario scenario = scenario(distances);
		final Board board = scenario.spread;
		final ImmutableSet<Move> legal = board.getAvailableMoves();
		assertThat(mrXToMove(board)).as("scenario ends on a detective turn").isFalse();

		final Map<Piece, Integer> assignment = RouteDiversity.assignTargets(board, distances);
		int checked = 0;
		for (Map.Entry<Piece, Integer> entry : assignment.entrySet()) {
			final Piece mover = entry.getKey();
			final int target = entry.getValue();
			final List<Move> mine = new ArrayList<>();
			for (Move move : legal) {
				if (move.commencedBy().equals(mover)) mine.add(move);
			}
			final Optional<Move> chosen = RouteDiversity.bestMoveToward(board, distances, mover, target, legal);
			if (mine.isEmpty()) {
				assertThat(chosen).as("no moves for %s means no answer", mover).isEmpty();
				continue;
			}
			checked++;
			assertThat(chosen).as("a move for %s", mover).isPresent();
			assertThat(chosen.orElseThrow()).as("the move is one of the supplied legal moves")
					.isIn((Object[]) mine.toArray(new Move[0]));

			// It is the closest reachable station to the target: no legal move does better.
			final int reached = distances.detectiveHops(destinationOf(chosen.orElseThrow()), target);
			for (Move move : mine) {
				assertThat(distances.detectiveHops(destinationOf(move), target))
						.as("no legal move gets %s closer to %d", mover, target)
						.isGreaterThanOrEqualTo(reached);
			}
			// And it never walks away from the target when it does not have to.
			final int here = distances.detectiveHops(locationOf(board, mover), target);
			int bestPossible = Integer.MAX_VALUE;
			for (Move move : mine) {
				bestPossible = Math.min(bestPossible, distances.detectiveHops(destinationOf(move), target));
			}
			if (bestPossible <= here) {
				assertThat(reached).as("%s does not retreat from %d", mover, target)
						.isLessThanOrEqualTo(here);
			}
		}
		assertThat(checked).as("at least one detective was actually exercised").isPositive();
	}

	@Test
	public void bestMoveTowardIsEmptyWhenTheMoverHasNoLegalMoves() throws IOException {
		final Distances distances = new Distances(standardSetup().graph);
		final Scenario scenario = scenario(distances);
		final Board board = scenario.spread;
		final ImmutableSet<Move> legal = board.getAvailableMoves();

		assertThat(RouteDiversity.bestMoveToward(board, distances, Piece.Detective.RED, 1, List.of()))
				.as("no moves supplied at all").isEmpty();
		// Mr X owns none of the moves on a detective turn.
		assertThat(RouteDiversity.bestMoveToward(board, distances, Piece.MrX.MRX, 1, legal))
				.as("no moves for this mover in the supplied set").isEmpty();
	}

	// ------------------------------------------------------- 4. determinism

	@Test
	public void assignmentIsDeterministic() throws IOException {
		final Distances distances = new Distances(standardSetup().graph);
		final Board first = scenario(distances).spread;
		final Board second = scenario(distances).spread;

		final Map<Piece, Integer> a = RouteDiversity.assignTargets(first, distances);
		final Map<Piece, Integer> b = RouteDiversity.assignTargets(second, distances);
		assertThat(b).as("the same board twice gives the same assignment").isEqualTo(a);

		// And twice on the very same object, for good measure.
		assertThat(RouteDiversity.assignTargets(first, distances)).isEqualTo(a);
	}

	// ------------------------------------------------------- 5. degenerate boards

	/**
	 * One candidate is one candidate: converging on it is not herding, it is correct.
	 * The only requirement is that nothing falls over.
	 */
	@Test
	public void aSingleCandidateIsHandled() throws IOException {
		final Distances distances = new Distances(standardSetup().graph);
		final Scenario scenario = scenario(distances);
		final Board board = scenario.afterReveal;

		final Map<Integer, Double> belief = RouteDiversity.candidateBelief(board);
		assertThat(belief).as("a reveal leaves exactly one candidate").hasSize(1);
		final int only = belief.keySet().iterator().next();

		final Map<Piece, Integer> assignment = RouteDiversity.assignTargets(board, distances);
		assertThat(assignment).as("everyone gets the one candidate there is")
				.hasSize(BoardStates.detectivesOf(board).size())
				.containsValues(only);
		assertThat(new HashSet<>(assignment.values())).containsExactly(only);
	}

	/** No detectives, no assignment; and no exception either. */
	@Test
	public void aBoardWithNoUsefulBeliefDoesNotThrow() throws IOException {
		final GameSetup setup = standardSetup();
		final Distances distances = new Distances(setup.graph);
		final Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), 35);
		final Player red = new Player(Piece.Detective.RED, ScotlandYard.defaultDetectiveTickets(), 91);
		final Board board = new MyGameStateFactory().build(setup, mrX, ImmutableList.of(red));

		// Round one, nothing in the log: the belief is Mr X's possible start stations.
		final Map<Piece, Integer> assignment = RouteDiversity.assignTargets(board, distances);
		assertThat(assignment).as("the one detective still gets a target").hasSize(1);
		assertThat(MrXLocator.possibleLocations(board)).containsAll(assignment.values());
	}
}
