package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;

/**
 * The entry point the game calls: works out whose turn it is, hands the position
 * to {@link Search}, and — whatever happens — hands back a legal move.
 *
 * <p>
 * The application treats an AI that throws, or that returns a move outside
 * {@link Board#getAvailableMoves()}, as fatal, so every path through
 * {@link #pickMove} ends in a move taken from that very set. A dumb move played
 * on time beats a clever one that never arrives.
 */
public final class MyAi implements Ai {

	/**
	 * How much of the allotted time we give back, so the move is returned before the
	 * AI is killed. Generous, because the cost of overrunning is losing outright.
	 */
	private static final long SAFETY_BUFFER_NANOS = 500_000_000L;

	/**
	 * The buffer never eats more than this share of the budget. A flat 500ms is the
	 * right giveback against the 30 second move timer the game actually uses, but it
	 * swallows a small budget whole and leaves no time to search at all, which turns
	 * every short-budget move into a blind fallback.
	 */
	private static final int BUFFER_MAX_SHARE = 5;

	/**
	 * Built lazily, on the first move. {@link #onStart()} is handed no board, and
	 * the all-pairs precomputation needs the graph; the graph is the same all game,
	 * so one build is enough.
	 */
	private Distances distances;
	private Search search;

	/**
	 * The weight vector this instance plays with, and the source of its randomness.
	 *
	 * <p>
	 * Mr X breaks ties between near-equal root moves at random, which is a strength fix
	 * rather than a flourish: a deterministic Mr X is one whose next move the detectives
	 * can simply <i>compute</i>, and determinism is what an inference engine eats. The
	 * generator is per-instance, so the two brains in a mirror match do not share a
	 * stream, and games run in parallel in the arena do not contend on one.
	 */
	private final EvalWeights weights;
	private final Random rng;

	/** Required: the game instantiates AIs reflectively, through the no-arg constructor. */
	public MyAi() {
		this(EvalWeights.fromSystemProperties());
	}

	/** For the arena and the tests: play with a named weight vector. */
	public MyAi(EvalWeights weights) {
		this.weights = weights;
		this.rng = new Random();
	}

	@Nonnull
	@Override
	public String name() {
		return "Boris the Bloodhound";
	}

	@Override
	public void onStart() {
		// No board here, so nothing to precompute yet; see prepare(Board).
		this.distances = null;
		this.search = null;
	}

	/** Builds — once — the map distances and the search that leans on them. */
	private Search prepare(Board board) {
		if (this.search == null) {
			this.distances = new Distances(board.getSetup().graph);
			this.search = new Search(new Evaluator(this.distances, this.weights), this.distances,
					this.weights, this.rng);
		}
		return this.search;
	}

	@Nonnull
	@Override
	public Move pickMove(@Nonnull Board board, Pair<Long, TimeUnit> timeoutPair) {
		final Move fallback = board.getAvailableMoves().iterator().next();
		try {
			final long budget = timeoutPair.right().toNanos(timeoutPair.left());
			final long buffer = Math.min(SAFETY_BUFFER_NANOS, budget / BUFFER_MAX_SHARE);
			final long deadline = System.nanoTime() + Math.max(budget - buffer, 0L);

			final Search engine = prepare(board);
			final boolean mrXToMove = board.getAvailableMoves().stream()
					.anyMatch(move -> move.commencedBy().isMrX());

			final Move chosen = mrXToMove
					? engine.bestMoveForMrX(board, deadline)
					: engine.bestMoveForDetective(board, deadline);

			// The contract is absolute: the move must come from the board's own set.
			return board.getAvailableMoves().contains(chosen) ? chosen : fallback;
		} catch (RuntimeException | StackOverflowError anything) {
			// A crashed AI is worse than a dumb one, and this is the only place that can
			// tell the difference.
			return fallback;
		}
	}
}
