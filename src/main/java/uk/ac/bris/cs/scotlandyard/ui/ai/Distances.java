package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Shortest-path distances over the London map.
 *
 * <p>
 * Two notions of distance live here. The plain one ignores tickets and is the
 * hop count between two stations; it is precomputed for every pair once, since
 * the map never changes. The ticket-aware one accounts for what a given
 * detective can actually pay for — a detective out of underground tickets
 * cannot use the tube, so its real distance to Mr X is longer than the map
 * suggests.
 */
public final class Distances {

	/** The map this instance was built for. */
	private final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph;

	/** Node id -> dense index in {@link #nodes} / {@link #allPairs}. */
	private final Map<Integer, Integer> indexOf;

	/** Dense index -> node id. Node ids are not contiguous, hence the indirection. */
	private final int[] nodes;

	/** allPairs[i][j] = hop count between nodes[i] and nodes[j], or MAX_VALUE. */
	private final int[][] allPairs;

	/** Dense adjacency: neighbours[i] holds the indices adjacent to index i. */
	private final int[][] neighbours;

	/**
	 * Precomputes all-pairs hop distances for the given map. Call once, from
	 * {@code Ai#onStart()} or on first use; it is the same 199-node graph every game.
	 *
	 * @param graph the game map
	 */
	public Distances(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph) {
		this.graph = graph;

		final var nodeSet = graph.nodes();
		this.nodes = new int[nodeSet.size()];
		this.indexOf = new HashMap<>(nodeSet.size() * 2);
		int next = 0;
		for (int node : nodeSet) {
			this.nodes[next] = node;
			this.indexOf.put(node, next);
			next++;
		}

		final int n = this.nodes.length;
		this.neighbours = new int[n][];
		for (int i = 0; i < n; i++) {
			final var adjacent = graph.adjacentNodes(this.nodes[i]);
			final int[] row = new int[adjacent.size()];
			int k = 0;
			for (int neighbour : adjacent) {
				row[k++] = this.indexOf.get(neighbour);
			}
			this.neighbours[i] = row;
		}

		// 199 BFS runs, one per source; trivially cheap and done once.
		this.allPairs = new int[n][];
		for (int i = 0; i < n; i++) {
			this.allPairs[i] = bfs(i);
		}
	}

	/** Plain hop-count BFS from a dense source index over the whole graph. */
	private int[] bfs(int source) {
		final int[] dist = new int[this.nodes.length];
		Arrays.fill(dist, Integer.MAX_VALUE);
		dist[source] = 0;
		final Deque<Integer> queue = new ArrayDeque<>();
		queue.add(source);
		while (!queue.isEmpty()) {
			final int current = queue.remove();
			for (int neighbour : this.neighbours[current]) {
				if (dist[neighbour] == Integer.MAX_VALUE) {
					dist[neighbour] = dist[current] + 1;
					queue.add(neighbour);
				}
			}
		}
		return dist;
	}

	/**
	 * @return the fewest edges between {@code source} and {@code destination},
	 *         ignoring tickets, or {@link Integer#MAX_VALUE} if unreachable
	 */
	public int hops(int source, int destination) {
		final Integer from = this.indexOf.get(source);
		final Integer to = this.indexOf.get(destination);
		if (from == null || to == null) return Integer.MAX_VALUE;
		return this.allPairs[from][to];
	}

	/**
	 * The distance a player can actually travel, given the tickets it holds.
	 *
	 * <p>
	 * Edges the player cannot pay for are excluded. A secret ticket crosses any
	 * edge, so a player holding secrets is never worse off than the plain hop
	 * count. Tickets are treated as a budget over the whole path, not per-edge.
	 *
	 * <p>
	 * <b>Approximation — read this.</b> A faithful budget model makes a search
	 * state {@code (node, remaining tickets)}, which is exponential in the number
	 * of ticket types and far too slow to call from an evaluation function that
	 * runs thousands of times per move. What is implemented here instead is a
	 * BFS over plain nodes in which an edge is traversable when the player holds
	 * <i>at least one</i> ticket of the required type (or at least one SECRET,
	 * which crosses anything). So a detective with a single bus ticket is treated
	 * as able to walk a path with three bus edges on it. This is a
	 * <i>lower bound</i> on the true budgeted distance: it never overestimates,
	 * and it is exact whenever the returned path uses each ticket type no more
	 * often than the player holds it — which covers the short paths an
	 * evaluation function actually cares about. It correctly returns
	 * {@link Integer#MAX_VALUE} when a whole transport mode is unaffordable and
	 * the destination lies behind it.
	 *
	 * @param board       the board, for the map and the player's ticket counts
	 * @param piece       the player travelling
	 * @param source      where it starts
	 * @param destination where it is heading
	 * @return the fewest edges it can afford, or {@link Integer#MAX_VALUE} if it
	 *         cannot get there at all
	 */
	public int ticketAwareDistance(Board board, uk.ac.bris.cs.scotlandyard.model.Piece piece,
			int source, int destination) {
		if (source == destination) return 0;
		if (!this.indexOf.containsKey(source) || !this.indexOf.containsKey(destination)) {
			return Integer.MAX_VALUE;
		}

		// Which transports this player can pay for at all, resolved once up front.
		final Map<Transport, Boolean> affordable = new HashMap<>();
		for (Transport transport : Transport.values()) {
			affordable.put(transport, canAfford(board, piece, transport));
		}

		final int from = this.indexOf.get(source);
		final int to = this.indexOf.get(destination);

		final int[] dist = new int[this.nodes.length];
		Arrays.fill(dist, Integer.MAX_VALUE);
		dist[from] = 0;
		final Deque<Integer> queue = new ArrayDeque<>();
		queue.add(from);
		while (!queue.isEmpty()) {
			final int current = queue.remove();
			if (current == to) return dist[current];
			for (int neighbour : this.neighbours[current]) {
				if (dist[neighbour] != Integer.MAX_VALUE) continue;
				if (!usable(current, neighbour, affordable)) continue;
				dist[neighbour] = dist[current] + 1;
				queue.add(neighbour);
			}
		}
		return dist[to];
	}

	/** @return whether any transport on the edge between two dense indices is affordable. */
	private boolean usable(int from, int to, Map<Transport, Boolean> affordable) {
		final ImmutableSet<Transport> transports = this.graph
				.edgeValueOrDefault(this.nodes[from], this.nodes[to], ImmutableSet.of());
		for (Transport transport : transports) {
			if (Boolean.TRUE.equals(affordable.get(transport))) return true;
		}
		return false;
	}

	/**
	 * @return whether {@code piece} holds at least one ticket that crosses an edge
	 *         served by {@code transport}
	 */
	public static boolean canAfford(Board board, uk.ac.bris.cs.scotlandyard.model.Piece piece,
			Transport transport) {
		final var tickets = board.getPlayerTickets(piece);
		if (tickets.isEmpty()) return false;
		final Board.TicketBoard held = tickets.get();
		return held.getCount(ticketFor(transport)) > 0 || held.getCount(Ticket.SECRET) > 0;
	}

	/** @return the ticket that pays for {@code transport}. */
	public static Ticket ticketFor(Transport transport) {
		return transport.requiredTicket();
	}
}
