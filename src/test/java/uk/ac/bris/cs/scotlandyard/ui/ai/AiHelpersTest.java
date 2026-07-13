package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Unit tests for the AI helper classes {@link Distances}, {@link BoardStates}
 * and {@link Evaluator}.
 */
public class AiHelpersTest {

	private static ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph;
	private static GameSetup setup;
	private static Distances distances;
	private static ImmutableList<Integer> nodes;

	private static final int MRX_START = 106;
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

	// --- board building helpers ------------------------------------------------

	private static ImmutableMap<Ticket, Integer> tickets(int taxi, int bus, int underground, int secret, int dbl) {
		final Map<Ticket, Integer> map = new EnumMap<>(Ticket.class);
		map.put(Ticket.TAXI, taxi);
		map.put(Ticket.BUS, bus);
		map.put(Ticket.UNDERGROUND, underground);
		map.put(Ticket.SECRET, secret);
		map.put(Ticket.DOUBLE, dbl);
		return ImmutableMap.copyOf(map);
	}

	private static Player mrX(int location) {
		return new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), location);
	}

	private static Player detective(Piece.Detective piece, int location) {
		return new Player(piece, ScotlandYard.defaultDetectiveTickets(), location);
	}

	/** A standard three-detective board with Mr X at {@link #MRX_START}. */
	private static GameState freshBoard() {
		return new MyGameStateFactory().build(setup, mrX(MRX_START), ImmutableList.of(
				detective(Piece.Detective.RED, RED_START),
				detective(Piece.Detective.GREEN, GREEN_START),
				detective(Piece.Detective.BLUE, BLUE_START)));
	}

	private static GameState boardWith(int mrXLocation, ImmutableList<Player> detectives) {
		return new MyGameStateFactory().build(setup, mrX(mrXLocation), detectives);
	}

	private static ImmutableSet<Transport> transportsOn(int a, int b) {
		return graph.edgeValueOrDefault(a, b, ImmutableSet.of());
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

	// === Distances =============================================================

	@Test
	public void hopsToSelfIsZero() {
		for (int node : nodes) {
			assertThat(distances.hops(node, node)).as("hops(%d,%d)", node, node).isZero();
		}
	}

	@Test
	public void hopsIsSymmetric() {
		for (int a : nodes) {
			for (int b : nodes) {
				assertThat(distances.hops(a, b)).as("hops(%d,%d) vs hops(%d,%d)", a, b, b, a)
						.isEqualTo(distances.hops(b, a));
			}
		}
	}

	@Test
	public void hopsIsOneExactlyForAdjacentNodes() {
		for (int a : nodes) {
			for (int b : nodes) {
				if (a == b) continue;
				final boolean adjacent = graph.adjacentNodes(a).contains(b);
				if (adjacent) {
					assertThat(distances.hops(a, b)).as("adjacent %d-%d", a, b).isEqualTo(1);
				} else {
					assertThat(distances.hops(a, b)).as("non-adjacent %d-%d", a, b).isGreaterThanOrEqualTo(2);
				}
			}
		}
	}

	@Test
	public void hopsObeysTriangleInequality() {
		final int size = nodes.size();
		for (int i = 0; i < size; i += 3) {
			for (int j = 0; j < size; j++) {
				for (int k = 0; k < size; k += 7) { // sampled triples, ~190k of them
					final int a = nodes.get(i);
					final int b = nodes.get(k);
					final int c = nodes.get(j);
					final long viaB = (long) distances.hops(a, b) + distances.hops(b, c);
					assertThat((long) distances.hops(a, c)).as("hops(%d,%d) <= hops(%d,%d)+hops(%d,%d)", a, c, a, b, b,
							c).isLessThanOrEqualTo(viaB);
				}
			}
		}
	}

	@Test
	public void everyNodeIsReachableFromEveryOther() {
		assertThat(nodes).hasSize(199);
		for (int a : nodes) {
			for (int b : nodes) {
				assertThat(distances.hops(a, b)).as("hops(%d,%d) is finite", a, b)
						.isNotEqualTo(Integer.MAX_VALUE);
			}
		}
	}

	@Test
	public void hopsOnUnknownNodeReturnsMaxValue() {
		assertThat(distances.hops(9999, 1)).isEqualTo(Integer.MAX_VALUE);
		assertThat(distances.hops(1, 9999)).isEqualTo(Integer.MAX_VALUE);
		assertThat(distances.hops(-5, 9999)).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	public void ticketAwareDistanceIsNeverLessThanHops() {
		final Board board = freshBoard();
		final List<Piece> pieces = ImmutableList.of(Piece.MrX.MRX, Piece.Detective.RED, Piece.Detective.GREEN);
		for (Piece piece : pieces) {
			for (int i = 0; i < nodes.size(); i++) {
				for (int j = 0; j < nodes.size(); j += 11) {
					final int a = nodes.get(i);
					final int b = nodes.get(j);
					final int ticketAware = distances.ticketAwareDistance(board, piece, a, b);
					assertThat(ticketAware).as("ticketAware(%s,%d,%d) >= hops", piece, a, b)
							.isGreaterThanOrEqualTo(distances.hops(a, b));
				}
			}
		}
	}

	@Test
	public void detectiveWithoutUndergroundTicketsCannotUseTheTube() {
		// A detective holding only taxi tickets.
		final Player taxiOnly = new Player(Piece.Detective.RED, tickets(11, 0, 0, 0, 0), RED_START);
		final Board board = boardWith(MRX_START, ImmutableList.of(taxiOnly));

		// Find an edge served ONLY by the underground: one hop on the map, but a
		// taxi-only detective cannot cross it.
		int a = -1;
		int b = -1;
		for (int node : nodes) {
			for (int neighbour : graph.adjacentNodes(node)) {
				final ImmutableSet<Transport> transports = transportsOn(node, neighbour);
				if (transports.equals(ImmutableSet.of(Transport.UNDERGROUND))) {
					a = node;
					b = neighbour;
					break;
				}
			}
			if (a != -1) break;
		}
		assertThat(a).as("the map has a tube-only edge").isNotEqualTo(-1);

		assertThat(distances.hops(a, b)).isEqualTo(1);
		assertThat(distances.ticketAwareDistance(board, Piece.Detective.RED, a, b))
				.as("taxi-only detective cannot take the single tube edge %d-%d", a, b)
				.isGreaterThan(1);
	}

	@Test
	public void canAffordRespectsTicketCounts() {
		final Player taxiOnly = new Player(Piece.Detective.RED, tickets(11, 0, 0, 0, 0), RED_START);
		final Board board = boardWith(MRX_START, ImmutableList.of(taxiOnly));

		assertThat(Distances.canAfford(board, Piece.Detective.RED, Transport.TAXI)).isTrue();
		assertThat(Distances.canAfford(board, Piece.Detective.RED, Transport.BUS)).isFalse();
		assertThat(Distances.canAfford(board, Piece.Detective.RED, Transport.UNDERGROUND)).isFalse();
		// FERRY requires a SECRET ticket, which detectives never hold.
		assertThat(Distances.canAfford(board, Piece.Detective.RED, Transport.FERRY)).isFalse();
	}

	@Test
	public void secretTicketsAffordEveryTransport() {
		// Mr X holding nothing but secret tickets.
		final Player secretOnly = new Player(Piece.MrX.MRX, tickets(0, 0, 0, 5, 0), MRX_START);
		final Board board = new MyGameStateFactory().build(setup, secretOnly,
				ImmutableList.of(detective(Piece.Detective.RED, RED_START)));

		for (Transport transport : Transport.values()) {
			assertThat(Distances.canAfford(board, Piece.MrX.MRX, transport))
					.as("secret affords %s", transport).isTrue();
		}
		assertThat(Transport.FERRY.requiredTicket()).isEqualTo(Ticket.SECRET);
	}

	// === BoardStates ===========================================================

	@Test
	public void mrXLocationOfReadsMrXsActualLocation() {
		assertThat(BoardStates.mrXLocationOf(freshBoard())).isEqualTo(MRX_START);
	}

	@Test
	public void mrXLocationOfThrowsOnADetectiveTurn() {
		final GameState afterMrX = advanceOnce(freshBoard());
		assertThat(afterMrX.getAvailableMoves().stream().allMatch(m -> m.commencedBy().isDetective())).isTrue();
		assertThatThrownBy(() -> BoardStates.mrXLocationOf(afterMrX))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static GameState advanceOnce(GameState state) {
		final Move move = state.getAvailableMoves().iterator().next();
		return state.advance(move);
	}

	@Test
	public void ticketsOfReportsTheRightCounts() {
		final Board board = freshBoard();
		assertThat(BoardStates.ticketsOf(board, Piece.MrX.MRX))
				.containsExactlyInAnyOrderEntriesOf(ScotlandYard.defaultMrXTickets());
		assertThat(BoardStates.ticketsOf(board, Piece.Detective.RED))
				.containsExactlyInAnyOrderEntriesOf(ScotlandYard.defaultDetectiveTickets());
	}

	@Test
	public void detectivesOfReturnsEveryDetectiveWithItsLocation() {
		final Board board = freshBoard();
		final ImmutableList<Player> detectives = BoardStates.detectivesOf(board);
		assertThat(detectives).hasSize(3);
		assertThat(detectives).extracting(Player::piece)
				.containsExactlyInAnyOrder(Piece.Detective.RED, Piece.Detective.GREEN, Piece.Detective.BLUE);

		for (Player player : detectives) {
			final int expected = switch ((Piece.Detective) player.piece()) {
				case RED -> RED_START;
				case GREEN -> GREEN_START;
				case BLUE -> BLUE_START;
				default -> -1;
			};
			assertThat(player.location()).as("%s location", player.piece()).isEqualTo(expected);
			assertThat(player.tickets()).containsExactlyInAnyOrderEntriesOf(ScotlandYard.defaultDetectiveTickets());
		}
	}

	@Test
	public void rebuildRoundTripsTheAvailableMoves() {
		final GameState original = freshBoard();
		final GameState rebuilt = BoardStates.rebuild(original, BoardStates.mrXLocationOf(original));
		assertThat(rebuilt.getAvailableMoves())
				.containsExactlyInAnyOrderElementsOf(original.getAvailableMoves());
		assertThat(rebuilt.getMrXTravelLog()).isEqualTo(original.getMrXTravelLog());
		assertThat(rebuilt.getPlayers()).isEqualTo(original.getPlayers());
	}

	@Test
	public void rebuildAtAHypothesisedLocationIsStillAdvanceable() {
		final GameState original = freshBoard();
		final int elsewhere = 1;
		assertThat(elsewhere).isNotEqualTo(MRX_START);

		final GameState hypothesis = BoardStates.rebuild(original, elsewhere);
		assertThat(BoardStates.mrXLocationOf(hypothesis)).isEqualTo(elsewhere);
		assertThat(hypothesis.getAvailableMoves()).isNotEmpty();

		final GameState next = advanceOnce(hypothesis);
		assertThat(next.getAvailableMoves()).isNotEmpty();
		assertThat(next.getMrXTravelLog()).isNotEmpty(); // a single or a double move
	}

	// === Evaluator =============================================================

	@Test
	public void detectiveStandingOnMrXScoresCaptured() {
		final Evaluator evaluator = new Evaluator(distances);
		assertThat(evaluator.score(freshBoard(), RED_START)).isEqualTo(Evaluator.MRX_CAPTURED);
	}

	@Test
	public void aStationADetectiveCanReachScoresBelowASafeOne() {
		final Evaluator evaluator = new Evaluator(distances);
		final Board board = freshBoard();

		// Dangerous: adjacent to RED by an edge RED can pay for.
		int dangerous = -1;
		for (int neighbour : graph.adjacentNodes(RED_START)) {
			for (Transport transport : transportsOn(RED_START, neighbour)) {
				if (transport == Transport.TAXI) {
					dangerous = neighbour;
					break;
				}
			}
			if (dangerous != -1) break;
		}
		assertThat(dangerous).as("RED has a taxi neighbour").isNotEqualTo(-1);

		final int safe = furthestFrom(ImmutableList.of(RED_START, GREEN_START, BLUE_START));
		assertThat(evaluator.score(board, dangerous)).isLessThan(evaluator.score(board, safe));
	}

	@Test
	public void mrXFurtherFromDetectivesScoresHigher() {
		final Evaluator evaluator = new Evaluator(distances);
		final ImmutableList<Integer> detectiveLocations = ImmutableList.of(RED_START, GREEN_START, BLUE_START);
		final ImmutableList<Player> detectives = ImmutableList.of(
				detective(Piece.Detective.RED, RED_START),
				detective(Piece.Detective.GREEN, GREEN_START),
				detective(Piece.Detective.BLUE, BLUE_START));

		final int far = furthestFrom(detectiveLocations);

		// A near station: two hops from RED, so not an immediate capture, but close.
		int near = -1;
		for (int node : nodes) {
			if (detectiveLocations.contains(node)) continue;
			if (distances.hops(RED_START, node) == 2) {
				near = node;
				break;
			}
		}
		assertThat(near).isNotEqualTo(-1);
		assertThat(distances.hops(RED_START, far)).isGreaterThan(distances.hops(RED_START, near));

		// Two boards differing only in where Mr X stands.
		final Board nearBoard = boardWith(near, detectives);
		final Board farBoard = boardWith(far, detectives);

		assertThat(evaluator.score(farBoard, far))
				.as("Mr X at %d (far) beats Mr X at %d (near)", far, near)
				.isGreaterThan(evaluator.score(nearBoard, near));
	}

	@Test
	public void scoreStaysInsideItsBandAndNeverOverflows() {
		final Evaluator evaluator = new Evaluator(distances);
		final Board board = freshBoard();
		final List<Integer> occupied = ImmutableList.of(RED_START, GREEN_START, BLUE_START);

		final List<Integer> nonTerminal = new ArrayList<>();
		for (int node : nodes) {
			final int score = evaluator.score(board, node);
			assertThat(score).as("score at %d within band", node)
					.isBetween(Evaluator.MRX_CAPTURED, Evaluator.MRX_ESCAPED);
			if (occupied.contains(node)) {
				assertThat(score).as("captured at %d", node).isEqualTo(Evaluator.MRX_CAPTURED);
			} else {
				nonTerminal.add(node);
				assertThat(score).as("ordinary position %d is not a sentinel", node)
						.isNotEqualTo(Evaluator.MRX_CAPTURED)
						.isNotEqualTo(Evaluator.MRX_ESCAPED);
			}
		}
		assertThat(nonTerminal).hasSize(nodes.size() - occupied.size());
	}

	@Test
	public void scoreWithNoDetectivesIsFiniteAndOrdinary() {
		final Evaluator evaluator = new Evaluator(distances);
		final Board board = boardWith(MRX_START, ImmutableList.of(detective(Piece.Detective.RED, RED_START)));
		final Optional<Integer> red = board.getDetectiveLocation(Piece.Detective.RED);
		assertThat(red).contains(RED_START);

		final int score = evaluator.score(board, MRX_START);
		assertThat(score).isBetween(Evaluator.MRX_CAPTURED, Evaluator.MRX_ESCAPED);
	}
}
