package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Tests for the retuned {@link Evaluator}: the graded threat term, the saturating
 * distance term, and the belief/entropy term.
 */
public class EvaluatorTuningTest {

	private static ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph;
	private static GameSetup setup;
	private static Distances distances;
	private static ImmutableList<Integer> nodes;

	private static final int RED_START = 91;
	private static final int GREEN_START = 103;
	private static final int BLUE_START = 112;

	@BeforeClass
	public static void loadMap() throws IOException {
		graph = ScotlandYard.standardGraph();
		setup = new GameSetup(graph, ScotlandYard.STANDARD24ROUNDS);
		distances = new Distances(graph);
		nodes = ImmutableList.copyOf(graph.nodes());
	}

	private static Player mrX(int location) {
		return new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), location);
	}

	private static Player detective(Piece.Detective piece, int location) {
		return new Player(piece, ScotlandYard.defaultDetectiveTickets(), location);
	}

	private static GameState boardWith(int mrXLocation, ImmutableList<Player> detectives) {
		return new MyGameStateFactory().build(setup, mrX(mrXLocation), detectives);
	}

	private static GameState threeDetectiveBoard(int mrXLocation) {
		return boardWith(mrXLocation, ImmutableList.of(
				detective(Piece.Detective.RED, RED_START),
				detective(Piece.Detective.GREEN, GREEN_START),
				detective(Piece.Detective.BLUE, BLUE_START)));
	}

	// === the graded sentinel ===================================================

	/**
	 * THE fix. A non-terminal node must never return a sentinel: when it did, every
	 * root move in a crisis tied at the same huge negative number, alpha-beta returned
	 * an arbitrary one, and Mr X played at random in exactly the position where the
	 * search mattered most.
	 */
	@Test
	public void noNonTerminalPositionEverReturnsASentinel() {
		final Evaluator evaluator = new Evaluator(distances);
		final List<Integer> occupied = ImmutableList.of(RED_START, GREEN_START, BLUE_START);
		final Board board = threeDetectiveBoard(106);

		int threatened = 0;
		for (int node : nodes) {
			final int score = evaluator.score(board, node);
			if (occupied.contains(node)) {
				assertThat(score).as("capture at %d is a true terminal", node)
						.isEqualTo(Evaluator.MRX_CAPTURED);
				continue;
			}
			assertThat(score).as("non-terminal %d is not a sentinel", node)
					.isNotEqualTo(Evaluator.MRX_CAPTURED)
					.isNotEqualTo(Evaluator.MRX_ESCAPED);
			assertThat(score).as("non-terminal %d stays inside the band", node)
					.isBetween(1, 1_000_000);
			if (evaluator.reachableByDetectiveNextMove(board, node)) threatened++;
		}
		// The board really does contain positions the old sentinel would have fired on.
		assertThat(threatened).as("some stations are one detective move from capture")
				.isGreaterThan(3);
	}

	@Test
	public void aThreatenedStationWithMoreWaysOutScoresHigherThanOneWithFewer() {
		final Evaluator evaluator = new Evaluator(distances);
		final Board board = threeDetectiveBoard(106);

		// Among the stations RED can step onto next move, the score must still separate
		// them — that is the whole point of grading the sentinel.
		final List<Integer> threatened = new ArrayList<>();
		for (int node : nodes) {
			if (node == RED_START || node == GREEN_START || node == BLUE_START) continue;
			if (evaluator.reachableByDetectiveNextMove(board, node)) threatened.add(node);
		}
		assertThat(threatened.size()).isGreaterThan(3);

		final List<Integer> scores = new ArrayList<>();
		for (int node : threatened) {
			scores.add(evaluator.score(board, node));
		}
		// Not all the same number: the old code returned NEAR_CAPTURE for every one of
		// these, and the search could not tell them apart.
		assertThat(scores.stream().distinct().count())
				.as("threatened positions are graded, not flattened")
				.isGreaterThan(1);

		// And the grading points the right way: more escapes is better.
		int most = threatened.get(0);
		int fewest = threatened.get(0);
		for (int node : threatened) {
			if (evaluator.escapeCount(board, node) > evaluator.escapeCount(board, most)) most = node;
			if (evaluator.escapeCount(board, node) < evaluator.escapeCount(board, fewest)) fewest = node;
		}
		assertThat(evaluator.escapeCount(board, most))
				.isGreaterThan(evaluator.escapeCount(board, fewest));
		assertThat(evaluator.score(board, most))
				.as("station %d (%d escapes) beats %d (%d escapes)", most,
						evaluator.escapeCount(board, most), fewest,
						evaluator.escapeCount(board, fewest))
				.isGreaterThan(evaluator.score(board, fewest));
	}

	@Test
	public void aThreatenedStationStillScoresBelowASafeOne() {
		final Evaluator evaluator = new Evaluator(distances);
		final Board board = threeDetectiveBoard(106);

		int threatened = -1;
		for (int node : nodes) {
			if (evaluator.reachableByDetectiveNextMove(board, node)) {
				threatened = node;
				break;
			}
		}
		assertThat(threatened).isNotEqualTo(-1);

		final int safe = furthestFrom(ImmutableList.of(RED_START, GREEN_START, BLUE_START));
		assertThat(evaluator.score(board, threatened)).isLessThan(evaluator.score(board, safe));
	}

	// === overflow ==============================================================

	@Test
	public void noOverflowAcrossEveryStationAndEveryWeightVector() {
		final List<EvalWeights> vectors = ImmutableList.of(
				EvalWeights.defaults(),
				humped(EvalWeights.defaults()),
				noEntropy(EvalWeights.defaults()));

		for (EvalWeights weights : vectors) {
			final Evaluator evaluator = new Evaluator(distances, weights);
			for (int node : nodes) {
				final Board board = threeDetectiveBoard(106);
				final int score = evaluator.score(board, node);
				assertThat(score).as("hump=%s node=%d", weights.useHumpPrior(), node)
						.isBetween(Evaluator.MRX_CAPTURED, Evaluator.MRX_ESCAPED);
			}
		}
	}

	// === safety ================================================================

	@Test
	public void scoreIsMonotoneIshInSafety() {
		final Evaluator evaluator = new Evaluator(distances);
		final ImmutableList<Player> detectives = ImmutableList.of(
				detective(Piece.Detective.RED, RED_START),
				detective(Piece.Detective.GREEN, GREEN_START),
				detective(Piece.Detective.BLUE, BLUE_START));
		final ImmutableList<Integer> at = ImmutableList.of(RED_START, GREEN_START, BLUE_START);

		// Mean score at each distance-from-nearest-detective band. Within the saturating
		// region the mean must rise; past the cap it is allowed (and expected) to level
		// off, which is exactly the fix for Mr X walking himself into a corner.
		final Map<Integer, List<Integer>> byDistance = new LinkedHashMap<>();
		for (int node : nodes) {
			if (at.contains(node)) continue;
			int nearest = Integer.MAX_VALUE;
			for (int d : at) {
				nearest = Math.min(nearest, distances.hops(d, node));
			}
			if (nearest < 1 || nearest > 6) continue;
			byDistance.computeIfAbsent(nearest, key -> new ArrayList<>())
					.add(evaluator.score(boardWith(node, detectives), node));
		}

		final double atOne = mean(byDistance.get(1));
		final double atThree = mean(byDistance.get(3));
		assertThat(atThree).as("three hops away beats one hop away, on average")
				.isGreaterThan(atOne);
	}

	// === the entropy term ======================================================

	@Test
	public void aDiffuseBeliefScoresHigherThanACollapsedOne() {
		final Evaluator evaluator = new Evaluator(distances);
		final Board board = threeDetectiveBoard(106);

		// Collapsed: the detectives know exactly where he is.
		final Map<Integer, Double> collapsed = Map.of(106, 1.0);

		// Diffuse: the mass is spread evenly over twenty candidates.
		final Map<Integer, Double> diffuse = new LinkedHashMap<>();
		for (int i = 0; i < 20; i++) {
			diffuse.put(nodes.get(i), 1.0 / 20);
		}

		assertThat(Evaluator.entropyOf(collapsed)).isZero();
		assertThat(Evaluator.entropyOf(diffuse)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));

		final int collapsedScore =
				evaluator.score(board, 106, Evaluator.entropyOf(collapsed), false);
		final int diffuseScore =
				evaluator.score(board, 106, Evaluator.entropyOf(diffuse), false);

		assertThat(diffuseScore)
				.as("being hard to locate is worth something")
				.isGreaterThan(collapsedScore);
	}

	@Test
	public void aBigButSharplyPeakedBeliefIsWorthLittle() {
		// The reason the term is entropy and not candidate-set size: a hundred candidates
		// with all the mass on one of them is not ambiguity, it is a rounding error.
		final Map<Integer, Double> peaked = new LinkedHashMap<>();
		peaked.put(nodes.get(0), 0.99);
		for (int i = 1; i < 100; i++) {
			peaked.put(nodes.get(i), 0.01 / 99);
		}
		final Map<Integer, Double> small = new LinkedHashMap<>();
		small.put(nodes.get(0), 0.5);
		small.put(nodes.get(1), 0.5);

		assertThat(Evaluator.entropyOf(peaked))
				.as("a hundred candidates, all the mass on one")
				.isLessThan(Evaluator.entropyOf(small));
	}

	@Test
	public void theEntropyTermCanBeSwitchedOff() {
		final Evaluator evaluator = new Evaluator(distances, noEntropy(EvalWeights.defaults()));
		final Board board = threeDetectiveBoard(106);
		assertThat(evaluator.score(board, 106, 0.0, false))
				.isEqualTo(evaluator.score(board, 106, 1.0, false));
	}

	// === reveal-round planning =================================================

	@Test
	public void arrivingAtARevealPaysMoreForFreedom() {
		final Evaluator evaluator = new Evaluator(distances);
		final Board board = threeDetectiveBoard(106);

		// A high-degree station and a low-degree one, both far from the detectives.
		int high = -1;
		int low = -1;
		for (int node : nodes) {
			if (node == RED_START || node == GREEN_START || node == BLUE_START) continue;
			final int degree = graph.adjacentNodes(node).size();
			if (high == -1 || degree > graph.adjacentNodes(high).size()) high = node;
			if (low == -1 || degree < graph.adjacentNodes(low).size()) low = node;
		}
		assertThat(graph.adjacentNodes(high).size()).isGreaterThan(graph.adjacentNodes(low).size());

		final double ordinaryGap =
				evaluator.score(board, high, 0.5, false) / (double) evaluator.score(board, low, 0.5, false);
		final double revealGap =
				evaluator.score(board, high, 0.5, true) / (double) evaluator.score(board, low, 0.5, true);

		// On the move into a reveal, Mr X is about to be pinned to a point; the only thing
		// that undoes it is re-expansion, so degree is worth strictly more there.
		assertThat(revealGap).as("freedom is priced higher at a reveal").isGreaterThan(ordinaryGap);
	}

	// --- helpers ---------------------------------------------------------------

	private static EvalWeights humped(EvalWeights base) {
		return new EvalWeights(base.nearestWeight(), base.restWeight(), base.distanceCap(), true,
				base.freedomWeight(), base.freedomCap(), base.revealFreedomBoost(),
				base.entropyAlpha(), base.beliefSearch(), base.gateMoves(), base.rootTieBand(),
				base.detectiveCoverage());
	}

	private static EvalWeights noEntropy(EvalWeights base) {
		return new EvalWeights(base.nearestWeight(), base.restWeight(), base.distanceCap(),
				base.useHumpPrior(), base.freedomWeight(), base.freedomCap(),
				base.revealFreedomBoost(), 0.0, base.beliefSearch(), base.gateMoves(),
				base.rootTieBand(), base.detectiveCoverage());
	}

	private static double mean(List<Integer> values) {
		assertThat(values).isNotNull().isNotEmpty();
		double total = 0;
		for (int value : values) {
			total += value;
		}
		return total / values.size();
	}

	/** @return the node whose nearest detective (of the given ones) is furthest away. */
	private static int furthestFrom(List<Integer> detectiveLocations) {
		int best = -1;
		int bestDistance = -1;
		for (int node : nodes) {
			if (detectiveLocations.contains(node)) continue;
			int nearest = Integer.MAX_VALUE;
			for (int d : detectiveLocations) {
				nearest = Math.min(nearest, distances.hops(d, node));
			}
			if (nearest > bestDistance) {
				bestDistance = nearest;
				best = node;
			}
		}
		return best;
	}
}
