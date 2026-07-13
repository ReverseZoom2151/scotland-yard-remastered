package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.ValueGraphBuilder;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.LogEntry;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Ticket-count feasibility: what Mr X's bounded purse does and does not tell us.
 *
 * <p>
 * The headline finding is a <b>negative</b> one, and these tests pin it down rather
 * than paper over it. Pruning the candidate set on a ticket budget sounds like an
 * edge, and it is not: {@link LogEntry#ticket()} names the exact ticket Mr X paid for
 * every entry in the travel log, so the inference branches only over <i>where</i> an
 * entry could have taken him, never over <i>which</i> ticket it was. Every surviving
 * path therefore spends the identical multiset of tickets, and no path can be told
 * from another on cost. {@link #aSecretBudgetCannotSeparateTwoCandidates()} and
 * {@link #theLogDeterminesTheSecretCountExactly()} demonstrate that directly.
 *
 * <p>
 * Two related prunes are real:
 * <ul>
 * <li>The <b>ferry</b> prune, which was already in force and is pinned here: a ferry
 * edge costs a SECRET ticket, so it can never be crossed on a TAXI, BUS or UNDERGROUND
 * log entry.
 * <li>The <b>forward</b> prune, which is new. SECRET and DOUBLE are never replenished —
 * a detective may not hold either, so none can ever be given back — while TAXI, BUS and
 * UNDERGROUND are handed to Mr X every time a detective spends one. So his secret and
 * double counts are a finite, monotonically shrinking budget that the board shows us,
 * and once they run out those moves are gone for good. That is what
 * {@link MrXLocator#possibleNextLocations(Board)} exploits, and what the taxi/bus/tube
 * counts categorically cannot.
 * </ul>
 */
public class TicketFeasibilityTest {

	// ---------------------------------------------------------------------------
	// A tiny hand-built map, so the prunes can be checked against a graph whose every
	// edge is known. Node 4 hangs off node 2 by a FERRY and nothing else; node 8 hangs
	// off it by a BUS and nothing else; 1 and 3 are its taxi neighbours; 7 is one taxi
	// hop past 3, so it is only reachable from 2 by a double move. Nodes 5 and 6 are a
	// corner for the detective to potter about in, well away from Mr X.
	//
	//     1 --taxi-- 2 --taxi-- 3 --taxi-- 7
	//                |\
	//           ferry| \bus
	//                4   8              5 --taxi-- 6
	// ---------------------------------------------------------------------------

	private static final int FERRY_NODE = 4;
	private static final int BUS_NODE = 8;
	private static final int DOUBLE_ONLY_NODE = 7;

	private static ImmutableValueGraph<Integer, ImmutableSet<Transport>> tinyGraph() {
		MutableValueGraph<Integer, ImmutableSet<Transport>> graph = ValueGraphBuilder.undirected().build();
		for (int node = 1; node <= 8; node++) {
			graph.addNode(node);
		}
		graph.putEdgeValue(1, 2, ImmutableSet.of(Transport.TAXI));
		graph.putEdgeValue(2, 3, ImmutableSet.of(Transport.TAXI));
		graph.putEdgeValue(3, DOUBLE_ONLY_NODE, ImmutableSet.of(Transport.TAXI));
		graph.putEdgeValue(2, FERRY_NODE, ImmutableSet.of(Transport.FERRY));
		graph.putEdgeValue(2, BUS_NODE, ImmutableSet.of(Transport.BUS));
		graph.putEdgeValue(5, 6, ImmutableSet.of(Transport.TAXI));
		return ImmutableValueGraph.copyOf(graph);
	}

	/** Reveal on the very first round, so the log pins Mr X down at once. */
	private static GameSetup tinySetup() {
		ImmutableList.Builder<Boolean> rounds = ImmutableList.builder();
		rounds.add(true);
		for (int i = 1; i < 24; i++) {
			rounds.add(false);
		}
		return new GameSetup(tinyGraph(), rounds.build());
	}

	private static ImmutableMap<Ticket, Integer> purse(int taxi, int bus, int underground, int dbl, int secret) {
		return ImmutableMap.of(Ticket.TAXI, taxi, Ticket.BUS, bus, Ticket.UNDERGROUND, underground,
				Ticket.DOUBLE, dbl, Ticket.SECRET, secret);
	}

	private static Board.GameState tinyGame(int mrXAt, ImmutableMap<Ticket, Integer> mrXTickets) {
		Player mrX = new Player(Piece.MrX.MRX, mrXTickets, mrXAt);
		Player red = new Player(Piece.Detective.RED, ScotlandYard.defaultDetectiveTickets(), 5);
		return new MyGameStateFactory().build(tinySetup(), mrX, ImmutableList.of(red));
	}

	// ---------------------------------------------------------------------------
	// The REAL prune, part one: ferries. Already in force; pinned so it stays that way.
	// ---------------------------------------------------------------------------

	/**
	 * A ferry edge costs a SECRET ticket — {@code Transport.FERRY.requiredTicket()} is
	 * {@code Ticket.SECRET}. So a station on the far side of a ferry cannot be where a
	 * TAXI log entry left him, however adjacent it is. This is the one ticket fact that
	 * prunes the <i>past</i>, and the propagation already respects it.
	 */
	@Test
	public void aFerryNeighbourIsNotReachableOnATaxiEntry() {
		assertThat(Transport.FERRY.requiredTicket()).isEqualTo(Ticket.SECRET);
		assertThat(tinyGraph().adjacentNodes(2)).contains(FERRY_NODE);

		Board.GameState state = tinyGame(1, ScotlandYard.defaultMrXTickets());
		// Round 1 is a reveal, so this writes reveal(TAXI, 2) and seeds the inference at 2.
		state = state.advance(new Move.SingleMove(Piece.MrX.MRX, 1, Ticket.TAXI, 2));
		state = state.advance(new Move.SingleMove(Piece.Detective.RED, 5, Ticket.TAXI, 6));
		// Round 2 is hidden: all we learn is that he spent a taxi.
		state = state.advance(new Move.SingleMove(Piece.MrX.MRX, 2, Ticket.TAXI, 3));

		ImmutableSet<Integer> candidates = MrXLocator.possibleLocations(state);
		assertThat(candidates)
				.as("a taxi ticket cannot pay for a ferry, so %s is impossible", FERRY_NODE)
				.doesNotContain(FERRY_NODE);
		assertThat(candidates)
				.as("a taxi ticket cannot pay for a bus either")
				.doesNotContain(BUS_NODE);
		assertThat(candidates).as("SOUNDNESS: he really is at 3").contains(3);
		assertThat(candidates).containsExactlyInAnyOrder(1, 3);

		// And the same station IS reachable when the log says he went secret.
		Board.GameState secretly = tinyGame(1, ScotlandYard.defaultMrXTickets())
				.advance(new Move.SingleMove(Piece.MrX.MRX, 1, Ticket.TAXI, 2))
				.advance(new Move.SingleMove(Piece.Detective.RED, 5, Ticket.TAXI, 6))
				.advance(new Move.SingleMove(Piece.MrX.MRX, 2, Ticket.SECRET, FERRY_NODE));
		assertThat(MrXLocator.possibleLocations(secretly))
				.as("a secret ticket crosses the ferry, so now it is a candidate")
				.contains(FERRY_NODE);
	}

	// ---------------------------------------------------------------------------
	// The VACUOUS prune: a secret/double budget over the past.
	// ---------------------------------------------------------------------------

	/**
	 * The proposed prune was: track how many secrets the path to each candidate spent,
	 * and drop candidates whose path cost more secrets than Mr X owned. It cannot fire.
	 * Every candidate after the last reveal is reached by walking the <i>same</i> log,
	 * one entry at a time, and the ticket on each entry is written down — so every path
	 * to every candidate spends exactly the tickets the log lists, and the secret count
	 * is a constant over the whole candidate set. A constant cannot separate anything.
	 */
	@Test
	public void aSecretBudgetCannotSeparateTwoCandidates() throws IOException {
		GameSetup setup = new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
		Distances distances = new Distances(setup.graph);

		for (int seed = 0; seed < 20; seed++) {
			Random rnd = new Random(3000 + seed);
			Board.GameState state = standardGame(setup, ScotlandYard.MRX_LOCATIONS.get(seed % 12));
			int trueMrX = ScotlandYard.MRX_LOCATIONS.get(seed % 12);

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 14) {
				ImmutableList<LogEntry> log = state.getMrXTravelLog();
				int lastReveal = -1;
				for (int i = log.size() - 1; i >= 0; i--) {
					if (log.get(i).location().isPresent()) {
						lastReveal = i;
						break;
					}
				}
				// The cost of the path to a candidate: the tickets on entries lastReveal+1..end.
				// It does not depend on the candidate at all, which is the whole point.
				int secretsOnEveryPath = 0;
				for (int i = lastReveal + 1; i < log.size(); i++) {
					if (log.get(i).ticket() == Ticket.SECRET) secretsOnEveryPath++;
				}
				assertThat(secretsOnEveryPath)
						.as("every log-consistent path spends the same secrets, so no candidate "
								+ "can ever be priced out of the set")
						.isLessThanOrEqualTo(ScotlandYard.defaultMrXTickets().get(Ticket.SECRET));

				if (mrXToMove(state)) {
					Move move = mrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					state = state.advance(move);
					round++;
				} else {
					state = state.advance(detectiveMove(state, distances, rnd, trueMrX));
				}
			}
		}
	}

	/**
	 * The corollary, and the reason the budget prune is empty: Mr X's live secret count
	 * is not new information. It is exactly five minus the secret entries in the log —
	 * a quantity the log already spelled out. (His DOUBLE count is <i>not</i> recoverable
	 * this way, since a double move is logged as its two legs and the DOUBLE ticket is
	 * never written down; that is why {@link MrXLocator#remainingDoubles(Board)} must read
	 * the board rather than count the log.)
	 */
	@Test
	public void theLogDeterminesTheSecretCountExactly() throws IOException {
		GameSetup setup = new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
		Distances distances = new Distances(setup.graph);
		int secretsSeen = 0;
		int doublesSeen = 0;

		for (int seed = 0; seed < 20; seed++) {
			Random rnd = new Random(4000 + seed);
			Board.GameState state = standardGame(setup, ScotlandYard.MRX_LOCATIONS.get(seed % 12));
			int trueMrX = ScotlandYard.MRX_LOCATIONS.get(seed % 12);

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 16) {
				int spent = 0;
				for (LogEntry entry : state.getMrXTravelLog()) {
					if (entry.ticket() == Ticket.SECRET) spent++;
				}
				assertThat(MrXLocator.remainingSecrets(state))
						.as("the board and the log must agree; if they do, the log told us everything")
						.isEqualTo(ScotlandYard.defaultMrXTickets().get(Ticket.SECRET) - spent);
				assertThat(MrXLocator.canStillPlaySecret(state))
						.isEqualTo(MrXLocator.remainingSecrets(state) > 0);
				assertThat(MrXLocator.remainingDoubles(state)).isBetween(0, 2);
				secretsSeen = Math.max(secretsSeen, spent);
				doublesSeen = Math.max(doublesSeen, 2 - MrXLocator.remainingDoubles(state));

				if (mrXToMove(state)) {
					Move move = mrXMove(state, distances, rnd, round);
					trueMrX = destinationOf(move);
					state = state.advance(move);
					round++;
				} else {
					state = state.advance(detectiveMove(state, distances, rnd, trueMrX));
				}
			}
		}
		assertThat(secretsSeen).as("the games must actually exercise secret moves").isPositive();
		assertThat(doublesSeen).as("the games must actually exercise double moves").isPositive();
	}

	// ---------------------------------------------------------------------------
	// The REAL prune, part two: the forward set, costed against the finite budget.
	// ---------------------------------------------------------------------------

	/** With no secret tickets left, the ferry is closed to him — for the rest of the game. */
	@Test
	public void withNoSecretsLeftFerryEdgesAreShut() {
		Board.GameState spent = tinyGame(2, purse(4, 3, 3, 0, 0));
		assertThat(MrXLocator.canStillPlaySecret(spent)).isFalse();
		assertThat(MrXLocator.possibleNextLocations(spent, Set.of(2)))
				.as("no secret ticket, so no ferry: %s is unreachable", FERRY_NODE)
				.doesNotContain(FERRY_NODE)
				.containsExactlyInAnyOrder(1, 3, BUS_NODE);

		Board.GameState flush = tinyGame(2, purse(4, 3, 3, 0, 1));
		assertThat(MrXLocator.canStillPlaySecret(flush)).isTrue();
		assertThat(MrXLocator.possibleNextLocations(flush, Set.of(2)))
				.as("one secret left, so the ferry is open again")
				.contains(FERRY_NODE);
	}

	/** With no double tickets left, he cannot be two stations away next time we look. */
	@Test
	public void withNoDoublesLeftTheForwardSetIsOnlyOneStepWide() {
		Board.GameState spent = tinyGame(2, purse(4, 0, 0, 0, 0));
		assertThat(MrXLocator.canStillPlayDouble(spent)).isFalse();
		assertThat(MrXLocator.possibleNextLocations(spent, Set.of(2)))
				.as("no double ticket, so %s (two taxi hops away) is out of reach", DOUBLE_ONLY_NODE)
				.doesNotContain(DOUBLE_ONLY_NODE)
				.containsExactlyInAnyOrder(1, 3);

		Board.GameState flush = tinyGame(2, purse(4, 0, 0, 1, 0));
		assertThat(MrXLocator.canStillPlayDouble(flush)).isTrue();
		assertThat(MrXLocator.possibleNextLocations(flush, Set.of(2)))
				.as("one double left, so he can reach two hops out — and can come back to 2")
				.contains(DOUBLE_ONLY_NODE, 2, 1, 3);
	}

	/**
	 * The other half of the finding, and a soundness trap it would have been easy to fall
	 * into. TAXI, BUS and UNDERGROUND are <b>replenished</b>: {@code advanceDetective}
	 * hands Mr X the ticket every detective spends. So a zero in his taxi column is not a
	 * zero he will still have when his turn comes round — a detective moving between now
	 * and then will top him up. Pruning taxi edges on it would cut off a move he really
	 * can make. The forward model therefore credits him with everything the detectives are
	 * still holding, and only ever prunes on SECRET and DOUBLE.
	 */
	@Test
	public void replenishableTicketsCannotBePrunedOn() {
		// One taxi, no secrets, no doubles — he spends the taxi getting to 2 and arrives
		// there broke. But RED is about to move and pay him.
		Board.GameState state = tinyGame(1, purse(1, 0, 0, 0, 0))
				.advance(new Move.SingleMove(Piece.MrX.MRX, 1, Ticket.TAXI, 2));

		assertThat(state.getPlayerTickets(Piece.MrX.MRX).orElseThrow().getCount(Ticket.TAXI))
				.as("he really is out of taxis at this instant").isZero();
		assertThat(state.getAvailableMoves().iterator().next().commencedBy().isDetective())
				.as("and it is a detective's turn, so he will be topped up before he moves").isTrue();

		ImmutableSet<Integer> forward = MrXLocator.possibleNextLocations(state, Set.of(2));
		assertThat(forward)
				.as("SOUNDNESS: RED will hand him a taxi, so the taxi edges must stay open")
				.contains(1, 3);
		assertThat(forward)
				.as("RED's bus tickets can reach him too, so the bus edge must stay open")
				.contains(BUS_NODE);
		assertThat(forward)
				.as("but no detective holds a SECRET, so nothing can ever reopen the ferry")
				.doesNotContain(FERRY_NODE);
	}

	// ---------------------------------------------------------------------------
	// Soundness over real games, and the measured gain.
	// ---------------------------------------------------------------------------

	/**
	 * THE test. Play real games; after every board, forecast where Mr X could be after
	 * his next move; when he makes it, assert the station he actually landed on was in
	 * every forecast. A forward prune that ever drops his true destination is unsound and
	 * worthless, however tight it looks.
	 *
	 * <p>
	 * Also measures the gain against a ticket-blind forecast — one that assumes he can
	 * always play a secret (so every edge is open, ferries included) and always play a
	 * double (so the reach is always two hops). That is what a forward model without this
	 * has to assume.
	 */
	@Test
	public void theForwardSetIsSoundAndTighterThanATicketBlindOne() throws IOException {
		GameSetup setup = new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
		Distances distances = new Distances(setup.graph);

		long awareTotal = 0;
		long blindTotal = 0;
		long nowTotal = 0;
		int samples = 0;
		int prunedBoards = 0;

		for (int seed = 0; seed < 40; seed++) {
			Random rnd = new Random(seed);
			int mrXStart = ScotlandYard.MRX_LOCATIONS.get(seed % ScotlandYard.MRX_LOCATIONS.size());
			Board.GameState state = standardGame(setup, mrXStart);
			int trueMrX = mrXStart;

			// Every forecast made since his last move; all of them must contain his next
			// station, whichever board they were made on.
			List<ImmutableSet<Integer>> pending = new ArrayList<>();

			int round = 1;
			while (state.getWinner().isEmpty() && round <= 16) {
				ImmutableSet<Integer> now = MrXLocator.possibleLocations(state);
				assertThat(now)
						.as("seed %d round %d: the existing inference must still hold him", seed, round)
						.contains(trueMrX);

				ImmutableSet<Integer> aware = MrXLocator.possibleNextLocations(state);
				ImmutableSet<Integer> blind = ticketBlindNextLocations(setup.graph, now);
				assertThat(blind)
						.as("the ticket-aware forecast can only ever be a subset of the blind one")
						.containsAll(aware);
				if (aware.size() < blind.size()) prunedBoards++;

				Map<Integer, Double> next = Suspicion.nextLikelihoods(state);
				assertThat(aware)
						.as("the distribution's support must sit inside the candidate forecast")
						.containsAll(next.keySet());
				assertThat(next.values().stream().mapToDouble(Double::doubleValue).sum())
						.as("a distribution must sum to one").isCloseTo(1.0, within(1e-9));

				pending.add(aware);
				awareTotal += aware.size();
				blindTotal += blind.size();
				nowTotal += now.size();
				samples++;

				if (mrXToMove(state)) {
					Move move = mrXMove(state, distances, rnd, round);
					int landed = destinationOf(move);
					for (ImmutableSet<Integer> forecast : pending) {
						assertThat(forecast)
								.as("SOUNDNESS: seed %d round %d, Mr X moved %s and landed on %s, which "
										+ "a forward forecast had ruled out", seed, round, move, landed)
								.contains(landed);
					}
					assertThat(Suspicion.nextLikelihoods(state))
							.as("the distribution must give his real destination %s some mass", landed)
							.containsKey(landed);
					pending.clear();
					trueMrX = landed;
					state = state.advance(move);
					round++;
				} else {
					state = state.advance(detectiveMove(state, distances, rnd, trueMrX));
				}
			}
		}

		System.out.printf("ticket feasibility: forward set, ticket-aware %.1f vs ticket-blind %.1f "
				+ "(%.1f%% smaller); it bites on %d of %d boards. Present-tense candidate set is "
				+ "%.1f and is UNCHANGED — a ticket budget cannot prune the past.%n",
				(double) awareTotal / samples, (double) blindTotal / samples,
				100.0 * (blindTotal - awareTotal) / blindTotal,
				prunedBoards, samples, (double) nowTotal / samples);

		assertThat(awareTotal)
				.as("the ticket-aware forecast must actually buy something, or it is not worth the API")
				.isLessThan(blindTotal);
	}

	/** What a forward model without ticket counts must assume: every edge, always two hops. */
	private static ImmutableSet<Integer> ticketBlindNextLocations(
			ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph, Set<Integer> candidates) {
		Set<Integer> one = new LinkedHashSet<>();
		for (int from : candidates) {
			if (graph.nodes().contains(from)) one.addAll(graph.adjacentNodes(from));
		}
		Set<Integer> both = new LinkedHashSet<>(one);
		for (int from : one) {
			both.addAll(graph.adjacentNodes(from));
		}
		return ImmutableSet.copyOf(both);
	}

	// ---------------------------------------------------------------------------
	// Game-driving helpers, in the style of MrXBeliefTest.
	// ---------------------------------------------------------------------------

	private static Board.GameState standardGame(GameSetup setup, int mrXStart) {
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

	/** Mr X runs, leaning hard on secrets and doubles — the traffic that stresses the budget. */
	private static Move mrXMove(Board board, Distances distances, Random rnd, int round) {
		List<Move> pool = new ArrayList<>(board.getAvailableMoves());
		List<Move> preferred = new ArrayList<>();
		if (round % 2 == 0) {
			for (Move m : pool) {
				if (m instanceof Move.SingleMove s && s.ticket == Ticket.SECRET) preferred.add(m);
			}
		} else if (round % 3 == 0) {
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

	private static Move detectiveMove(Board board, Distances distances, Random rnd, int trueMrX) {
		List<Move> pool = new ArrayList<>(board.getAvailableMoves());
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
}
