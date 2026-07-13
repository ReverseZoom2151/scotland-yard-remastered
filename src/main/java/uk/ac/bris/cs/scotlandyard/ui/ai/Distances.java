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
 * Three notions of distance live here.
 *
 * <ul>
 * <li>{@link #hops(int, int)} — the plain hop count over <i>every</i> edge,
 * ferries included. This is <b>Mr X's</b> metric: he is the only player who can
 * hold a SECRET ticket, and a ferry edge's required ticket <i>is</i> SECRET, so
 * he is the only player who can ever board one.
 * <li>{@link #detectiveHops(int, int)} — the same hop count over the edges a
 * <b>detective</b> can actually walk: every edge carrying at least one non-ferry
 * transport. Detectives never hold SECRET tickets ({@code MyGameStateFactory}
 * rejects a detective that does), so a ferry-only edge is a wall to them. Use
 * this whenever the question is "how close is a detective to X"; using
 * {@link #hops} there makes detectives look nearer than they can ever get, and
 * flatters Mr X's sense of safety.
 * <li>{@link #ticketAwareDistance} — a detective's distance given the tickets it
 * is actually holding right now. Strictly the sharpest of the three for a
 * detective, and it already excludes ferries for free (see its notes), but it is
 * a BFS per call rather than a table lookup.
 * </ul>
 *
 * <p>
 * Both hop tables are precomputed for every pair once, since the map never
 * changes.
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

	/** As {@link #allPairs}, but over detective-usable (non-ferry-only) edges alone. */
	private final int[][] allPairsNoFerry;

	/** Dense adjacency: neighbours[i] holds the indices adjacent to index i. */
	private final int[][] neighbours;

	/** As {@link #neighbours}, minus the neighbours only a ferry edge reaches. */
	private final int[][] neighboursNoFerry;

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
		this.neighboursNoFerry = new int[n][];
		for (int i = 0; i < n; i++) {
			final var adjacent = graph.adjacentNodes(this.nodes[i]);
			final int[] row = new int[adjacent.size()];
			final int[] walkable = new int[adjacent.size()];
			int k = 0;
			int w = 0;
			for (int neighbour : adjacent) {
				final int j = this.indexOf.get(neighbour);
				row[k++] = j;
				if (detectiveUsable(graph, this.nodes[i], neighbour)) walkable[w++] = j;
			}
			this.neighbours[i] = row;
			this.neighboursNoFerry[i] = Arrays.copyOf(walkable, w);
		}

		// 199 BFS runs per metric, one per source; trivially cheap and done once.
		this.allPairs = new int[n][];
		this.allPairsNoFerry = new int[n][];
		for (int i = 0; i < n; i++) {
			this.allPairs[i] = bfs(i, this.neighbours);
			this.allPairsNoFerry[i] = bfs(i, this.neighboursNoFerry);
		}
	}

	/**
	 * @return whether a detective could ever cross the edge {@code (a, b)}: it must
	 *         carry a transport other than the ferry. A ferry demands a SECRET
	 *         ticket, and no detective is ever dealt one.
	 */
	private static boolean detectiveUsable(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			int a, int b) {
		for (Transport transport : graph.edgeValueOrDefault(a, b, ImmutableSet.of())) {
			if (transport != Transport.FERRY) return true;
		}
		return false;
	}

	/** Plain hop-count BFS from a dense source index over the given adjacency. */
	private int[] bfs(int source, int[][] adjacency) {
		final int[] dist = new int[this.nodes.length];
		Arrays.fill(dist, Integer.MAX_VALUE);
		dist[source] = 0;
		final Deque<Integer> queue = new ArrayDeque<>();
		queue.add(source);
		while (!queue.isEmpty()) {
			final int current = queue.remove();
			for (int neighbour : adjacency[current]) {
				if (dist[neighbour] == Integer.MAX_VALUE) {
					dist[neighbour] = dist[current] + 1;
					queue.add(neighbour);
				}
			}
		}
		return dist;
	}

	/**
	 * The hop count over <i>every</i> edge, ferries included — <b>Mr X's</b> metric.
	 * For a detective, prefer {@link #detectiveHops(int, int)}: this one lets it
	 * "swim".
	 *
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
	 * The hop count over the edges a <b>detective</b> can walk — every edge except
	 * the ferry-only ones, which need a SECRET ticket no detective ever holds.
	 *
	 * <p>
	 * Always {@code >= hops(source, destination)}: dropping edges can only lengthen
	 * a path. Use it wherever the travelling player is a detective and its exact
	 * ticket purse is unknown or irrelevant; where the purse is known,
	 * {@link #ticketAwareDistance} is sharper still.
	 *
	 * @return the fewest such edges, or {@link Integer#MAX_VALUE} if unreachable
	 */
	public int detectiveHops(int source, int destination) {
		final Integer from = this.indexOf.get(source);
		final Integer to = this.indexOf.get(destination);
		if (from == null || to == null) return Integer.MAX_VALUE;
		return this.allPairsNoFerry[from][to];
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
	 * <p>
	 * <b>Ferries.</b> This already handles them, with no special case: a ferry's
	 * required ticket is SECRET, {@link #canAfford} therefore asks a detective for
	 * a SECRET ticket, and a detective never has one — so a ferry-only edge is
	 * never {@link #usable} to a detective. Mr X, who does carry secrets, keeps
	 * them. Nothing to fix here; the hole was only in {@link #hops}, hence
	 * {@link #detectiveHops}.
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
		final Integer to = this.indexOf.get(destination);
		if (to == null || !this.indexOf.containsKey(source)) return Integer.MAX_VALUE;
		return ticketAwareDistancesFrom(board, piece, source)[to];
	}

	/**
	 * Every ticket-aware distance from one origin, in a single BFS.
	 *
	 * <p>
	 * The same walk as {@link #ticketAwareDistance}, and the same approximation — but
	 * done once for all destinations. A player scoring its own moves is asking about
	 * one origin (its target) and many destinations, and calling
	 * {@link #ticketAwareDistance} in that loop pays for a whole BFS per move. The
	 * subgraph a player can afford is undirected, so the distance from the target to a
	 * move's destination is also the distance from that destination to the target —
	 * which is what {@link #distancesFrom} hands back.
	 *
	 * @param board  the board, for the map and the player's ticket counts
	 * @param piece  the player travelling
	 * @param origin where it starts
	 * @return distances indexed by this instance's dense node index; entries are
	 *         {@link Integer#MAX_VALUE} where unreachable
	 */
	private int[] ticketAwareDistancesFrom(Board board, uk.ac.bris.cs.scotlandyard.model.Piece piece,
			int origin) {
		final int[] dist = new int[this.nodes.length];
		Arrays.fill(dist, Integer.MAX_VALUE);
		final Integer from = this.indexOf.get(origin);
		if (from == null) return dist;

		// Which transports this player can pay for at all, resolved once up front.
		final Map<Transport, Boolean> affordable = new HashMap<>();
		for (Transport transport : Transport.values()) {
			affordable.put(transport, canAfford(board, piece, transport));
		}

		dist[from] = 0;
		final Deque<Integer> queue = new ArrayDeque<>();
		queue.add(from);
		while (!queue.isEmpty()) {
			final int current = queue.remove();
			for (int neighbour : this.neighbours[current]) {
				if (dist[neighbour] != Integer.MAX_VALUE) continue;
				if (!usable(current, neighbour, affordable)) continue;
				dist[neighbour] = dist[current] + 1;
				queue.add(neighbour);
			}
		}
		return dist;
	}

	/**
	 * A reusable table of ticket-aware distances from one station, for a player about
	 * to score many candidate destinations against the same target. One BFS, then a
	 * lookup per destination.
	 *
	 * @param board  the board, for the map and the player's ticket counts
	 * @param piece  the player travelling
	 * @param origin the station every distance is measured from
	 * @return a table; see {@link Table#to(int)}
	 */
	public Table distancesFrom(Board board, uk.ac.bris.cs.scotlandyard.model.Piece piece, int origin) {
		return new Table(this.indexOf, ticketAwareDistancesFrom(board, piece, origin));
	}

	/** Ticket-aware distances from a fixed origin. See {@link Distances#distancesFrom}. */
	public static final class Table {

		private final Map<Integer, Integer> indexOf;
		private final int[] dist;

		private Table(Map<Integer, Integer> indexOf, int[] dist) {
			this.indexOf = indexOf;
			this.dist = dist;
		}

		/**
		 * @return the distance from the origin to {@code station}, or
		 *         {@link Integer#MAX_VALUE} if it cannot be afforded or does not exist
		 */
		public int to(int station) {
			final Integer index = this.indexOf.get(station);
			return index == null ? Integer.MAX_VALUE : this.dist[index];
		}
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
