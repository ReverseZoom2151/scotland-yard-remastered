package uk.ac.bris.cs.scotlandyard.ui.ai.arena;

import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;

/**
 * The record of a single arena game: who won, from where, how long it took,
 * whether either side broke the rules on the way — and, crucially, <i>how</i> the
 * game was won.
 *
 * <p>
 * Deliberately flat and dumb — it is written to CSV and read by spreadsheets, so
 * it holds numbers, not objects.
 *
 * <p>
 * <b>Why the telemetry below exists.</b> A win rate alone cannot tell you whether
 * a strategy change worked <i>for the reason you intended</i>. Concretely: Mr X's
 * flat secret/double penalties are being replaced by hard gates whose whole intent
 * is that he spends secrets on the rounds immediately <i>after</i> a reveal (4, 9,
 * 14, 19), when the detectives' belief has collapsed onto a single station and a
 * secret ticket therefore buys the most ambiguity. If the win rate goes up but the
 * secret-spend histogram does <b>not</b> shift onto those rounds, then the change
 * won by luck (or by some unrelated side effect), not by its mechanism, and it will
 * not survive a change of opponent. {@link #secretSpentRounds()},
 * {@link #doubleSpentRounds()}, {@link #capturedAtRound()},
 * {@link #candidateSetSizeAtEnd()} and {@link #beliefEntropyAtEnd()} are what tell
 * those two worlds apart: mechanism leaves fingerprints, luck does not.
 *
 * @param winner              who won, or {@link Winner#NONE} if the game was cut short
 * @param mrXStart            Mr X's starting station
 * @param detectiveStarts     the detectives' starting stations, in seating order
 * @param totalMoves          every move played, by anybody
 * @param travelLogSize       rounds Mr X survived
 * @param slowestMoveMillis   the longest a single {@code pickMove} took
 * @param illegalMoveAttempts moves returned that were not in {@code getAvailableMoves()},
 *                            including moves that could not be produced at all because the
 *                            AI threw; any of these disqualifies the side that made them
 * @param secretSpentRounds   the round (1-based, i.e. the travel-log size once the entry is
 *                            written) of every SECRET ticket Mr X spent, in order. A double
 *                            move contributes one round per secret leg.
 * @param doubleSpentRounds   the round of every DOUBLE ticket Mr X spent — the round of the
 *                            move's first leg
 * @param capturedAtRound     the round Mr X was caught on, or -1 if he escaped, was never
 *                            caught, or the game ended some other way
 * @param candidateSetSizeAtEnd size of {@code MrXLocator.possibleLocations(finalBoard)} at
 *                            game end: how much genuine doubt Mr X still had left
 * @param beliefEntropyAtEnd  normalised Shannon entropy of {@code Suspicion.likelihoods}
 *                            at game end, in [0, 1] — 0 means the detectives knew exactly
 *                            where he was, 1 means total ignorance over the whole map
 * @param mrXFinalLocation    where Mr X actually stood at the end (the arena knows, even
 *                            though the detectives do not), or -1 if unknown
 */
public record GameResult(
		Winner winner,
		int mrXStart,
		ImmutableList<Integer> detectiveStarts,
		int totalMoves,
		int travelLogSize,
		long slowestMoveMillis,
		int illegalMoveAttempts,
		ImmutableList<Integer> secretSpentRounds,
		ImmutableList<Integer> doubleSpentRounds,
		int capturedAtRound,
		int candidateSetSizeAtEnd,
		double beliefEntropyAtEnd,
		int mrXFinalLocation) {

	/** The only outcomes worth counting. */
	public enum Winner {
		/** Mr X survived the log, or the detectives were disqualified. */
		MRX,
		/** Mr X was caught, cornered, or disqualified. */
		DETECTIVES,
		/** The game hit the move ceiling: neither side gets the credit. */
		NONE
	}

	public GameResult {
		detectiveStarts = ImmutableList.copyOf(detectiveStarts);
		secretSpentRounds = ImmutableList.copyOf(secretSpentRounds);
		doubleSpentRounds = ImmutableList.copyOf(doubleSpentRounds);
	}

	/** @return whether an AI broke the move contract during this game */
	public boolean hadIllegalMove() {
		return this.illegalMoveAttempts > 0;
	}

	public static String csvHeader() {
		return "winner,mrXStart,detectiveStarts,totalMoves,travelLogSize,"
				+ "slowestMoveMillis,illegalMoveAttempts,secretSpentRounds,doubleSpentRounds,"
				+ "capturedAtRound,candidateSetSizeAtEnd,beliefEntropyAtEnd,mrXFinalLocation";
	}

	/**
	 * @return one CSV row; the detective starts are one field, space separated, and the
	 *         two round histograms are one field each, pipe separated ("4|9|14")
	 */
	public String toCsvRow() {
		final String starts = this.detectiveStarts.stream()
				.map(String::valueOf)
				.collect(Collectors.joining(" "));
		return String.format(java.util.Locale.ROOT, "%s,%d,%s,%d,%d,%d,%d,%s,%s,%d,%d,%.4f,%d",
				this.winner, this.mrXStart, starts, this.totalMoves, this.travelLogSize,
				this.slowestMoveMillis, this.illegalMoveAttempts,
				join(this.secretSpentRounds), join(this.doubleSpentRounds),
				this.capturedAtRound, this.candidateSetSizeAtEnd, this.beliefEntropyAtEnd,
				this.mrXFinalLocation);
	}

	/** @return the rounds as "4|9|14", or the empty string if there were none */
	private static String join(List<Integer> rounds) {
		return rounds.stream().map(String::valueOf).collect(Collectors.joining("|"));
	}
}
