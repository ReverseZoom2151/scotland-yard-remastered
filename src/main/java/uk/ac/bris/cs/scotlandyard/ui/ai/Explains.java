package uk.ac.bris.cs.scotlandyard.ui.ai;

import uk.ac.bris.cs.scotlandyard.model.Move;

import java.util.List;

/**
 * An {@link uk.ac.bris.cs.scotlandyard.model.Ai} that can explain its last
 * decision.
 *
 * <p>
 * Optional, and deliberately separate from {@code Ai}: the UI probes for it with
 * an {@code instanceof} and renders nothing when an Ai does not implement it.
 */
public interface Explains {

	/** @return the moves it considered, best first, with their scores. */
	List<ScoredMove> lastEvaluation();

	/**
	 * A move and the score the Ai gave it.
	 *
	 * @param move  the move considered
	 * @param score the score it was given; higher is better
	 */
	record ScoredMove(Move move, int score) {
	}
}
