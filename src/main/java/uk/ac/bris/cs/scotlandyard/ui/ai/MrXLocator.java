package uk.ac.bris.cs.scotlandyard.ui.ai;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.LogEntry;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * Infers where Mr X might be, for an AI playing the detectives.
 *
 * <p>
 * {@link Board} deliberately exposes no {@code getMrXLocation()}. The travel log
 * names a station only on the reveal rounds (3, 8, 13, 18, 24); the rest of the
 * time it records nothing but the <i>kind</i> of ticket he spent.
 *
 * <p>
 * That ticket is the leak. From a known position, a taxi ticket can only have
 * carried him along a taxi edge — so the set of places he could now be is the
 * taxi-neighbours of where he was. Applying that once per logged move, starting
 * from the last reveal, gives a set of candidates that stays small for a few
 * rounds and then blooms. Two things prune it: he cannot be standing on a
 * detective, and a secret ticket tells you nothing at all, since it crosses any
 * edge — including ferries.
 */
public final class MrXLocator {

	/**
	 * @param board the current board, seen from a detective's side
	 * @return every station Mr X could be standing on, consistent with the travel
	 *         log and the detectives' positions. Never empty: if the log implies a
	 *         contradiction, falls back to every unoccupied station.
	 */
	public static ImmutableSet<Integer> possibleLocations(Board board) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		final ImmutableList<LogEntry> log = board.getMrXTravelLog();
		final Set<Integer> occupied = detectiveLocations(board);

		// Index of the last entry that actually names a station; -1 if he has never
		// surfaced. Note we trust location() rather than setup.rounds — they agree,
		// but the log is the ground truth we are reading.
		int lastReveal = -1;
		for (int i = log.size() - 1; i >= 0; i--) {
			if (log.get(i).location().isPresent()) {
				lastReveal = i;
				break;
			}
		}

		// Seed. After a reveal the seed is a singleton: exactly where he stood when
		// entry `lastReveal` was written. Before any reveal we only know the rules of
		// the game — he began on one of Mr X's designated start stations — so seed with
		// those (restricted to stations the map actually has). If the map is not the
		// standard one, fall back to every station. Either way the per-entry ticket
		// expansion below still narrows things: a start pool run through, say, an
		// underground move collapses to the tube-reachable stations only.
		Set<Integer> candidates = new LinkedHashSet<>();
		if (lastReveal >= 0) {
			candidates.add(log.get(lastReveal).location().orElseThrow());
		} else {
			for (int start : ScotlandYard.MRX_LOCATIONS) {
				if (graph.nodes().contains(start))
					candidates.add(start);
			}
			if (candidates.isEmpty())
				candidates.addAll(graph.nodes());
		}

		// Propagate forward, one logged move at a time. A DOUBLE move writes two
		// entries, so iterating entries handles it with no special case.
		//
		// Do NOT prune intermediate steps against where the detectives stand now. Those
		// steps are stations Mr X passed through in the PAST, and a detective that has
		// since walked onto one of them would sever a branch he really took, dropping his
		// true location from the set without any sign that it had happened. What we know
		// is only about the present: he cannot be standing on a detective right now. So
		// the occupancy filter belongs on the final set alone.
		//
		// Per-step pruning IS strictly stronger — but only if each step is pruned against
		// the detective positions AS THEY WERE IN THAT ROUND, and a Board carries no
		// history: it shows the travel log and where the detectives stand at this instant,
		// nothing else. There is no sound way to recover the earlier positions from it, so
		// there is no sound way to do it here. Doing it soundly needs the positions to be
		// captured as the game runs, which is what {@link Belief} is for; a detective AI
		// that keeps one Belief per game gets the tighter set, and everything that can only
		// see a Board gets this, the weaker but honest answer.
		for (int i = lastReveal + 1; i < log.size() && !candidates.isEmpty(); i++) {
			candidates = expand(graph, candidates, log.get(i).ticket());
		}
		candidates.removeAll(occupied);

