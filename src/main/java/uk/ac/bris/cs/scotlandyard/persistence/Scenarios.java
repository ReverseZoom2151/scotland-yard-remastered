package uk.ac.bris.cs.scotlandyard.persistence;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Piece.Detective;
import uk.ac.bris.cs.scotlandyard.model.Piece.MrX;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * A named set of interesting but hard-to-reach positions, for demos and for QA:
 * getting a real game into the last round, or onto a ferry stop, by playing it
 * out by hand takes a quarter of an hour.
 *
 * <p>
 * Each scenario is a {@link GameRecord} — the same thing a saved game is — so the
 * UI starts one exactly the way it loads one. Every scenario is a legal,
 * non-terminal position.
 */
public final class Scenarios {

	/** MrX's travel log is nearly full and he only has to survive one more round. */
	public static final String LAST_ROUND = "Last round";
	/** Detectives are breathing down his neck; only a secret or double gets him out. */
	public static final String CORNERED = "Mr X cornered";
	/** The detectives are all but out of tickets. */
	public static final String STRANDED = "Detectives stranded";
	/** MrX's next move is logged in a reveal round. */
	public static final String REVEAL = "Reveal round";
	/** MrX is sitting on one of the four ferry stops. */
	public static final String FERRY = "Mr X on a ferry stop";

	private Scenarios() {
	}

	/**
	 * @return every scenario, keyed by its display name, in menu order
	 */
	@Nonnull
	public static Map<String, Supplier<GameState>> all() {
		Map<String, Supplier<GameState>> scenarios = new LinkedHashMap<>();
		records().forEach((name, record) -> scenarios.put(name, () -> record.get().replay().finalState()));
		return scenarios;
	}

	/**
	 * The same scenarios as {@link #all()}, but as records, so that the UI can build
	 * a {@code Model} for them and can save them back out again.
	 *
	 * @return every scenario, keyed by its display name, in menu order
	 */
	@Nonnull
	public static Map<String, Supplier<GameRecord>> records() {
		Map<String, Supplier<GameRecord>> scenarios = new LinkedHashMap<>();
		scenarios.put(LAST_ROUND, Scenarios::lastRound);
		scenarios.put(CORNERED, Scenarios::cornered);
		scenarios.put(STRANDED, Scenarios::stranded);
		scenarios.put(REVEAL, Scenarios::revealRound);
		scenarios.put(FERRY, Scenarios::ferryStop);
		return scenarios;
	}

	/**
	 * @param name the scenario name, see the constants on this class
	 * @return that scenario
	 * @throws IllegalArgumentException if there is no such scenario
	 */
	@Nonnull
	public static GameRecord record(@Nonnull String name) {
		Supplier<GameRecord> supplier = records().get(Objects.requireNonNull(name, "name"));
		if (supplier == null) {
			throw new IllegalArgumentException("No such scenario: " + name);
		}
		return supplier.get();
	}

	// --- the scenarios
	// ---------------------------------------------------------------

	/**
	 * A three round game with two rounds already played: MrX is one move from
	 * escaping, and that move is a reveal.
	 */
	private static GameRecord lastRound() {
		ImmutableList<Boolean> rounds = ImmutableList.of(false, false, true);
		Player mrX = mrX(106, 4, 3, 3, 2, 5);
		ImmutableList<Player> detectives = ImmutableList.of(
				detective(Detective.RED, 26, 11, 8, 4),
				detective(Detective.GREEN, 29, 11, 8, 4),
				detective(Detective.BLUE, 50, 11, 8, 4));
		return playRounds(rounds, mrX, detectives, 2);
	}

