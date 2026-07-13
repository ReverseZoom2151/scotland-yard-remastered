package uk.ac.bris.cs.scotlandyard.ui.ai;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.LogEntry;
import uk.ac.bris.cs.scotlandyard.model.Move;
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

	// ---------------------------------------------------------------------------
	// Ticket feasibility.
	//
	// Mr X's purse is bounded, so it is tempting to prune candidate paths that
	// could not have been paid for. Two of the three obvious prunes are VACUOUS,
	// and saying so is more useful than pretending otherwise:
	//
	// 1. A SECRET BUDGET PRUNE ON THE PAST IS VACUOUS. It would only bite if
	//    different candidates were reached by paths spending different numbers of
	//    secrets. They are not. LogEntry.ticket() names the exact ticket Mr X paid
	//    for that entry, and the propagation branches only over WHERE an entry could
	//    have taken him, never over WHICH ticket it was. So every surviving path
	//    spends exactly the same multiset of tickets — the one the log spells out —
	//    and no path can be told from another on cost. The ambiguity is spatial, not
	//    monetary. (Corollary: his live SECRET count is 5 minus the number of SECRET
	//    entries in the log, and carries no information the log did not already give.)
	//
	// 2. A DOUBLE BUDGET PRUNE ON THE PAST IS ALSO VACUOUS. MyGameStateFactory.
	//    advanceMrX writes ONE LOG ENTRY PER LEG, each stamped with that leg's own
	//    transport ticket; Ticket.DOUBLE never appears in the log at all (DoubleMove.
	//    tickets() puts it last, past the legs the logger indexes). So a double move
	//    is recorded as two ordinary entries, indistinguishable from two consecutive
	//    single moves — and since the propagation walks the log one ENTRY at a time,
	//    it already models the intermediate station correctly, with no special case.
	//    Knowing which pairs of entries were doubles would tell us nothing extra about
	//    position: two edges walked are two edges walked either way.
	//
	// 3. THE FERRY PRUNE IS REAL, AND ALREADY IN FORCE. Transport.FERRY.requiredTicket()
	//    is SECRET, so servedBy() below can never match a ferry edge against a TAXI,
	//    BUS or UNDERGROUND log entry: a candidate reached over a ferry on a non-secret
	//    entry is already excluded. Nothing to add, but see TicketFeasibilityTest,
	//    which pins it.
	//
	// What ticket counts DO buy is the FUTURE, and that is what the methods below are
	// for. Mr X's SECRET and DOUBLE tickets are never replenished — MyGameStateFactory
	// .build() forbids a detective from holding either, and advanceDetective's
	// mrX.give(move.tickets()) can therefore only ever hand him TAXI, BUS or UNDERGROUND
	// back. So SECRET and DOUBLE are a genuinely finite, monotonically shrinking budget,
	// their live counts are readable from the Board, and once they hit zero the
	// corresponding move simply cannot happen again. A forward model that still hedges
	// over "he might go anywhere, it might be a secret" after his fifth secret is
	// spending its search budget on branches the rules have already ruled out.
	// ---------------------------------------------------------------------------

	/**
	 * How many SECRET tickets Mr X still holds — ground truth, read off the board.
	 *
	 * <p>
	 * Never replenished: detectives are forbidden from holding a secret ticket, so
	 * none can ever be handed back to him. This only ever falls.
	 *
	 * @param board the current board
	 * @return his remaining secret tickets; on the (impossible) event that the board
	 *         will not say, the exact figure implied by the log instead
	 */
	public static int remainingSecrets(Board board) {
		Optional<Integer> held = heldByMrX(board, Ticket.SECRET);
		if (held.isPresent())
			return held.orElseThrow();
		int spent = 0;
		for (LogEntry entry : board.getMrXTravelLog()) {
			if (entry.ticket() == Ticket.SECRET)
				spent++;
		}
		int start = ScotlandYard.defaultMrXTickets().getOrDefault(Ticket.SECRET, 0);
		return Math.max(0, start - spent);
	}

	/**
	 * How many DOUBLE tickets Mr X still holds — ground truth, read off the board.
	 *
	 * <p>
	 * Also never replenished. Unlike secrets this one cannot be recovered from the
	 * log (a double move is logged as its two legs, and the DOUBLE ticket itself is
	 * never written down), so if the board will not say we return the only sound
	 * answer, the upper bound: assume he still has them all.
	 *
	 * @param board the current board
	 * @return his remaining double tickets
	 */
	public static int remainingDoubles(Board board) {
		return heldByMrX(board, Ticket.DOUBLE)
				.orElseGet(() -> ScotlandYard.defaultMrXTickets().getOrDefault(Ticket.DOUBLE, 0));
	}

	/**
	 * @param board the current board
	 * @return whether a secret move is still possible for Mr X, ever again. When this
	 *         is false, every remaining log entry names a real transport, and a
	 *         forward model need no longer hedge over all edges — in particular, no
	 *         ferry edge can be crossed for the rest of the game.
	 */
	public static boolean canStillPlaySecret(Board board) {
		return remainingSecrets(board) > 0;
	}

	/**
	 * @param board the current board
	 * @return whether a double move is still possible for Mr X. False once he is out
	 *         of double tickets, and also false near the end of the game: a double
	 *         spans two rounds, so it needs two left in the log — the same test
	 *         {@code MyGameStateFactory} applies.
	 */
	public static boolean canStillPlayDouble(Board board) {
		return remainingDoubles(board) > 0
				&& board.getMrXTravelLog().size() <= board.getSetup().rounds.size() - 2;
	}

	/**
	 * Where Mr X could be standing after his <i>next</i> move — the candidate set
	 * pushed one Mr X turn into the future, spending only tickets he actually holds.
	 *
	 * <p>
	 * This is where ticket counts genuinely pay. A ticket-blind forward model expands
	 * along every edge (because a secret ticket crosses every edge) and, if it is
	 * careful, over two edges (because he might double). Both are conditional on a
	 * purse the board shows us: once his secrets are gone, ferry edges are closed to
	 * him outright and a "he could be anywhere adjacent" expansion is simply wrong;
	 * once his doubles are gone, the two-step reach vanishes.
	 *
	 * <p>
	 * Deliberately a <b>superset</b>: it does not remove stations the detectives
	 * currently stand on. Detectives move before Mr X does, so today's occupancy is
	 * not the occupancy his move will face, and subtracting it would be exactly the
	 * unsound prune this class warns about everywhere else.
	 *
	 * @param board the current board
	 * @return every station Mr X could occupy after one more move of his
	 */
	public static ImmutableSet<Integer> possibleNextLocations(Board board) {
		return possibleNextLocations(board, possibleLocations(board));
	}

	/**
	 * As {@link #possibleNextLocations(Board)}, but starting from a candidate set the
	 * caller already holds — a {@link Belief}'s, say, which is tighter than the
	 * stateless one.
	 *
	 * @param board      the current board
	 * @param candidates where he could be standing now
	 * @return every station he could occupy after one more move of his
	 */
	public static ImmutableSet<Integer> possibleNextLocations(Board board, Set<Integer> candidates) {
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		final EnumMap<Ticket, Integer> purse = mrXPurse(board);
		final boolean doubles = canStillPlayDouble(board);

		Set<Integer> next = new LinkedHashSet<>();
		for (int from : candidates) {
			if (!graph.nodes().contains(from))
				continue;
			for (int first : graph.adjacentNodes(from)) {
				for (Ticket paid : affordable(graph, purse, from, first)) {
					next.add(first);
					if (!doubles)
						continue;
					// Cost the second leg against the purse the first leg leaves behind: two
					// secret legs in one double need two secrets, exactly as makeDoubleMoves
					// insists.
					purse.merge(paid, -1, Integer::sum);
					for (int second : graph.adjacentNodes(first)) {
						if (!affordable(graph, purse, first, second).isEmpty())
							next.add(second);
					}
					purse.merge(paid, 1, Integer::sum);
				}
			}
		}
		return ImmutableSet.copyOf(next);
	}

	/**
	 * The tickets Mr X could pay for edge {@code (a, b)} with, out of {@code purse}.
	 * A secret ticket covers any edge he can hold one for; a ferry edge admits nothing
	 * else, since {@link Transport#requiredTicket()} maps FERRY to SECRET.
	 */
	static Set<Ticket> affordable(ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph,
			EnumMap<Ticket, Integer> purse, int a, int b) {
		final ImmutableSet<Transport> transports = graph.edgeValueOrDefault(a, b, ImmutableSet.of());
		if (transports.isEmpty())
			return EnumSet.noneOf(Ticket.class);
		Set<Ticket> payable = EnumSet.noneOf(Ticket.class);
		for (Transport t : transports) {
			if (purse.getOrDefault(t.requiredTicket(), 0) > 0)
				payable.add(t.requiredTicket());
		}
		if (purse.getOrDefault(Ticket.SECRET, 0) > 0)
			payable.add(Ticket.SECRET);
		return payable;
	}

	/**
	 * The purse Mr X will have <i>by the time he next moves</i> — which is not simply
	 * the purse he has now, and getting that wrong would make the forward set unsound.
	 *
	 * <p>
	 * The detectives move before he does, and {@code advanceDetective} hands each
	 * ticket a detective spends straight to Mr X. So his TAXI, BUS and UNDERGROUND
	 * counts can still <i>grow</i> between this board and his turn: a zero we see now
	 * is not a zero he will face, and pruning taxi edges on it would cut off a move he
	 * really can make. We therefore credit him, up front, with every replenishable
	 * ticket the detectives are still holding. That is an over-estimate, and an
	 * over-estimate is the safe direction — it can only make the forward set larger.
	 *
	 * <p>
	 * SECRET and DOUBLE need no such allowance, and this is the whole reason the
	 * pruning works: {@code MyGameStateFactory.build} forbids a detective from holding
	 * either, so no detective can ever hand one over. Those two counts are exact, they
	 * only ever fall, and they are the only tickets it is sound to prune on.
	 *
	 * <p>
	 * If the board declines to show a count at all — it never does in this model, but
	 * the {@link Board} contract permits it — assume he can afford anything.
	 */
	static EnumMap<Ticket, Integer> mrXPurse(Board board) {
		final EnumMap<Ticket, Integer> purse = new EnumMap<>(Ticket.class);
		final Optional<Board.TicketBoard> tickets = board.getPlayerTickets(Piece.MrX.MRX);
		for (Ticket ticket : Ticket.values()) {
			purse.put(ticket, tickets.map(t -> t.getCount(ticket)).orElse(Integer.MAX_VALUE));
		}
		// If it is already Mr X's turn, no detective moves before him, so nothing can be
		// handed over and the counts we can see are exactly the counts he will spend.
		if (mrXToMove(board))
			return purse;
		for (Ticket ticket : ScotlandYard.DETECTIVE_TICKETS) {
			final int held = purse.getOrDefault(ticket, 0);
			if (held == Integer.MAX_VALUE)
				continue;
			purse.put(ticket, held + heldByDetectives(board, ticket));
		}
		return purse;
	}

	/** @return whether the board is waiting on Mr X, so that no detective moves before he does. */
	private static boolean mrXToMove(Board board) {
		final ImmutableSet<Move> moves = board.getAvailableMoves();
		return !moves.isEmpty() && moves.iterator().next().commencedBy().isMrX();
	}

	/** @return how many of {@code ticket} the detectives hold between them, and could yet give him. */
	private static int heldByDetectives(Board board, Ticket ticket) {
		int total = 0;
		for (Piece piece : board.getPlayers()) {
			if (!piece.isDetective())
				continue;
			final Optional<Board.TicketBoard> tickets = board.getPlayerTickets(piece);
			if (tickets.isEmpty())
				return Integer.MAX_VALUE / 2;
			total += tickets.orElseThrow().getCount(ticket);
		}
		return total;
	}

	/** @return how many of {@code ticket} Mr X holds, if the board will say. */
	private static Optional<Integer> heldByMrX(Board board, Ticket ticket) {
		return board.getPlayerTickets(Piece.MrX.MRX).map(t -> t.getCount(ticket));
	}

	private MrXLocator() {
	}
}
