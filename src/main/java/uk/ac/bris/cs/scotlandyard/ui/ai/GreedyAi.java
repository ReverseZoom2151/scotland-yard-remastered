package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * One ply, no search: score every legal move by where it lands and take the
 * best. Mr X maximises {@link Evaluator}; a detective simply walks towards
 * wherever {@link MrXLocator} thinks Mr X is.
 *
 * <p>
 * It exists to be beaten. A search that cannot outplay a single greedy ply is
 * not earning its time, so this is the yardstick the arena measures against.
 *
 * <p>
 * It also keeps to its time budget. One ply is cheap enough that it never
 * expected to run out — but a yardstick that overruns its allowance distorts
 * every arena run it appears in, and "it is usually fast" is not a deadline. It
 * scores moves until the clock says stop and then plays the best it has.
 */
public final class GreedyAi implements Ai {

	/**
	 * How much of the budget to leave for handing the move back. Small: there is
	 * nothing to unwind, only a return.
	 */
	private static final long MARGIN_MILLIS = 20;

	/**
	 * The map is the same 199-node graph in every game, so the precompute is shared
	 * across instances and games rather than repeated per game. Guarded by the class
	 * lock, and keyed by the graph so a non-standard map still gets its own.
	 */
	private static ImmutableValueGraph<Integer, ImmutableSet<Transport>> cachedGraph;
	private static Distances cachedDistances;
	private static Evaluator cachedEvaluator;

	private Distances distances;
	private Evaluator evaluator;

	/** Required: the game instantiates AIs reflectively, through the no-arg constructor. */
	public GreedyAi() {
	}

	@Nonnull
	@Override
	public String name() {
		return "Greedy Gary";
	}

	@Override
	public void onStart() {
		this.distances = null;
		this.evaluator = null;
	}

	private void prepare(Board board) {
		if (this.distances != null) return;
		final ImmutableValueGraph<Integer, ImmutableSet<Transport>> graph = board.getSetup().graph;
		synchronized (GreedyAi.class) {
			if (!graph.equals(cachedGraph)) {
				cachedDistances = new Distances(graph);
				cachedEvaluator = new Evaluator(cachedDistances);
				cachedGraph = graph;
			}
			this.distances = cachedDistances;
			this.evaluator = cachedEvaluator;
		}
	}

	/** Where a move ends up — the second hop, for a double move. */
	private static int destinationOf(Move move) {
		return move.visit(new Move.FunctionalVisitor<>(
				single -> single.destination, doubleMove -> doubleMove.destination2));
	}

	@Nonnull
	@Override
	public Move pickMove(@Nonnull Board board, Pair<Long, TimeUnit> timeoutPair) {
		final long deadline = deadlineFrom(timeoutPair);
		final Move fallback = board.getAvailableMoves().iterator().next();
		try {
			prepare(board);
			final boolean mrXToMove = fallback.commencedBy().isMrX();
			final Move chosen = mrXToMove
					? bestForMrX(board, deadline, fallback)
					: bestForDetective(board, deadline, fallback);
			return board.getAvailableMoves().contains(chosen) ? chosen : fallback;
		} catch (RuntimeException | StackOverflowError anything) {
			// A crashed AI is worse than a dumb one; the contract is only that a legal
			// move comes back.
			return fallback;
		}
	}

	/** The instant, on {@link System#nanoTime}'s clock, by which a move must be back. */
	private static long deadlineFrom(Pair<Long, TimeUnit> timeoutPair) {
		final long budgetMillis = timeoutPair == null
				? Long.MAX_VALUE
				: timeoutPair.right().toMillis(timeoutPair.left());
		// Leave the margin, but never cut the budget below a floor: with a tiny or absurd
		// timeout it is still better to score a few moves than none.
		final long usable = Math.max(1, budgetMillis - MARGIN_MILLIS);
		return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(usable, 3_600_000L));
	}

	private static boolean expired(long deadline) {
		return System.nanoTime() - deadline >= 0;
	}

	private Move bestForMrX(Board board, long deadline, Move fallback) {
		Move best = null;
		int bestScore = Integer.MIN_VALUE;
		for (Move move : board.getAvailableMoves()) {
			// Out of time: play the best of the moves actually looked at. The order the
			// board hands them out is arbitrary, so this is a random sample, not a biased
			// one — and it always beats returning nothing.
			if (best != null && expired(deadline)) return best;
			final int score = this.evaluator.score(board, destinationOf(move));
			if (best == null || score > bestScore) {
				best = move;
				bestScore = score;
			}
		}
		return best == null ? fallback : best;
	}

	/**
	 * Closes on Mr X's most likely station. With no evidence at all — before the
	 * first reveal — every station is equally likely and the locator says nothing;
	 * then any legal move is as good as any other.
	 *
	 * <p>
	 * Every move on offer is measured against the same target, and only a handful of
	 * detectives are ever on offer at once, so the ticket-aware distances are had in
	 * one BFS from the target <i>per detective</i> and then looked up — rather than a
	 * BFS per candidate move, which is what used to make this the slowest thing in the
	 * arena. The affordable subgraph is undirected, so a distance measured out from
	 * the target is the same as the distance measured in to it.
	 */
	private Move bestForDetective(Board board, long deadline, Move fallback) {
		final Optional<Integer> target = MrXLocator.mostLikelyLocation(board, this.distances);
		if (target.isEmpty()) return fallback;

		// A detective turn offers the moves of every detective still to play, so the
		// table depends on whose tickets are paying.
		final Map<Piece, Distances.Table> tables = new HashMap<>();

		Move best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (Move move : board.getAvailableMoves()) {
			if (best != null && expired(deadline)) return best;
			final Distances.Table fromTarget = tables.computeIfAbsent(move.commencedBy(),
					piece -> this.distances.distancesFrom(board, piece, target.get()));
			final int distance = fromTarget.to(destinationOf(move));
			if (best == null || distance < bestDistance) {
				best = move;
				bestDistance = distance;
			}
		}
		return best == null ? fallback : best;
	}
}