	/**
	 * MrX is boxed in on all sides on the standard map; taxi hops walk him straight
	 * into a detective, so he has to spend a secret or a double to live.
	 */
	private static GameRecord cornered() {
		// Station 128 is one of the busiest on the map; ring it with detectives, but
		// leave one way out or MrX is caught before he has played at all.
		int home = 128;
		List<Integer> neighbours = new ArrayList<>(GameRecord.standardGraph().adjacentNodes(home));
		neighbours.sort(Integer::compareTo);
		List<Detective> pieces = List.of(Detective.RED, Detective.GREEN, Detective.BLUE,
				Detective.WHITE, Detective.YELLOW);
		int ringed = Math.min(pieces.size(), neighbours.size() - 1);
		if (ringed < 2) {
			throw new IllegalStateException("Station " + home + " is not busy enough to corner MrX");
		}
		List<Player> detectives = new ArrayList<>();
		for (int i = 0; i < ringed; i++) {
			detectives.add(detective(pieces.get(i), neighbours.get(i), 11, 8, 4));
		}
		// no taxi, bus or underground tickets at all: every escape is a secret one
		Player mrX = mrX(home, 0, 0, 0, 2, 5);
		GameRecord record = GameRecord.of(standardSetup(), mrX, ImmutableList.copyOf(detectives),
				ImmutableList.of());
		verifyPlayable(record.replay().finalState());
		return record;
	}

	/** Detectives with a single ticket each, and one with none at all. */
	private static GameRecord stranded() {
		Player mrX = mrX(104, 4, 3, 3, 2, 5);
		ImmutableList<Player> detectives = ImmutableList.of(
				detective(Detective.RED, 26, 1, 0, 0),
				detective(Detective.GREEN, 29, 1, 0, 0),
				// out of the game entirely: no ticket of any kind
				detective(Detective.BLUE, 50, 0, 0, 0));
		GameRecord record = GameRecord.of(standardSetup(), mrX, detectives, ImmutableList.of());
		verifyPlayable(record.replay().finalState());
		return record;
	}

	/**
	 * Two rounds of a standard game are already in the log, so MrX's next move lands
	 * on round three: a reveal.
	 */
	private static GameRecord revealRound() {
		Player mrX = mrX(106, 4, 3, 3, 2, 5);
		ImmutableList<Player> detectives = ImmutableList.of(
				detective(Detective.RED, 26, 11, 8, 4),
				detective(Detective.GREEN, 29, 11, 8, 4),
				detective(Detective.BLUE, 50, 11, 8, 4));
		return playRounds(ScotlandYard.STANDARD24ROUNDS, mrX, detectives, 2);
	}

