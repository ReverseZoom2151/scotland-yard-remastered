package uk.ac.bris.cs.scotlandyard.ui.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * The ferry is Mr X's alone.
 *
 * <p>
 * A ferry edge demands a SECRET ticket — {@code Transport.FERRY.requiredTicket()}
 * is {@code Ticket.SECRET} — and no detective is ever dealt one:
 * {@code MyGameStateFactory.build()} throws if a detective holds a secret. So a
 * ferry-only edge is a wall to a detective and a shortcut to Mr X, and one hop
 * count cannot serve both. These tests pin that down: that
 * {@link Distances#detectiveHops} never flatters a detective, that it really does
 * differ from {@link Distances#hops} on this map, and that a detective cannot buy
 * its way onto a boat.
 */
public class DistancesFerryTest {

	private static GameSetup standardSetup() throws IOException {
		return new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
	}

	/** A board, only so that a player's ticket purse can be looked up. */
	private static Board freshGame(GameSetup setup) {
		Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), 106);
		Player red = new Player(Piece.Detective.RED, ScotlandYard.defaultDetectiveTickets(), 91);
		Player green = new Player(Piece.Detective.GREEN, ScotlandYard.defaultDetectiveTickets(), 94);
		return new MyGameStateFactory().build(setup, mrX, ImmutableList.of(red, green));
	}

	/** Edges whose only transport is the ferry: the ones a detective can never cross. */
	private static List<EndpointPair<Integer>> ferryOnlyEdges(
			ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph) {
		List<EndpointPair<Integer>> found = new ArrayList<>();
		for (EndpointPair<Integer> edge : graph.edges()) {
			ImmutableSet<Transport> transports = graph
					.edgeValueOrDefault(edge.nodeU(), edge.nodeV(), ImmutableSet.of());
			if (!transports.isEmpty() && transports.stream().allMatch(t -> t == Transport.FERRY)) {
				found.add(edge);
			}
		}
		return found;
	}

	// ------------------------------------------------------------ the premise

	/** The whole item rests on this: the ferry costs a SECRET, and detectives have none. */
	@Test
	public void aFerryCostsASecretTicketAndNoDetectiveHoldsOne() {
		assertThat(Transport.FERRY.requiredTicket()).isEqualTo(Ticket.SECRET);
		assertThat(ScotlandYard.defaultDetectiveTickets().get(Ticket.SECRET))
				.as("a detective is never dealt a secret ticket").isZero();
		assertThat(ScotlandYard.defaultMrXTickets().get(Ticket.SECRET))
				.as("Mr X is").isPositive();
	}

	// --------------------------------------------------- the two metrics differ

	/**
	 * {@code detectiveHops} walks a subgraph of the map, so it can only ever be
	 * longer. Counts the pairs where it actually is — if that count were zero the
	 * ferries would be redundant and there would be no reason for two metrics.
	 */
	@Test
	public void detectiveHopsIsNeverShorterThanHopsAndSometimesLonger() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);
		List<Integer> nodes = new ArrayList<>(setup.graph.nodes());

		int pairs = 0;
		int differing = 0;
		int worst = 0;
		for (int i = 0; i < nodes.size(); i++) {
			for (int j = i + 1; j < nodes.size(); j++) {
				int a = nodes.get(i);
				int b = nodes.get(j);
				int mrX = distances.hops(a, b);
				int detective = distances.detectiveHops(a, b);
				pairs++;

				assertThat(detective).as("detectiveHops(%d,%d) >= hops(%d,%d)", a, b, a, b)
						.isGreaterThanOrEqualTo(mrX);
				// Symmetric, like the plain metric.
				assertThat(distances.detectiveHops(b, a)).as("detectiveHops(%d,%d) is symmetric", a, b)
						.isEqualTo(detective);
				if (detective != mrX) {
					differing++;
					worst = Math.max(worst, detective - mrX);
				}
			}
		}

		System.out.printf("ferry audit: %d of %d node pairs differ between hops and detectiveHops "
				+ "(worst case +%d hops for the detective)%n", differing, pairs, worst);

		assertThat(pairs).as("199 stations, so 199*198/2 unordered pairs").isEqualTo(199 * 198 / 2);
		assertThat(differing)
				.as("if this is 0 the ferries are redundant and one metric would do")
				.isPositive();

		// Both metrics reach everywhere: cutting the ferries does not strand a detective.
		for (int a : nodes) {
			assertThat(distances.detectiveHops(a, nodes.get(0)))
					.as("station %d is still reachable without ferries", a)
					.isLessThan(Integer.MAX_VALUE);
			assertThat(distances.detectiveHops(a, a)).as("detectiveHops(%d,%d)", a, a).isZero();
		}
	}

	/** A concrete pair, named, so a regression cannot quietly erase the distinction. */
	@Test
	public void thereIsAPairMrXCanReachFasterThanADetective() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);

		List<EndpointPair<Integer>> ferries = ferryOnlyEdges(setup.graph);
		assertThat(ferries).as("the standard map has ferry-only edges").isNotEmpty();

		// The two ends of a ferry are one hop apart for Mr X, by definition, and further
		// for anyone who has to go round by land.
		EndpointPair<Integer> ferry = ferries.get(0);
		int a = ferry.nodeU();
		int b = ferry.nodeV();
		System.out.printf("ferry-only edges: %s%n", ferries);

		assertThat(distances.hops(a, b)).as("Mr X boards the ferry %d-%d", a, b).isEqualTo(1);
		assertThat(distances.detectiveHops(a, b)).as("a detective must walk round from %d to %d", a, b)
				.isGreaterThan(1);
	}

	// ---------------------------------------------------- the ticket-aware path

	/**
	 * {@code ticketAwareDistance} needed no fixing: it already asks whether the player
	 * can pay for the edge, the ferry's price is a SECRET ticket, and a detective has
	 * none. This test is the proof of that claim, not of a change.
	 */
	@Test
	public void aDetectiveCannotCrossAFerryEdgeButMrXCan() throws IOException {
		GameSetup setup = standardSetup();
		Distances distances = new Distances(setup.graph);
		Board board = freshGame(setup);

		List<EndpointPair<Integer>> ferries = ferryOnlyEdges(setup.graph);
		assertThat(ferries).isNotEmpty();

		for (EndpointPair<Integer> ferry : ferries) {
			int a = ferry.nodeU();
			int b = ferry.nodeV();

			assertThat(Distances.canAfford(board, Piece.Detective.RED, Transport.FERRY))
					.as("a detective cannot pay for the ferry %d-%d", a, b).isFalse();
			assertThat(Distances.canAfford(board, Piece.MrX.MRX, Transport.FERRY))
					.as("Mr X can").isTrue();

			// One hop for Mr X, who holds secrets; the long way round for the detective,
			// whose only route out of a ferry-only edge is by land.
			assertThat(distances.ticketAwareDistance(board, Piece.MrX.MRX, a, b))
					.as("Mr X takes the ferry %d-%d in one", a, b).isEqualTo(1);
			assertThat(distances.ticketAwareDistance(board, Piece.Detective.RED, a, b))
					.as("a detective may not cross the ferry %d-%d", a, b)
					.isGreaterThan(1)
					.isEqualTo(distances.detectiveHops(a, b));
		}
	}
}
