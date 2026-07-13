package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
 * Tests for {@link MrXLocator}, the detective-side inference of where Mr X is.
 *
 * <p>
 * The load-bearing property is <b>soundness</b>: Mr X's true station must always
 * be one of the candidates. Everything else — how tight the set is, which single
 * station is named as most likely — is tuning. If soundness breaks, a detective
 * AI built on this is chasing a phantom.
 */
public class MrXLocatorTest {

	/** Number of nodes on the standard London map. */
	private static final int MAP_NODES = 199;

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

	/** Where a move leaves the mover standing. */
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

	/** How far the given station is from the nearest detective; big is safe for Mr X. */
	private static int safety(Board board, Distances distances, int station) {
		int best = Integer.MAX_VALUE;
		for (int d : detectiveLocations(board)) {
			best = Math.min(best, distances.hops(d, station));
		}
		return best;
	}

	/**
	 * Mr X plays evasively — he walks to whichever reachable station is furthest
	 * from the nearest detective. Every third round he takes a SECRET move if one is
	 * on offer, and every fifth a DOUBLE, so the log gets a genuine mix of tickets.
	 */
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
			// Small random jitter so different seeds walk genuinely different games.
			int score = safety(board, distances, destinationOf(m)) * 4 + rnd.nextInt(4);
			if (score > bestScore) {
				bestScore = score;
				best = m;
			}
		}
		return best;
	}

	/**
	 * Mr X moves evasively but spends only ordinary tickets — no secrets, no doubles.
	 * Used to walk him up to a reveal with his secret purse untouched.
	 */
	private static Move choosePlainMrXMove(Board board, Distances distances, Random rnd) {
		Move best = null;
		int bestScore = Integer.MIN_VALUE;
		for (Move m : board.getAvailableMoves()) {
			if (!(m instanceof Move.SingleMove s) || s.ticket == Ticket.SECRET) continue;
			int score = safety(board, distances, s.destination) * 4 + rnd.nextInt(4);
			if (score > bestScore) {
				bestScore = score;
				best = m;
			}
		}
		return best;
	}

	/**
	 * Detectives either chase (they are handed Mr X's true station — a test harness
	 * may cheat where an AI may not; this is what a <i>good</i> detective AI would
	 * approximate, and it is the traffic that stresses the locator) or wander.
	 */
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

	/** Does the last log entry name a station? (i.e. Mr X has just surfaced) */
	private static boolean justRevealed(Board board) {
		ImmutableList<LogEntry> log = board.getMrXTravelLog();
		return !log.isEmpty() && log.get(log.size() - 1).location().isPresent();
	}

	private static String describe(Board board, int trueMrX) {
		StringBuilder sb = new StringBuilder();
		sb.append("true MrX = ").append(trueMrX)
				.append(", detectives = ").append(detectiveLocations(board))
				.append(", log = [");
		for (LogEntry e : board.getMrXTravelLog()) {
			sb.append(e.ticket()).append(e.location().map(l -> "@" + l).orElse("@?")).append(' ');
		}
		return sb.append("]").toString();
	}

	// ---------------------------------------------------- 1. soundness, 2. never empty
	//                                                       6. pruning, 7. mostLikely

	/**
	 * THE test. Plays whole games, tracking Mr X's true station at every step, and
	 * demands that the inferred candidate set always contains it.
	 */
	@Test
	public void trueLocationIsAlwaysACandidate() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);

		for (int seed = 0; seed < 40; seed++) {
			Random rnd = new Random(seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			boolean chase = seed % 2 == 0;

			Board.GameState state = freshGame(setup, mrXStart);
			int trueMrX = mrXStart;
			List<String> trace = new ArrayList<>();
			trace.add("seed=" + seed + " chase=" + chase + " MrX starts at " + mrXStart);

			check(state, trueMrX, trace, distances);

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 16) {
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
				if (state.getWinner().isEmpty()) check(state, trueMrX, trace, distances);
			}
		}
	}

	/** All the always-true invariants, asserted after every single half-move. */
	private static void check(Board board, int trueMrX, List<String> trace, Distances distances) {
		ImmutableSet<Integer> candidates = MrXLocator.possibleLocations(board);
		String context = String.join("\n", trace) + "\n" + describe(board, trueMrX)
				+ "\ncandidates(" + candidates.size() + ") = " + candidates;

		// 2. never empty, and never nonsense
		assertThat(candidates).as("candidate set must never be empty%n%s", context).isNotEmpty();
		assertThat(candidates.size()).as("candidate set must never exceed the map%n%s", context)
				.isLessThanOrEqualTo(MAP_NODES);

		// 1. SOUNDNESS
		assertThat(candidates).as("Mr X's TRUE location %s must be a candidate%n%s", trueMrX, context)
				.contains(trueMrX);

		// 6. a station a detective stands on can never hold Mr X
		assertThat(candidates).as("candidates must exclude detective-occupied stations%n%s", context)
				.doesNotContainAnyElementsOf(detectiveLocations(board));

		// 7. mostLikelyLocation is present and is one of the candidates
		Optional<Integer> likely = MrXLocator.mostLikelyLocation(board, distances);
		assertThat(likely).as("mostLikelyLocation must be present%n%s", context).isPresent();
		assertThat(candidates).as("mostLikelyLocation must be a candidate%n%s", context)
				.contains(likely.orElseThrow());
	}

	// ------------------------------------------------------------- 3. reveal rounds

	/**
	 * The moment Mr X's station is written into the log, the inference must collapse
	 * onto exactly that station.
	 */
	@Test
	public void revealCollapsesTheCandidateSetToASingleton() throws IOException {
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
						ImmutableSet<Integer> candidates = MrXLocator.possibleLocations(state);
						assertThat(candidates)
								.as("just after a reveal, the log names %s", trueMrX)
								.containsExactly(trueMrX);
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

	// -------------------------------------------------------------------- 4. bloom

	/**
	 * Measures how fast the candidate set grows once Mr X submerges again. No upper
	 * bound is asserted beyond "not nonsense" — the growth rate is a property of the
	 * map, not of the code's correctness — but the sizes are printed, because they
	 * are what tells you whether a detective AI is guessing usefully.
	 */
	@Test
	public void candidateSetBloomsAfterAReveal() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);
		List<List<Integer>> allBlooms = new ArrayList<>();

		for (int seed = 0; seed < 6; seed++) {
			Random rnd = new Random(200 + seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			Board.GameState state = freshGame(setup, mrXStart);
			int trueMrX = mrXStart;
			List<Integer> bloom = null;

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 13) {
				if (mrXToMove(state)) {
					Move move = chooseMrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					state = state.advance(move);
					round++;
					if (!state.getWinner().isEmpty()) break;
					int size = MrXLocator.possibleLocations(state).size();
					if (justRevealed(state)) {
						if (bloom == null) bloom = new ArrayList<>();
						else break; // stop at the next reveal
					}
					if (bloom != null) {
						bloom.add(size);
						assertThat(size).as("candidate set must stay within the map").isBetween(1, MAP_NODES);
					}
				} else {
					state = state.advance(chooseDetectiveMove(state, distances, rnd, trueMrX, false));
				}
			}
			if (bloom != null && bloom.size() >= 3) allBlooms.add(bloom);
		}

		assertThat(allBlooms).as("should have measured at least one bloom").isNotEmpty();
		for (List<Integer> bloom : allBlooms) {
			System.out.println("bloom after reveal (sizes per Mr X move): " + bloom);
			assertThat(bloom.get(0)).as("a reveal pins him to one station").isEqualTo(1);
			assertThat(bloom.get(bloom.size() - 1)).as("uncertainty grows once he submerges")
					.isGreaterThan(bloom.get(0));
		}
	}

	// ------------------------------------------------------------------- 5. secret

	/**
	 * A secret ticket says nothing: from a known station he could be at <i>any</i>
	 * neighbour, along any edge including ferries.
	 */
	@Test
	public void secretMoveFromAKnownStationYieldsEveryNeighbour() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);
		int checked = 0;

		for (int seed = 0; seed < 6; seed++) {
			Random rnd = new Random(300 + seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			Board.GameState state = freshGame(setup, mrXStart);
			int trueMrX = mrXStart;

			// Play until Mr X has just surfaced; the locator now knows exactly where he is.
			int round = 1;
			while (state.getWinner().isEmpty() && !justRevealed(state) && round <= 6) {
				if (mrXToMove(state)) {
					// no secrets, no doubles before the reveal: he must reach it with a full purse
					Move move = choosePlainMrXMove(state, distances, rnd);
					if (move == null) break;
					trueMrX = destinationOf(move);
					state = state.advance(move);
					round++;
				} else {
					state = state.advance(chooseDetectiveMove(state, distances, rnd, trueMrX, false));
				}
			}
			if (!justRevealed(state) || !state.getWinner().isEmpty()) continue;

			int revealed = trueMrX;
			assertThat(MrXLocator.possibleLocations(state)).containsExactly(revealed);

			// Run the detectives, then make Mr X vanish with a secret ticket.
			while (state.getWinner().isEmpty() && !mrXToMove(state)) {
				state = state.advance(chooseDetectiveMove(state, distances, rnd, trueMrX, false));
			}
			if (!state.getWinner().isEmpty()) continue;

			Move secret = null;
			for (Move m : state.getAvailableMoves()) {
				if (m instanceof Move.SingleMove s && s.ticket == Ticket.SECRET) {
					secret = m;
					break;
				}
			}
			assertThat(secret).as("Mr X holds secret tickets, so a secret move must be available").isNotNull();
			trueMrX = destinationOf(secret);
			state = state.advance(secret);
			if (!state.getWinner().isEmpty()) continue;

			Set<Integer> expected = new HashSet<>(setup.graph.adjacentNodes(revealed));
			expected.removeAll(detectiveLocations(state));

			assertThat(MrXLocator.possibleLocations(state))
					.as("a secret move from %s could have crossed any edge", revealed)
					.containsExactlyInAnyOrderElementsOf(expected);
			checked++;
		}
		assertThat(checked).as("at least one secret-from-a-reveal scenario must have been built")
				.isGreaterThanOrEqualTo(1);
	}
}
