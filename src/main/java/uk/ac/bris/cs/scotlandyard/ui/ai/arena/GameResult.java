package uk.ac.bris.cs.scotlandyard.ui.ai.arena;

import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;

/**
 * The record of a single arena game: who won, from where, how long it took and
 * whether either side broke the rules on the way.
 *
 * <p>
 * Deliberately flat and dumb — it is written to CSV and read by spreadsheets, so
 * it holds numbers, not objects.
 *
 * @param winner            who won, or {@link Winner#NONE} if the game was cut short
 * @param mrXStart          Mr X's starting station
 * @param detectiveStarts   the detectives' starting stations, in seating order
 * @param totalMoves        every move played, by anybody
 * @param travelLogSize     rounds Mr X survived
 * @param slowestMoveMillis the longest a single {@code pickMove} took
 * @param illegalMoveAttempts moves returned that were not in {@code getAvailableMoves()},
 *                          including moves that could not be produced at all because the
 *                          AI threw; any of these disqualifies the side that made them
 */
public record GameResult(
		Winner winner,
		int mrXStart,
		ImmutableList<Integer> detectiveStarts,
		int totalMoves,
		int travelLogSize,
		long slowestMoveMillis,
		int illegalMoveAttempts) {

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
	}

	/** @return whether an AI broke the move contract during this game */
	public boolean hadIllegalMove() {
		return this.illegalMoveAttempts > 0;
	}

	public static String csvHeader() {
		return "winner,mrXStart,detectiveStarts,totalMoves,travelLogSize,"
				+ "slowestMoveMillis,illegalMoveAttempts";
	}

	/** @return one CSV row; the detective starts are one field, space separated */
	public String toCsvRow() {
		final String starts = this.detectiveStarts.stream()
				.map(String::valueOf)
				.collect(Collectors.joining(" "));
		return String.format(java.util.Locale.ROOT, "%s,%d,%s,%d,%d,%d,%d",
				this.winner, this.mrXStart, starts, this.totalMoves, this.travelLogSize,
				this.slowestMoveMillis, this.illegalMoveAttempts);
	}
}
