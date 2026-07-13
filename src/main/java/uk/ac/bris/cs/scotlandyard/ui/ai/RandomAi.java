package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableList;

import io.atlassian.fugue.Pair;

import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Move;

/**
 * Plays a uniformly random legal move. The floor of the difficulty ladder, and
 * the control arm of the arena: any AI that cannot beat this is not an AI.
 */
public final class RandomAi implements Ai {

	private final Random random;

	/** Required: the game instantiates AIs reflectively, through the no-arg constructor. */
	public RandomAi() {
		this(new Random().nextLong());
	}

	/** @param seed fixes the sequence of choices, so an arena run can be replayed */
	public RandomAi(long seed) {
		this.random = new Random(seed);
	}

	@Nonnull
	@Override
	public String name() {
		return "Random Rita";
	}

	@Nonnull
	@Override
	public Move pickMove(@Nonnull Board board, Pair<Long, TimeUnit> timeoutPair) {
		final List<Move> moves = ImmutableList.copyOf(board.getAvailableMoves());
		return moves.get(this.random.nextInt(moves.size()));
	}
}
