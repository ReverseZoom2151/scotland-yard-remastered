package uk.ac.bris.cs.scotlandyard.auxiliary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.io.Resources;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;
import uk.ac.bris.cs.scotlandyard.ui.ai.RandomAi;

/**
 * The 20-station map in {@code tiny-graph.txt} is a piece of test apparatus, and
 * apparatus has to be checked before it is trusted.
 *
 * <p>
 * Why it exists: the real board has 199 stations, so a ten-thousand-game sweep
 * takes minutes rather than seconds and an endgame test can only ever be
 * approximate — you cannot enumerate what a detective could have done instead. On
 * twenty stations a sweep is instant and an endgame is exhaustible.
 *
 * <p>
 * A map that does not <i>play</i>, though, measures nothing: if Mr X has no escape
 * routes he loses every game regardless of how he thinks, and if the detectives
 * cannot corner him he never loses one. So the last test here plays a whole game on
 * it, which is the property the other tests are only evidence for.
 */
public class TinyMapTest {

	private static final int NODES = 20;
	private static final int EDGES = 43;

	private static ImmutableValueGraph<Integer, ImmutableSet<Transport>> tinyGraph()
			throws IOException {
		return ScotlandYard.readGraph(Resources.toString(
				Resources.getResource("tiny-graph.txt"), StandardCharsets.UTF_8));
	}

	@Test
	public void parsesToTwentyStationsNumberedOneToTwenty() throws IOException {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = tinyGraph();

		assertThat(graph.nodes()).hasSize(NODES);
		for (int station = 1; station <= NODES; station++) {
			assertThat(graph.nodes())
					.withFailMessage("the tiny map is missing station %d", station)
					.contains(station);
		}
		assertThat(graph.edges()).hasSize(EDGES);
	}

	/** A disconnected station is a station no game can ever reach. */
	@Test
	public void isConnected() throws IOException {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = tinyGraph();

		final Set<Integer> seen = new HashSet<>();
		final Deque<Integer> frontier = new ArrayDeque<>();
		frontier.add(1);
		seen.add(1);
		while (!frontier.isEmpty()) {
			for (int next : graph.adjacentNodes(frontier.remove())) {
				if (seen.add(next)) frontier.add(next);
			}
		}
		assertThat(seen)
				.withFailMessage("the tiny map is not connected; unreachable from 1: %s",
						difference(graph.nodes(), seen))
				.hasSize(NODES);
	}

	/**
	 * Every transport has to appear, or the map cannot exercise the ticket logic it
	 * exists to exercise — and the ferry in particular, because a ferry edge is the
	 * only edge a SECRET ticket can cross and nothing else can, which is where half of
	 * Mr X's inference-dodging lives.
	 */
	@Test
	public void hasEveryTransportIncludingAFerry() throws IOException {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = tinyGraph();

		final Set<Transport> transports = new HashSet<>();
		int ferries = 0;
		for (var edge : graph.edges()) {
			final ImmutableSet<Transport> on =
					graph.edgeValueOrDefault(edge, ImmutableSet.of());
			transports.addAll(on);
			if (on.contains(Transport.FERRY)) ferries++;
		}

		assertThat(transports).contains(Transport.TAXI, Transport.BUS, Transport.UNDERGROUND);
		assertThat(ferries)
				.withFailMessage("the tiny map has no ferry edge, so SECRET tickets are"
						+ " indistinguishable from ordinary ones on it")
				.isPositive();
	}

	/** Both sides need somewhere to go on their first move, or the map is a corridor. */
	@Test
	public void everyStationHasAWayOut() throws IOException {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = tinyGraph();

		for (int station : graph.nodes()) {
			assertThat(graph.adjacentNodes(station).size())
					.withFailMessage("station %d is a dead end", station)
					.isGreaterThanOrEqualTo(2);
		}
	}

	/**
	 * The point of the whole file: a game really can be played to a finish on this map.
	 * Two RandomAis, a short rounds list, and a winner at the end of it.
	 */
	@Test(timeout = 60_000)
	public void aWholeGameCanBePlayedOnIt() throws IOException {
		final GameSetup setup = new GameSetup(tinyGraph(),
				// Six rounds, revealing on the third — the standard shape, scaled down.
				ImmutableList.of(false, false, true, false, false, true));

		final Player mrX = new Player(Piece.MrX.MRX, ScotlandYard.defaultMrXTickets(), 5);
		final List<Player> detectives = ImmutableList.of(
				new Player(Piece.Detective.RED, ScotlandYard.defaultDetectiveTickets(), 12),
				new Player(Piece.Detective.BLUE, ScotlandYard.defaultDetectiveTickets(), 18));

		Board.GameState state = new MyGameStateFactory()
				.build(setup, mrX, ImmutableList.copyOf(detectives));

		final RandomAi mrXBrain = new RandomAi(11);
		final RandomAi detectiveBrain = new RandomAi(13);
		final Pair<Long, TimeUnit> budget = new Pair<>(50L, TimeUnit.MILLISECONDS);

		final List<Move> played = new ArrayList<>();
		while (state.getWinner().isEmpty()) {
			assertThat(played.size())
					.withFailMessage("the tiny map produced a game that never ends")
					.isLessThan(100);
			final boolean mrXToMove =
					state.getAvailableMoves().iterator().next().commencedBy().isMrX();
			final Move move = (mrXToMove ? mrXBrain : detectiveBrain).pickMove(state, budget);
			assertThat(state.getAvailableMoves()).contains(move);
			played.add(move);
			state = state.advance(move);
		}

		assertThat(state.getWinner()).isNotEmpty();
		assertThat(played).isNotEmpty();
		assertThat(state.getMrXTravelLog().size()).isLessThanOrEqualTo(setup.rounds.size());
	}

	private static Set<Integer> difference(Set<Integer> all, Set<Integer> seen) {
		final Set<Integer> missing = new HashSet<>(all);
		missing.removeAll(seen);
		return missing;
	}
}
