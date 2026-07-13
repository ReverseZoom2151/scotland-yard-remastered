package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.LogEntry;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;

/**
 * Tests for {@link Suspicion}, the probability distribution the board overlay
 * paints.
 *
 * <p>
 * The load-bearing property, as for {@link MrXLocator}, is <b>soundness</b>: the
 * station Mr X is really standing on must carry non-zero mass. A heatmap that is
 * confidently wrong is worse than no heatmap. Everything else — the shape of the
 * distribution, which station peaks — is presentation.
 *
 * <p>
 * Headless by construction: {@code Suspicion} takes a {@link Board} and returns a
 * {@link Map}, so no JavaFX toolkit is ever started here.
 */
public class SuspicionTest {

	/** Floating point slack for the "sums to one" assertions. */
	private static final double EPSILON = 1e-9;

	// ---------------------------------------------------------------- fixtures

	private static GameSetup standardSetup() throws IOException {
		return new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
	}

	private static Board.GameState freshGame(GameSetup setup, int mrXStart) {
		Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), mrXStart);
		Player red = new Player(Piece.Detective.RED, ScotlandYard.defaultDetectiveTickets(), 91);
		Player green = new Player(Piece.Detective.GREEN, ScotlandYard.defaultDetectiveTickets(), 94);
		Player blue = new Player(Piece.Detective.BLUE, ScotlandYard.defaultDetectiveTickets(), 29);
		Player white = new Player(Piece.Detective.WHITE, ScotlandYard.defaultDetectiveTickets(), 50);
		Player yellow = new Player(Piece.Detective.YELLOW, ScotlandYard.defaultDetectiveTickets(), 138);
		return new MyGameStateFactory().build(setup, mrX, ImmutableList.of(red, green, blue, white, yellow));
	}

	// ----------------------------------------------------------------- helpers

	private static int destinationOf(Move move) {
		return move.visit(new Move.FunctionalVisitor<Integer>(m -> m.destination, m -> m.destination2));
	}

	private static boolean mrXToMove(Board board) {
		ImmutableSet<Move> moves = board.getAvailableMoves();
		return !moves.isEmpty() && moves.iterator().next().commencedBy().isMrX();
	}

	private static Set<Integer> detectiveLocations(Board board) {
		Set<Integer> occupied = new HashSet<>();
		for (Piece p : board.getPlayers()) {
			if (p instanceof Piece.Detective d) board.getDetectiveLocation(d).ifPresent(occupied::add);
		}
		return occupied;
	}

	private static int safety(Board board, Distances distances, int station) {
		int best = Integer.MAX_VALUE;
		for (int d : detectiveLocations(board)) {
			best = Math.min(best, distances.hops(d, station));
		}
		return best;
	}

	/** Mr X runs away from the nearest detective, mixing in secrets and doubles. */
	private static Move chooseMrXMove(Board board, Distances distances, Random rnd, int round) {
		List<Move> pool = new ArrayList<>(board.getAvailableMoves());
		List<Move> preferred = new ArrayList<>();
		if (round % 3 == 0) {
			for (Move m : pool) {
				if (m instanceof Move.SingleMove s && s.ticket == Ticket.SECRET) preferred.add(m);
			}
		} else if (round % 5 == 0) {
			for (Move m : pool) {
				if (m instanceof Move.DoubleMove) preferred.add(m);
			}
		}
		List<Move> from = preferred.isEmpty() ? pool : preferred;

		Move best = null;
		int bestScore = Integer.MIN_VALUE;
		for (Move m : from) {
			int score = safety(board, distances, destinationOf(m)) * 4 + rnd.nextInt(4);
			if (score > bestScore) {
				bestScore = score;
				best = m;
			}
		}
		return best;
	}

	private static Move chooseDetectiveMove(Board board, Distances distances, Random rnd,
			int trueMrX, boolean chase) {
		List<Move> pool = new ArrayList<>(board.getAvailableMoves());
		if (!chase) return pool.get(rnd.nextInt(pool.size()));
		Move best = null;
		int bestScore = Integer.MAX_VALUE;
		for (Move m : pool) {
			int score = distances.hops(destinationOf(m), trueMrX) * 4 + rnd.nextInt(4);
			if (score < bestScore) {
				bestScore = score;
				best = m;
			}
		}
		return best;
	}

	private static boolean justRevealed(Board board) {
		ImmutableList<LogEntry> log = board.getMrXTravelLog();
		return !log.isEmpty() && log.get(log.size() - 1).location().isPresent();
	}

	private static String describe(Board board, int trueMrX, Map<Integer, Double> likelihoods) {
		StringBuilder sb = new StringBuilder();
		sb.append("true MrX = ").append(trueMrX)
				.append(", detectives = ").append(detectiveLocations(board))
				.append(", log = [");
		for (LogEntry e : board.getMrXTravelLog()) {
			sb.append(e.ticket()).append(e.location().map(l -> "@" + l).orElse("@?")).append(' ');
		}
		sb.append("], support(").append(likelihoods.size()).append(") = ").append(likelihoods.keySet());
		return sb.toString();
	}

	private static double total(Map<Integer, Double> likelihoods) {
		double sum = 0;
		for (double m : likelihoods.values()) {
			sum += m;
		}
		return sum;
	}

	// ------------------------------------------------- soundness + normalisation

	/**
	 * THE test. Real games, real moves, Mr X's true station tracked at every step:
	 * the distribution must always sum to one and must always assign him non-zero
	 * mass. If the propagation drops a branch, this fails.
	 */
	@Test
	public void trueLocationAlwaysHasMassAndTheDistributionSumsToOne() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);
		int mrXMovesChecked = 0;

		for (int seed = 0; seed < 30; seed++) {
			Random rnd = new Random(seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			boolean chase = seed % 2 == 0;

			Board.GameState state = freshGame(setup, mrXStart);
			int trueMrX = mrXStart;
			List<String> trace = new ArrayList<>();
			trace.add("seed=" + seed + " chase=" + chase + " MrX starts at " + mrXStart);

			check(state, trueMrX, trace);

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 16) {
				if (mrXToMove(state)) {
					Move move = chooseMrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					trace.add("r" + round + " MrX: " + move + " -> " + trueMrX);
					state = state.advance(move);
					round++;
					if (state.getWinner().isEmpty()) {
						check(state, trueMrX, trace);
						mrXMovesChecked++;
					}
				} else {
					Move move = chooseDetectiveMove(state, distances, rnd, trueMrX, chase);
					trace.add("r" + round + " det: " + move);
					state = state.advance(move);
					if (state.getWinner().isEmpty()) check(state, trueMrX, trace);
				}
			}
		}
		assertThat(mrXMovesChecked).as("the games must actually have moved Mr X around")
				.isGreaterThan(100);
	}

	/** Every invariant that must hold on every board, asserted after every half-move. */
	private static void check(Board board, int trueMrX, List<String> trace) {
		Map<Integer, Double> likelihoods = Suspicion.likelihoods(board);
		String context = String.join("\n", trace) + "\n" + describe(board, trueMrX, likelihoods);

		assertThat(likelihoods).as("the distribution must never be empty%n%s", context).isNotEmpty();

		// Normalisation.
		assertThat(total(likelihoods)).as("likelihoods must sum to 1%n%s", context)
				.isCloseTo(1.0, within(EPSILON));

		// No negative or absurd mass.
		for (double m : likelihoods.values()) {
			assertThat(m).as("every likelihood is a probability%n%s", context)
					.isGreaterThan(0).isLessThanOrEqualTo(1.0 + EPSILON);
		}

		// SOUNDNESS: he is somewhere the overlay is painting.
		assertThat(likelihoods).as("Mr X's TRUE location %s must carry mass%n%s", trueMrX, context)
				.containsKey(trueMrX);
		assertThat(likelihoods.get(trueMrX)).as("mass on the true location must be non-zero%n%s", context)
				.isGreaterThan(0);

		// Detective-occupied stations carry nothing: he cannot be standing on a detective.
		for (int occupied : detectiveLocations(board)) {
			assertThat(likelihoods).as("station %s holds a detective%n%s", occupied, context)
					.doesNotContainKey(occupied);
		}

		// The support must agree with the yes/no candidate set the AI uses.
		assertThat(likelihoods.keySet())
				.as("support must match MrXLocator's candidate set%n%s", context)
				.isEqualTo(MrXLocator.possibleLocations(board));
	}

	// ---------------------------------------------------------------- reveals

	/** On a reveal the log names him, so all the mass must land on that one station. */
	@Test
	public void revealCollapsesTheDistributionToASingleStation() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);
		int reveals = 0;

		for (int seed = 0; seed < 8; seed++) {
			Random rnd = new Random(100 + seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			Board.GameState state = freshGame(setup, mrXStart);
			int trueMrX = mrXStart;

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 16) {
				if (mrXToMove(state)) {
					Move move = chooseMrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					state = state.advance(move);
					round++;
					if (state.getWinner().isEmpty() && justRevealed(state)) {
						Map<Integer, Double> likelihoods = Suspicion.likelihoods(state);
						assertThat(likelihoods).as("just after a reveal, the log names %s", trueMrX)
								.containsOnlyKeys(trueMrX);
						assertThat(likelihoods.get(trueMrX)).isCloseTo(1.0, within(EPSILON));
						reveals++;
					}
				} else {
					state = state.advance(chooseDetectiveMove(state, distances, rnd, trueMrX, false));
				}
			}
		}
		assertThat(reveals).as("the games should have passed through some reveal rounds")
				.isGreaterThanOrEqualTo(8);
	}

	// --------------------------------------------------------------- the mass

	/**
	 * Mass is not flat. Once he has submerged for a couple of moves the candidates
	 * are reachable by different numbers of log-consistent paths, and the heatmap
	 * must say so — otherwise it is just {@link MrXLocator} in colour.
	 */
	@Test
	public void massConcentratesWherePathsConverge() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);
		int uneven = 0;
		int measured = 0;

		for (int seed = 0; seed < 10; seed++) {
			Random rnd = new Random(400 + seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			Board.GameState state = freshGame(setup, mrXStart);
			int trueMrX = mrXStart;
			int sinceReveal = 0;

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 12) {
				if (mrXToMove(state)) {
					Move move = chooseMrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					state = state.advance(move);
					round++;
					if (!state.getWinner().isEmpty()) break;
					sinceReveal = justRevealed(state) ? 0 : sinceReveal + 1;
					if (sinceReveal >= 2) {
						Map<Integer, Double> likelihoods = Suspicion.likelihoods(state);
						if (likelihoods.size() < 3) continue;
						measured++;
						double peak = likelihoods.values().stream()
								.mapToDouble(Double::doubleValue).max().orElseThrow();
						double floor = likelihoods.values().stream()
								.mapToDouble(Double::doubleValue).min().orElseThrow();
						if (peak > floor * 1.5) uneven++;
					}
				} else {
					state = state.advance(chooseDetectiveMove(state, distances, rnd, trueMrX, false));
				}
			}
		}
		assertThat(measured).as("should have measured some submerged boards").isGreaterThan(5);
		assertThat(uneven).as("the distribution should be genuinely uneven, not a flat set in colour")
				.isGreaterThan(measured / 2);
	}

	// -------------------------------------------------------------- ambiguity

	/** The static 2-hop reachability map: every station has some, and it varies. */
	@Test
	public void ambiguityIsPerStationAndVaries() throws IOException {
		GameSetup setup = standardSetup();
		Map<Integer, Integer> ambiguity = Suspicion.ambiguity(
				new Distances(setup.graph), setup.graph.nodes());

		assertThat(ambiguity.keySet()).isEqualTo(setup.graph.nodes());
		for (Map.Entry<Integer, Integer> entry : ambiguity.entrySet()) {
			assertThat(entry.getValue())
					.as("station %s must reach at least its own neighbours", entry.getKey())
					.isGreaterThanOrEqualTo(setup.graph.adjacentNodes(entry.getKey()).size());
		}
		int max = ambiguity.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
		int min = ambiguity.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
		assertThat(max).as("hiding places must differ from dead ends").isGreaterThan(min);
	}
}