		// Never empty. A contradiction would mean our model of the log is wrong; rather
		// than hand the search an empty set, admit total ignorance.
		if (candidates.isEmpty()) {
			Set<Integer> all = new LinkedHashSet<>(graph.nodes());
			all.removeAll(occupied);
			if (all.isEmpty())
				all.addAll(graph.nodes());
			return ImmutableSet.copyOf(all);
		}
		return ImmutableSet.copyOf(candidates);
	}

	/**
	 * One step of the propagation: every station reachable from some candidate by an
	 * edge that {@code ticket} pays for.
	 *
	 * <p>
	 * A SECRET ticket crosses <i>any</i> edge, so it expands along all neighbours
	 * unconditionally. That includes ferry edges, which no other ticket can use, since
	 * their required ticket is itself SECRET.
	 */
	private static Set<Integer> expand(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			Set<Integer> candidates, Ticket ticket) {
		Set<Integer> next = new LinkedHashSet<>();
		boolean secret = ticket == Ticket.SECRET;
		for (int c : candidates) {
			if (!graph.nodes().contains(c))
				continue;
			for (int n : graph.adjacentNodes(c)) {
				if (secret || servedBy(graph, c, n, ticket))
					next.add(n);
			}
		}
		return next;
	}

	/** @return whether some transport on edge {@code (a, b)} is paid for by {@code ticket}. */
	private static boolean servedBy(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			int a, int b, Ticket ticket) {
		for (Transport t : graph.edgeValueOrDefault(a, b, ImmutableSet.of())) {
			if (t.requiredTicket() == ticket)
				return true;
		}
		return false;
	}

	/** @return the stations the detectives currently stand on. */
	private static Set<Integer> detectiveLocations(Board board) {
		Set<Integer> occupied = new HashSet<>();
		for (Piece p : board.getPlayers()) {
			if (p.isDetective() && p instanceof Piece.Detective d)
				board.getDetectiveLocation(d).ifPresent(occupied::add);
		}
		return occupied;
	}

	/**
	 * The single station that best represents where Mr X probably is — the
	 * candidate a detective should walk towards when it has to commit to one.
	 *
	 * @param board     the current board
	 * @param distances precomputed map distances
	 * @return the most plausible station, or empty if the candidate set is
	 *         degenerate
	 */
	public static Optional<Integer> mostLikelyLocation(Board board, Distances distances) {
		// CHOICE: maximin — the candidate whose distance to the *nearest* detective is
		// largest. Reasoning: the candidate set is a set of possibilities, not a
		// probability distribution, and Mr X is an adversary who picks from it. He will
		// not have walked into the detectives' laps; the branches of the log that
		// survive in practice are the ones that took him away from them. Assuming he
		// sits at the safest surviving candidate is therefore both the pessimistic
		// (worst-case) assumption and the behaviourally realistic one, and chasing it
		// is safe: a detective walking towards the hardest candidate to catch shortens
		// its distance to *every* candidate that is closer in, so we lose nothing if he
		// is actually somewhere easier. A medoid was the alternative, but it can name a
		// station Mr X would never choose — the geometric centre of a set can sit right
		// next to a detective — and it degrades badly once the set blooms and its
		// centre becomes an artefact of the map's shape rather than of Mr X's choices.
		// Ties break towards the candidate with the smaller station number, purely for
		// determinism.
		ImmutableSet<Integer> candidates = possibleLocations(board);
		if (candidates.isEmpty())
			return Optional.empty();

		Set<Integer> detectives = detectiveLocations(board);
		if (detectives.isEmpty())
			return Optional.of(candidates.iterator().next());

		Integer best = null;
		int bestSafety = Integer.MIN_VALUE;
		for (int c : candidates) {
			int safety = Integer.MAX_VALUE;
			for (int d : detectives) {
				int hops = distances.hops(d, c);
				if (hops < safety)
					safety = hops;
			}
			if (safety > bestSafety || (safety == bestSafety && best != null && c < best)) {
				bestSafety = safety;
				best = c;
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * The same inference, kept <i>incrementally</i> as the game runs — and therefore
	 * able to prune every step, not just the last one.
	 *
	 * <p>
	 * <b>Why this is stronger.</b> {@link #possibleLocations(Board)} must rebuild the
	 * candidate set from scratch out of a single Board, and a Board has no memory: it
	 * cannot say where the detectives stood three rounds ago. So it can only apply the
	 * one fact it can see — that Mr X is not standing on a detective <i>now</i> — and
	 * every branch of the log that passed through a station a detective has since
	 * occupied survives, because there is no sound way to rule it out. A Belief instead
	 * folds each round in as it happens, and at the moment it folds round {@code r} in,
	 * the candidates it holds are exactly the stations Mr X could be standing on
	 * <i>right then</i> — so the detective positions it can see <i>right then</i> are
	 * the ones that matter, and pruning with them is sound. Round {@code r + 1} is then
	 * expanded from the survivors only, and the pruning compounds.
	 *
	 * <p>
	 * <b>Why this is sound.</b> Every prune is applied to a set of "where he is
	 * standing at this instant" against "where a detective is standing at this
	 * instant", and those two are never the same station: had a detective stepped onto
	 * him the game would be over. Note what is <i>not</i> pruned — the middle station of
	 * a DOUBLE move. He never stands there between observations, so no observation of
	 * ours is contemporaneous with his being there, and pruning it would be the very
	 * mistake described above. The gain is not worth an unsound set.
	 *
	 * <p>
	 * <b>Contract.</b> One Belief per game, per AI. Call {@link #observe(Board)} every
	 * time the AI is handed a board; skipping boards is safe — it only costs prunes, it
	 * never invents them — but the more it sees the tighter it gets. It re-seeds itself
	 * at every reveal, so it also recovers from any gap. It is not thread-safe.
	 */
	public static final class Belief {

		private final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph;

		/** Where he could be standing, as of the last observation. Null before the first. */
		private Set<Integer> candidates;

		/** How many log entries have been folded in. */
		private int consumed;

		/** @param graph the game map, from {@code board.getSetup().graph} */
		public Belief(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph) {
			this.graph = graph;
			reset();
		}

		/** Forgets everything; the next {@link #observe} starts the inference afresh. */
		public void reset() {
			this.candidates = null;
			this.consumed = 0;
		}

		/**
		 * Folds a board into the belief and returns the candidate set.
		 *
		 * @param board the current board, seen from a detective's side
		 * @return every station Mr X could be standing on. Never empty: on a
		 *         contradiction it falls back to {@link #possibleLocations(Board)}, which
		 *         is itself never empty.
		 */
		public ImmutableSet<Integer> observe(Board board) {
			final ImmutableList<LogEntry> log = board.getMrXTravelLog();
			final Set<Integer> occupied = detectiveLocations(board);

			// A shorter log than we have already eaten means this is not the game we were
			// following (a fresh game, or a replay rewound). Start over.
			if (this.candidates == null || log.size() < this.consumed) {
				seed();
			}

			// A reveal names him outright. Re-seed on it: it is both the sharpest possible
			// belief and a self-repair, wiping out any staleness from boards we never saw.
			for (int i = log.size() - 1; i >= this.consumed; i--) {
				if (log.get(i).location().isPresent()) {
					this.candidates = new LinkedHashSet<>();
					this.candidates.add(log.get(i).location().orElseThrow());
					this.consumed = i + 1;
					break;
				}
			}

			// One expansion per entry we have not seen before. No pruning inside the loop:
			// the middle station of a double move is not a place he stands, so we hold no
			// contemporaneous observation of it (see the class notes).
			for (; this.consumed < log.size(); this.consumed++) {
				this.candidates = expand(this.graph, this.candidates, log.get(this.consumed).ticket());
			}

			// The prune, applied to where he is standing NOW against where the detectives
			// are standing NOW. This is the whole point: it happens on every observation,
			// so the set it leaves behind is the set the next round expands from.
			this.candidates.removeAll(occupied);

			if (this.candidates.isEmpty()) {
				// Our model of the log has come apart. Rather than hand the search an empty
				// set, drop back to the stateless inference and carry on from there.
				this.candidates = new LinkedHashSet<>(possibleLocations(board));
				this.consumed = log.size();
			}
			return ImmutableSet.copyOf(this.candidates);
		}

		/** Seeds from the start of the game: Mr X's designated starting stations. */
		private void seed() {
			this.candidates = new LinkedHashSet<>();
			for (int start : ScotlandYard.MRX_LOCATIONS) {
				if (this.graph.nodes().contains(start)) this.candidates.add(start);
			}
			if (this.candidates.isEmpty()) this.candidates.addAll(this.graph.nodes());
			this.consumed = 0;
		}
	}

	private MrXLocator() {
	}
}
