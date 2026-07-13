package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
 * Tests for {@link MoveGates}, the hard filters that replaced the evaluator's flat
 * ticket penalties.
 */
public class MoveGatesTest {

	private static ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph;
	private static GameSetup setup;
	private static Distances distances;

	@BeforeClass
	public static void loadMap() throws IOException {
		graph = ScotlandYard.standardGraph();
		setup = new GameSetup(graph, ScotlandYard.STANDARD24ROUNDS);
		distances = new Distances(graph);
	}

	private static Player mrX(int location) {
		return new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), location);
	}

	private static Player detective(Piece.Detective piece, int location) {
		return new Player(piece, ScotlandYard.defaultDetectiveTickets(), location);
	}

	private static GameState boardWith(int mrXLocation, List<Player> detectives) {
		return new MyGameStateFactory().build(setup, mrX(mrXLocation),
				ImmutableList.copyOf(detectives));
	}

	// === secretAllowed =========================================================

	@Test
	public void secretIsRejectedOnTheFirstTwoRounds() {
		final Board board = boardWith(106, ImmutableList.of(detective(Piece.Detective.RED, 91)));
		// Zero-based log indices 0 and 1 are the game's rounds 1 and 2: the detectives
		// have no reveal to work from, so a secret conceals nothing they had inferred.
		assertThat(MoveGates.secretAllowed(board, 0)).isFalse();
		assertThat(MoveGates.secretAllowed(board, 1)).isFalse();
	}

	@Test
	public void secretIsRejectedOnEveryRevealRoundAndAllowedOnTheHiddenOnesAfterRoundTwo() {
		final Board board = boardWith(106, ImmutableList.of(detective(Piece.Detective.RED, 91)));
		final ImmutableList<Boolean> rounds = setup.rounds;

		boolean sawAReveal = false;
		boolean sawAHiddenRound = false;
		for (int round = 2; round < rounds.size(); round++) {
			if (Boolean.TRUE.equals(rounds.get(round))) {
				sawAReveal = true;
				assertThat(MoveGates.secretAllowed(board, round))
						.as("reveal round %d", round).isFalse();
			} else {
				sawAHiddenRound = true;
				assertThat(MoveGates.secretAllowed(board, round))
						.as("hidden round %d", round).isTrue();
			}
		}
		// The schedule is read from the setup, never hardcoded; assert we actually
		// exercised both kinds of round rather than vacuously passing.
		assertThat(sawAReveal).isTrue();
		assertThat(sawAHiddenRound).isTrue();
	}

	@Test
	public void theRevealScheduleIsReadFromTheSetupAndNotHardcoded() {
		// A non-standard schedule: the reveal is on round 5 (index 4) and nowhere else.
		final ImmutableList<Boolean> odd = ImmutableList.of(
				false, false, false, false, true, false);
		final GameSetup oddSetup = new GameSetup(graph, odd);
		final Board board = new MyGameStateFactory().build(oddSetup, mrX(106),
				ImmutableList.of(detective(Piece.Detective.RED, 91)));

		assertThat(MoveGates.secretAllowed(board, 3)).isTrue();  // hidden, and past round 2
		assertThat(MoveGates.secretAllowed(board, 4)).isFalse(); // the reveal
		assertThat(MoveGates.secretAllowed(board, 5)).isTrue();
	}

	// === secretIsInformative ===================================================

	@Test
	public void secretIsUninformativeWhereEveryEdgeIsTheSameTransport() {
		final Board board = boardWith(106, ImmutableList.of(detective(Piece.Detective.RED, 91)));

		// A station all of whose edges are taxi edges: a secret out of it reaches exactly
		// the stations a taxi reaches, so it hides nothing at all.
		int taxiOnly = -1;
		int destination = -1;
		for (int node : graph.nodes()) {
			boolean allTaxi = true;
			for (int neighbour : graph.adjacentNodes(node)) {
				for (Transport transport : graph.edgeValueOrDefault(node, neighbour, ImmutableSet.of())) {
					if (transport != Transport.TAXI) allTaxi = false;
				}
			}
			if (allTaxi && !graph.adjacentNodes(node).isEmpty()) {
				taxiOnly = node;
				destination = graph.adjacentNodes(node).iterator().next();
				break;
			}
		}
		assertThat(taxiOnly).as("the map has taxi-only stations").isNotEqualTo(-1);

		assertThat(MoveGates.secretIsInformative(board, taxiOnly, destination)).isFalse();
	}

	@Test
	public void secretIsInformativeWhereTheStationIsMultiModal() {
		final Board board = boardWith(106, ImmutableList.of(detective(Piece.Detective.RED, 91)));

		// A station with both taxi and bus edges: a logged taxi narrows the detectives'
		// belief to the taxi neighbours, a logged secret does not narrow it at all.
		int multiModal = -1;
		int viaTaxi = -1;
		for (int node : graph.nodes()) {
			boolean taxi = false;
			boolean other = false;
			int taxiNeighbour = -1;
			for (int neighbour : graph.adjacentNodes(node)) {
				for (Transport transport : graph.edgeValueOrDefault(node, neighbour, ImmutableSet.of())) {
					if (transport == Transport.TAXI) {
						taxi = true;
						taxiNeighbour = neighbour;
					} else {
						other = true;
					}
				}
			}
			if (taxi && other) {
				multiModal = node;
				viaTaxi = taxiNeighbour;
				break;
			}
		}
		assertThat(multiModal).isNotEqualTo(-1);

		assertThat(MoveGates.secretIsInformative(board, multiModal, viaTaxi)).isTrue();
	}

	// === doubleAllowed =========================================================

	@Test
	public void doubleIsRejectedWhenTheDetectivesAreFarAndAllowedWhenTheyAreClose() {
		// Far: detectives parked in a corner of the map, Mr X in another.
		final int mrXLocation = 106;
		final List<Player> far = new ArrayList<>();
		final List<Player> near = new ArrayList<>();
		final Piece.Detective[] pieces = {
				Piece.Detective.RED, Piece.Detective.GREEN, Piece.Detective.BLUE };

		// Pick three stations at hop distance > 6 for the far board, and three neighbours
		// of Mr X's neighbourhood for the near board.
		final List<Integer> distant = new ArrayList<>();
		for (int node : graph.nodes()) {
			if (distances.hops(node, mrXLocation) >= 7 && distant.size() < 3) distant.add(node);
		}
		assertThat(distant).hasSize(3);

		final List<Integer> close = new ArrayList<>();
		for (int node : graph.nodes()) {
			final int hops = distances.hops(node, mrXLocation);
			if (hops >= 1 && hops <= 2 && close.size() < 3) close.add(node);
		}
		assertThat(close).hasSize(3);

		for (int i = 0; i < 3; i++) {
			far.add(detective(pieces[i], distant.get(i)));
			near.add(detective(pieces[i], close.get(i)));
		}

		assertThat(MoveGates.doubleAllowed(boardWith(mrXLocation, far), mrXLocation, distances))
				.as("detectives are far: a double is a flourish, not an escape")
				.isFalse();
		assertThat(MoveGates.doubleAllowed(boardWith(mrXLocation, near), mrXLocation, distances))
				.as("detectives are on top of him: a double is an escape")
				.isTrue();
	}

	// === filter ================================================================

	@Test
	public void theGatesNeverProduceAnEmptyMoveList() {
		// Over every Mr X start, every round, and both a crowded and an empty board: the
		// filtered set is always non-empty and always a subset of the legal moves.
		for (int start : ScotlandYard.MRX_LOCATIONS) {
			final GameState board = boardWith(start, ImmutableList.of(
					detective(Piece.Detective.RED, 91),
					detective(Piece.Detective.GREEN, 103),
					detective(Piece.Detective.BLUE, 112)));
			final ImmutableSet<Move> legal = board.getAvailableMoves();
			for (int round = 0; round < setup.rounds.size(); round++) {
				final ImmutableList<Move> gated =
						MoveGates.filter(board, start, round, legal, distances);
				assertThat(gated).as("start %d round %d", start, round).isNotEmpty();
				assertThat(legal).containsAll(gated);
			}
		}
	}

	@Test
	public void theGatesFallBackToTheUnfilteredSetWhenTheyRejectEverything() {
		// A Mr X with nothing but secret tickets, on round 1, where every secret is
		// gated: the filter must hand back the legal moves rather than nothing.
		final Map<Ticket, Integer> onlySecrets = new EnumMap<>(Ticket.class);
		onlySecrets.put(Ticket.TAXI, 0);
		onlySecrets.put(Ticket.BUS, 0);
		onlySecrets.put(Ticket.UNDERGROUND, 0);
		onlySecrets.put(Ticket.SECRET, 5);
		onlySecrets.put(Ticket.DOUBLE, 0);

		final Player mrX = new Player(Piece.MrX.MRX, ImmutableMap.copyOf(onlySecrets), 106);
		final GameState board = new MyGameStateFactory().build(setup, mrX,
				ImmutableList.of(detective(Piece.Detective.RED, 91)));

		final ImmutableSet<Move> legal = board.getAvailableMoves();
		assertThat(legal).isNotEmpty();

		final ImmutableList<Move> gated = MoveGates.filter(board, 106, 0, legal, distances);
		// Round 0 bans every secret, and every move he has is a secret.
		assertThat(gated).containsExactlyInAnyOrderElementsOf(legal);
	}

	@Test
	public void theGatesRemoveSecretsOnRoundOneWhenOrdinaryMovesRemain() {
		final GameState board = boardWith(106, ImmutableList.of(
				detective(Piece.Detective.RED, 91),
				detective(Piece.Detective.GREEN, 103)));
		final ImmutableSet<Move> legal = board.getAvailableMoves();
		assertThat(legal).anyMatch(MoveGatesTest::usesSecret);

		final ImmutableList<Move> gated = MoveGates.filter(board, 106, 0, legal, distances);
		assertThat(gated).isNotEmpty();
		assertThat(gated).noneMatch(MoveGatesTest::usesSecret);
	}

	/**
	 * End to end: the gates are only worth anything if the AI that owns them actually
	 * branches through them. Mr X must not spend a secret on round one, whatever the
	 * search thinks of the position.
	 */
	@Test
	public void mrXNeverPlaysASecretOnTheFirstRound() {
		for (int start : ScotlandYard.MRX_LOCATIONS) {
			final GameState board = boardWith(start, ImmutableList.of(
					detective(Piece.Detective.RED, 91),
					detective(Piece.Detective.GREEN, 103),
					detective(Piece.Detective.BLUE, 112),
					detective(Piece.Detective.WHITE, 117),
					detective(Piece.Detective.YELLOW, 123)));
			final Move chosen = new MyAi().pickMove(board,
					new io.atlassian.fugue.Pair<>(200L, java.util.concurrent.TimeUnit.MILLISECONDS));
			assertThat(usesSecret(chosen)).as("secret on round 1 from %d: %s", start, chosen)
					.isFalse();
		}
	}

	private static boolean usesSecret(Move move) {
		for (Ticket ticket : move.tickets()) {
			if (ticket == Ticket.SECRET) return true;
		}
		return false;
	}
}
