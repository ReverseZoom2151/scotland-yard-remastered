package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Piece;

/**
 * Alpha-beta search over the game tree, deepened until the clock runs out.
 *
 * <p>
 * The AI is handed a deadline and is killed one second after it passes, so depth
 * cannot be fixed in advance: the search completes a full ply, keeps the best
 * move it found, and only then starts the next ply — abandoning it the moment the
 * budget is gone. Whatever the last completed ply returned is what gets played,
 * so the search is always safe to interrupt.
 *
 * <p>
 * Detectives are not branched. Every detective moving in every combination
 * explodes the tree far beyond what fits in the budget, so the search assumes
 * each takes its shortest step toward Mr X — pessimistic for him, and cheap
 * enough to buy several more plies of depth, which are worth far more.
 */
public final class Search {

	/** Deepest Mr X ply we will ever attempt; a guard against spinning on a trivial position. */
	private static final int MAX_DEPTH = 8;

	/** How far ahead of the caller's deadline we stop, so we always return before the kill. */
	private static final long SAFETY_MARGIN_NANOS = 100_000_000L;

	/**
	 * At most this many of Mr X's possible locations are considered when playing a
	 * detective. Late in a game the candidate set blooms into the hundreds; scoring
	 * every one of them for every available move would blow the budget for no real
	 * gain, since the candidates are heavily clustered and a bounded sample of them
	 * points in the same direction as the whole set.
	 */
	private static final int MAX_CANDIDATES = 30;

	private final Evaluator evaluator;
	private final Distances distances;

	public Search(Evaluator evaluator, Distances distances) {
		this.evaluator = evaluator;
		this.distances = distances;
	}

