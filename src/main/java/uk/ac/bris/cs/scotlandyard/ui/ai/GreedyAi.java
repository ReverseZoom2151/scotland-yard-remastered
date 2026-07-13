package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;

/**
 * One ply, no search: score every legal move by where it lands and take the
 * best. Mr X maximises {@link Evaluator}; a detective simply walks towards
 * wherever {@link MrXLocator} thinks Mr X is.
 *
 * <p>
 * It exists to be beaten. A search that cannot outplay a single greedy ply is
 * not earning its time, so this is the yardstick the arena measures against.
 */
public final class GreedyAi implements Ai {

	/** Built on the first move: {@link #onStart()} is handed no board, and the graph never changes. */
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
		if (this.distances == null) {
			this.distances = new Distances(board.getSetup().graph);
			this.evaluator = new Evaluator(this.distances);
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
		final Move fallback = board.getAvailableMoves().iterator().next();
		try {
			prepare(board);
			final boolean mrXToMove = fallback.commencedBy().isMrX();
			final Move chosen = mrXToMove ? bestForMrX(board) : bestForDetective(board, fallback);
			return board.getAvailableMoves().contains(chosen) ? chosen : fallback;
		} catch (RuntimeException | StackOverflowError anything) {
			// A crashed AI is worse than a dumb one; the contract is only that a legal
			// move comes back.
			return fallback;
		}
	}

	private Move bestForMrX(Board board) {
		Move best = null;
		int bestScore = Integer.MIN_VALUE;
		for (Move move : board.getAvailableMoves()) {
			final int score = this.evaluator.score(board, destinationOf(move));
			if (best == null || score > bestScore) {
				best = move;
				bestScore = score;
			}
		}
		return best;
	}

	/**
	 * Closes on Mr X's most likely station. With no evidence at all — before the
	 * first reveal — every station is equally likely and the locator says nothing;
	 * then any legal move is as good as any other.
	 */
	private Move bestForDetective(Board board, Move fallback) {
		final Optional<Integer> target = MrXLocator.mostLikelyLocation(board, this.distances);
		if (target.isEmpty()) return fallback;

		Move best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (Move move : board.getAvailableMoves()) {
			final int distance = this.distances.ticketAwareDistance(
					board, move.commencedBy(), destinationOf(move), target.get());
			if (best == null || distance < bestDistance) {
				best = move;
				bestDistance = distance;
			}
		}
		return best;
	}
}
