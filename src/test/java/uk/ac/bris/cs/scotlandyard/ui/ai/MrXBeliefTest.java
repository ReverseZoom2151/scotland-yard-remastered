package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;

/**
 * Tests for {@link MrXLocator.Belief}, the incremental version of the inference.
 *
 * <p>
 * It exists to be <i>tighter</i> than {@link MrXLocator#possibleLocations(Board)},
 * by pruning detective-occupied stations at every round rather than only at the
 * end. That is exactly the change that is unsound when done from a single Board —
 * see the note in {@code MrXLocator} — so the same soundness property is asserted
 * here, harder: Mr X's true station must be in the set after <i>every</i>
 * half-move of a whole game, and the set must stay a subset of the stateless one.
 */
public class MrXBeliefTest {

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
		for (int d : detectiveLocations(board)) best = Math.min(best, distances.hops(d, station));
		return best;
	}

	/** Mr X runs from the nearest detective, spending secrets and doubles now and then. */
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

	/** Detectives chase the true station — the traffic that stresses the inference hardest. */
	private static Move chooseDetectiveMove(Board board, Distances distances, Random rnd,
			int trueMrX, boolean chase) {
		List<Move> pool = new ArrayList<>(board.getAvailableMoves());
		if (!chase) return pool.get(rnd.nextInt(pool.size()));
		Move best = null;
		int bestScore = Integer.MAX_VALUE;
		for (Move m : pool) {
			int score = distances.detectiveHops(destinationOf(m), trueMrX) * 4 + rnd.nextInt(4);
			if (score < bestScore) {
				bestScore = score;
				best = m;
			}
		}
		return best;
	}

	/**
	 * THE test, again. The incremental belief prunes far more aggressively than the
	 * stateless inference, so it has far more chances to lose Mr X. It must not.
	 *
	 * <p>
	 * Also reports how much tighter it is, since that is the only reason it exists.
	 */
	@Test
	public void beliefIsSoundAndTighterThanTheStatelessInference() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);

		long statelessTotal = 0;
		long beliefTotal = 0;
		int samples = 0;

		for (int seed = 0; seed < 40; seed++) {
			Random rnd = new Random(seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			boolean chase = seed % 2 == 0;

			Board.GameState state = freshGame(setup, mrXStart);
			MrXLocator.Belief belief = new MrXLocator.Belief(setup.graph);
			int trueMrX = mrXStart;
			List<String> trace = new ArrayList<>();
			trace.add("seed=" + seed + " chase=" + chase + " MrX starts at " + mrXStart);

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 16) {
				// Observe every board the detectives would ever be handed.
				ImmutableSet<Integer> candidates = belief.observe(state);
				ImmutableSet<Integer> stateless = MrXLocator.possibleLocations(state);
				String context = String.join("\n", trace) + "\ntrue MrX = " + trueMrX
						+ ", detectives = " + detectiveLocations(state)
						+ "\nbelief(" + candidates.size() + ") = " + candidates;

				assertThat(candidates).as("belief must never be empty%n%s", context).isNotEmpty();
				assertThat(candidates)
						.as("SOUNDNESS: Mr X's TRUE location %s must be in the belief%n%s", trueMrX, context)
						.contains(trueMrX);
				assertThat(candidates)
						.as("belief must exclude detective-occupied stations%n%s", context)
						.doesNotContainAnyElementsOf(detectiveLocations(state));
				assertThat(stateless)
						.as("the belief can only ever be a subset of the stateless set%n%s", context)
						.containsAll(candidates);

				statelessTotal += stateless.size();
				beliefTotal += candidates.size();
				samples++;

				if (mrXToMove(state)) {
					Move move = chooseMrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					trace.add("r" + round + " MrX: " + move + " -> " + trueMrX);
					state = state.advance(move);
					round++;
				} else {
					Move move = chooseDetectiveMove(state, distances, rnd, trueMrX, chase);
					trace.add("r" + round + " det: " + move);
					state = state.advance(move);
				}
			}
		}

		System.out.printf("belief: mean candidates %.2f vs stateless %.2f over %d observations%n",
				(double) beliefTotal / samples, (double) statelessTotal / samples, samples);
		assertThat(beliefTotal)
				.as("per-round pruning must actually buy something, or it is not worth the API")
				.isLessThan(statelessTotal);
	}

	/**
	 * A Belief that is only shown some of the boards misses prunes, but must never
	 * invent one: skipping observations may only ever make the set larger, never
	 * unsound.
	 */
	@Test
	public void skippingObservationsStaysSound() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);

		for (int seed = 0; seed < 12; seed++) {
			Random rnd = new Random(500 + seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			Board.GameState state = freshGame(setup, mrXStart);
			MrXLocator.Belief belief = new MrXLocator.Belief(setup.graph);
			int trueMrX = mrXStart;

			int round = 1;
			int half = 0;
			while (state.getWinner().isEmpty() && round <= 16) {
				// Show it only every third board: a caller that forgets, or an AI that is
				// only asked to move now and then.
				if (half++ % 3 == 0) {
					assertThat(belief.observe(state))
							.as("seed %d: true location %s must survive a gappy observer", seed, trueMrX)
							.contains(trueMrX);
				}
				if (mrXToMove(state)) {
					Move move = chooseMrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					state = state.advance(move);
					round++;
				} else {
					state = state.advance(chooseDetectiveMove(state, distances, rnd, trueMrX, true));
				}
			}
		}
	}

	/** A reveal names him outright, so the belief must collapse onto that one station. */
	@Test
	public void aRevealCollapsesTheBelief() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);
		int reveals = 0;

		for (int seed = 0; seed < 8; seed++) {
			Random rnd = new Random(700 + seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			Board.GameState state = freshGame(setup, mrXStart);
			MrXLocator.Belief belief = new MrXLocator.Belief(setup.graph);
			int trueMrX = mrXStart;

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 16) {
				if (mrXToMove(state)) {
					Move move = chooseMrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					state = state.advance(move);
					round++;
					if (state.getWinner().isEmpty()) {
						ImmutableList<uk.ac.bris.cs.scotlandyard.model.LogEntry> log = state.getMrXTravelLog();
						boolean revealed = !log.isEmpty() && log.get(log.size() - 1).location().isPresent();
						ImmutableSet<Integer> candidates = belief.observe(state);
						if (revealed) {
							assertThat(candidates).as("the log names %s", trueMrX).containsExactly(trueMrX);
							reveals++;
						}
					}
				} else {
					state = state.advance(chooseDetectiveMove(state, distances, rnd, trueMrX, false));
				}
			}
		}
		assertThat(reveals).isGreaterThanOrEqualTo(8);
	}
}