	/** Thrown to unwind the recursion the instant the budget is gone. Carries no stack trace. */
	private static final class TimeoutException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		TimeoutException() {
			super(null, null, false, false);
		}
	}

	/**
	 * Searches for Mr X's best move, deepening until {@code deadlineNanos}.
	 *
	 * @param board         the current board, on which it is Mr X's turn
	 * @param deadlineNanos when to stop, as a {@link System#nanoTime()} value
	 * @return the best move found by the deepest ply that finished in time
	 */
	public Move bestMoveForMrX(Board board, long deadlineNanos) {
		final long stopAt = deadlineNanos - SAFETY_MARGIN_NANOS;
		final ImmutableSet<Move> legal = board.getAvailableMoves();
		Move best = legal.iterator().next(); // never null, whatever happens below
		final int mrXLocation = BoardStates.mrXLocationOf(board);

		// Iterative deepening: a ply is only allowed to replace the incumbent once it
		// has finished in full. A ply abandoned halfway has seen an arbitrary subset of
		// the moves and its "best so far" is not comparable with anything.
		for (int depth = 1; depth <= MAX_DEPTH; depth++) {
			try {
				final Move candidate = rootSearch(board, mrXLocation, depth, stopAt);
				if (candidate != null) best = candidate;
			} catch (TimeoutException expired) {
				break;
			}
			if (System.nanoTime() > stopAt) break;
		}
		return best;
	}

	/** One complete ply of Mr X's search, rooted at the real board. */
	private Move rootSearch(Board board, int mrXLocation, int depth, long stopAt) {
		final GameState root = BoardStates.rebuild(board, mrXLocation);
		Move best = null;
		int bestValue = Integer.MIN_VALUE;
		int alpha = Integer.MIN_VALUE;

		// Branch over the *real* board's moves, so that whatever we return is guaranteed
		// to be an element of board.getAvailableMoves(). The rebuilt state's move set can
		// differ slightly (its travel log starts empty), so a move it offers but the real
		// board does not would be illegal to play; and one the real board offers but it
		// rejects is simply skipped.
		for (Move move : board.getAvailableMoves()) {
			checkClock(stopAt);
			final GameState next;
			try {
				next = root.advance(move);
			} catch (IllegalArgumentException notPlayableHere) {
				continue;
			}
			final int value = detectivePly(next, destinationOf(move), depth - 1, alpha,
					Integer.MAX_VALUE, stopAt);
			if (best == null || value > bestValue) {
				bestValue = value;
				best = move;
			}
			if (bestValue > alpha) alpha = bestValue;
		}
		return best;
	}

	/**
	 * A maximising node: Mr X to move.
	 */
	private int maxNode(GameState state, int mrXLocation, int depth, int alpha, int beta, long stopAt) {
		checkClock(stopAt);
		final Integer terminal = terminalScore(state);
		if (terminal != null) return terminal;
		if (depth <= 0) return this.evaluator.score(state, mrXLocation);

		int value = Integer.MIN_VALUE;
		for (Move move : state.getAvailableMoves()) {
			final int child = detectivePly(state.advance(move), destinationOf(move), depth - 1,
					alpha, beta, stopAt);
			if (child > value) value = child;
			if (value > alpha) alpha = value;
			if (alpha >= beta) break; // fail-high: the parent already has something better
		}
		return value == Integer.MIN_VALUE ? this.evaluator.score(state, mrXLocation) : value;
	}

	/**
	 * The detectives' reply, modelled greedily rather than branched.
	 *
	 * <p>
	 * CHOICE: branching every detective's every move makes the tree
	 * {@code (mrX moves) x (d1 moves) x ... x (d5 moves)} wide per ply — tens of
	 * thousands of children where Mr X alone has a dozen. Instead each detective is
	 * advanced one at a time, always taking the single move that minimises its own
	 * ticket-aware distance to where Mr X is assumed to stand. That is pessimistic
	 * for Mr X (the detectives never blunder, they just never cooperate either), and
	 * it collapses the ply to a single line, buying several plies of extra depth.
	 * Depth is worth far more here than exactness in a reply that, in a real game,
	 * is played by opponents who are themselves approximating.
	 */
	private int detectivePly(GameState state, int mrXLocation, int depth, int alpha, int beta,
			long stopAt) {
		checkClock(stopAt);
		GameState current = state;
		while (true) {
			final Integer terminal = terminalScore(current);
			if (terminal != null) return terminal;

			final ImmutableSet<Move> moves = current.getAvailableMoves();
			if (moves.isEmpty()) return this.evaluator.score(current, mrXLocation);
			if (moves.iterator().next().commencedBy().isMrX()) {
				// Every detective has now moved; back to Mr X.
				return maxNode(current, mrXLocation, depth, alpha, beta, stopAt);
			}

			checkClock(stopAt);
			current = current.advance(greediestDetectiveMove(current, moves, mrXLocation));
		}
	}

	/** @return the detective move that gets its mover closest to {@code mrXLocation}. */
	private Move greediestDetectiveMove(Board board, ImmutableSet<Move> moves, int mrXLocation) {
		Move best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (Move move : moves) {
			final int destination = destinationOf(move);
			if (destination == mrXLocation) return move; // a capture; nothing beats it
			final int distance = this.distances
					.ticketAwareDistance(board, move.commencedBy(), destination, mrXLocation);
			if (best == null || distance < bestDistance) {
				bestDistance = distance;
				best = move;
			}
		}
		return best;
	}

	/**
	 * Searches for a detective's best move.
	 *
	 * <p>
	 * Mr X's location is unknown, so the move is scored against every location he
	 * could be in — see {@link MrXLocator} — rather than a single assumed position.
	 *
	 * @param board         the current board, on which it is this detective's turn
	 * @param deadlineNanos when to stop, as a {@link System#nanoTime()} value
	 * @return the best move found
	 */
	public Move bestMoveForDetective(Board board, long deadlineNanos) {
		// CHOICE: a greedy, ticket-aware one-ply evaluation, averaged over the candidate
		// set. TRADEOFF: a full alpha-beta *per candidate* is what a detective would
		// ideally run, but the candidate set routinely holds dozens of stations and each
		// one needs its own rebuilt game state, so the budget buys a depth of about one
		// anyway — and a search that overruns is worse than no search at all, since the
		// AI is hard-killed. Worse, the candidates disagree with each other far more than
		// the plies disagree with each other: the dominant error is *where Mr X is*, not
		// how deeply we read the position. So the budget goes on breadth over the
		// candidate set rather than depth on any one of them.
		final long stopAt = deadlineNanos - SAFETY_MARGIN_NANOS;
		final ImmutableSet<Move> legal = board.getAvailableMoves();
		Move best = legal.iterator().next();

		final List<Integer> candidates = sample(MrXLocator.possibleLocations(board));
		if (candidates.isEmpty()) return best;

		double bestCost = Double.POSITIVE_INFINITY;
		for (Move move : legal) {
			if (System.nanoTime() > stopAt) break; // keep the incumbent; never overrun
			final double cost = cost(board, move, candidates);
			if (cost < bestCost) {
				bestCost = cost;
				best = move;
			}
		}
		return best;
	}

	/**
	 * The cost of a detective move, averaged over the candidate locations. Low is
	 * good for the detectives, mirroring {@link Evaluator}'s sign convention.
	 *
	 * <p>
	 * Averaging rather than taking the worst case: the candidate set is a set of
	 * possibilities, and a worst-case rule lets one unreachable outlier veto a move
	 * that closes the net on everything else.
	 */
	private double cost(Board board, Move move, List<Integer> candidates) {
		final Piece mover = move.commencedBy();
		final int destination = destinationOf(move);
		double total = 0;
		for (int candidate : candidates) {
			if (destination == candidate) {
				total += Evaluator.MRX_CAPTURED / (double) candidates.size();
				continue;
			}
			final int mine = this.distances.ticketAwareDistance(board, mover, destination, candidate);
			// Unreachable candidates would otherwise saturate the average; cap them at a
			// distance no real path on a 199-node map exceeds.
			final int capped = mine == Integer.MAX_VALUE ? 99 : mine;
			// The mover's own distance is what counts most; the rest of the team is worth
			// something too, so that detectives do not all pile onto the same station.
			total += 2.0 * capped + nearestOtherDetective(board, mover, candidate);
		}
		return total / candidates.size();
	}

	/** @return the ticket-aware distance from the nearest detective other than {@code mover}. */
	private int nearestOtherDetective(Board board, Piece mover, int candidate) {
		int nearest = 99;
		for (var detective : BoardStates.detectivesOf(board)) {
			if (detective.piece().equals(mover)) continue;
			final int distance = this.distances
					.ticketAwareDistance(board, detective.piece(), detective.location(), candidate);
			if (distance != Integer.MAX_VALUE && distance < nearest) nearest = distance;
		}
		return nearest;
	}

	/** Bounds the candidate set at {@link #MAX_CANDIDATES}, keeping iteration order. */
	private static List<Integer> sample(ImmutableSet<Integer> candidates) {
		final List<Integer> sampled = new ArrayList<>(Math.min(candidates.size(), MAX_CANDIDATES));
		// Stride through the set rather than taking a prefix, so the sample spreads over
		// the whole of it instead of clumping in whichever corner comes first.
		final int stride = Math.max(1, candidates.size() / MAX_CANDIDATES);
		int index = 0;
		for (int candidate : candidates) {
			if (index % stride == 0 && sampled.size() < MAX_CANDIDATES) sampled.add(candidate);
			index++;
		}
		return sampled;
	}

	/** @return the score of a finished game, or {@code null} if it is still running. */
	private static Integer terminalScore(Board board) {
		final ImmutableSet<Piece> winner = board.getWinner();
		if (winner.isEmpty()) return null;
		for (Piece piece : winner) {
			if (piece.isMrX()) return Evaluator.MRX_ESCAPED;
		}
		return Evaluator.MRX_CAPTURED;
	}

	/** @return where a move ends up; the second hop, for a double move. */
	private static int destinationOf(Move move) {
		return move.visit(new Move.FunctionalVisitor<Integer>(
				single -> single.destination,
				dubble -> dubble.destination2));
	}

	private static void checkClock(long stopAt) {
		if (System.nanoTime() > stopAt) throw new TimeoutException();
	}
}