	/** MrX starts on a ferry stop, with the secret tickets a ferry demands. */
	private static GameRecord ferryStop() {
		int stop = ferryStops().stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("The standard map has no ferry edges"));
		Player mrX = mrX(stop, 4, 3, 3, 2, 5);
		ImmutableList<Player> detectives = ImmutableList.of(
				detective(Detective.RED, 26, 11, 8, 4),
				detective(Detective.GREEN, 29, 11, 8, 4),
				detective(Detective.BLUE, 50, 11, 8, 4));
		GameRecord record = GameRecord.of(standardSetup(), mrX, detectives, ImmutableList.of());
		verifyPlayable(record.replay().finalState());
		return record;
	}

	/** @return every station with a ferry edge, lowest first */
	@Nonnull
	public static ImmutableSet<Integer> ferryStops() {
		ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = GameRecord.standardGraph();
		return graph.edges().stream()
				.filter(edge -> graph.edgeValueOrDefault(edge, ImmutableSet.of()).contains(Transport.FERRY))
				.flatMap(edge -> ImmutableList.of(edge.nodeU(), edge.nodeV()).stream())
				.sorted()
				.collect(ImmutableSet.toImmutableSet());
	}

	// --- machinery
	// -------------------------------------------------------------------

	private static GameSetup standardSetup() {
		return new GameSetup(GameRecord.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
	}

	private static Player mrX(int location, int taxi, int bus, int underground, int x2, int secret) {
		return new Player(MrX.MRX, ImmutableMap.of(
				Ticket.TAXI, taxi,
				Ticket.BUS, bus,
				Ticket.UNDERGROUND, underground,
				Ticket.DOUBLE, x2,
				Ticket.SECRET, secret), location);
	}

	private static Player detective(Detective piece, int location, int taxi, int bus, int underground) {
		return new Player(piece, ImmutableMap.of(
				Ticket.TAXI, taxi,
				Ticket.BUS, bus,
				Ticket.UNDERGROUND, underground,
				Ticket.DOUBLE, 0,
				Ticket.SECRET, 0), location);
	}

	/**
	 * Plays a game out far enough to fill MrX's travel log, keeping it alive: MrX
	 * runs from the detectives and the detectives, who are only here to fill the
	 * rotation, decline to catch him.
	 *
	 * @param rounds     the reveal rounds
	 * @param mrX        MrX
	 * @param detectives the detectives
	 * @param mrXRounds  how many entries to leave in the travel log
	 * @return the record of the game so far, which is MrX's turn again
	 */
	private static GameRecord playRounds(ImmutableList<Boolean> rounds, Player mrX,
			ImmutableList<Player> detectives, int mrXRounds) {
		GameSetup setup = new GameSetup(GameRecord.standardGraph(), rounds);
		GameState state = new MyGameStateFactory().build(setup, mrX, detectives);
		List<Move> played = new ArrayList<>();
		int mrXLocation = mrX.location();

		while (state.getWinner().isEmpty()
				&& !(state.getMrXTravelLog().size() >= mrXRounds && isMrXToMove(state))) {
			Move move = isMrXToMove(state)
					? flee(state, mrXLocation)
					: shadow(state, mrXLocation);
			if (move.commencedBy().isMrX()) {
				mrXLocation = lastDestination(move);
			}
			played.add(move);
			state = state.advance(move);
		}
		GameRecord record = GameRecord.of(setup, mrX, detectives, played);
		verifyPlayable(record.replay().finalState());
		return record;
	}

	private static boolean isMrXToMove(GameState state) {
		return History.pieceToMove(state).filter(Piece::isMrX).isPresent();
	}

	/** @return a single move taking MrX as far from the detectives as it can */
	private static Move flee(GameState state, int mrXLocation) {
		Map<Integer, Integer> distances = distancesFromDetectives(state);
		return state.getAvailableMoves().stream()
				.filter(move -> move.visit(new Move.FunctionalVisitor<>(single -> true, doubleMove -> false)))
				.max((a, b) -> Integer.compare(
						distances.getOrDefault(lastDestination(a), 0),
						distances.getOrDefault(lastDestination(b), 0)))
				.orElseThrow(() -> new IllegalStateException("MrX is stuck at " + mrXLocation));
	}

	/** @return a detective move that pointedly does not capture MrX */
	private static Move shadow(GameState state, int mrXLocation) {
		return state.getAvailableMoves().stream()
				.filter(move -> lastDestination(move) != mrXLocation)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"Every detective move captures MrX, cannot build the scenario"));
	}

	private static int lastDestination(Move move) {
		return move.visit(new Move.FunctionalVisitor<>(
				single -> single.destination,
				doubleMove -> doubleMove.destination2));
	}

	/** @return for each station, how many hops the nearest detective is away */
	private static Map<Integer, Integer> distancesFromDetectives(GameState state) {
		ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = state.getSetup().graph;
		Map<Integer, Integer> distances = new HashMap<>();
		Deque<Integer> queue = new ArrayDeque<>();
		for (Piece piece : state.getPlayers()) {
			if (piece.isDetective()) {
				Optional<Integer> at = state.getDetectiveLocation((Detective) piece);
				at.ifPresent(node -> {
					distances.put(node, 0);
					queue.add(node);
				});
			}
		}
		while (!queue.isEmpty()) {
			int node = queue.remove();
			int next = distances.get(node) + 1;
			for (int neighbour : graph.adjacentNodes(node)) {
				if (distances.putIfAbsent(neighbour, next) == null) {
					queue.add(neighbour);
				}
			}
		}
		return distances;
	}

	private static void verifyPlayable(GameState state) {
		if (!state.getWinner().isEmpty()) {
			throw new IllegalStateException("Scenario is already over, winner: " + state.getWinner());
		}
		if (state.getAvailableMoves().isEmpty()) {
			throw new IllegalStateException("Scenario has no available moves");
		}
	}
}
