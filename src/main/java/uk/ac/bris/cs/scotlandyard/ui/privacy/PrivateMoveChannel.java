package uk.ac.bris.cs.scotlandyard.ui.privacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;

/**
 * Builds the private, letter-coded move menu that lets Mr X take his turn on a
 * shared (hot-seat) screen without the detectives learning anything.
 *
 * <p>
 * The idea (borrowed from the {@code simple-scotland-yard} web game): the
 * screen shows a QR code encoding a {@code LETTER -> MOVE} table. Mr X scans it
 * privately with his phone; the on-screen dropdown then only ever offers
 * {@code "A"}, {@code "B"}, {@code "C"} ... which are meaningless to anyone who
 * did not scan. Mr X picks a letter, the game plays the corresponding move, and
 * the detectives learn nothing.
 *
 * <h2>Side channels, and how they are closed</h2>
 * A naive implementation leaks Mr X's position even though the destinations are
 * hidden, so three defences are applied here:
 * <ol>
 * <li><b>The option count is padded.</b> The number of entries in the dropdown
 * is a function of a coarse bucket of the legal move count, not of the count
 * itself (see below). Without this, detectives simply count the dropdown rows
 * and learn how well connected Mr X's station is - a real leak, since a station
 * offering 3 moves and one offering 20 are very different places on the
 * board.</li>
 * <li><b>The mapping is reshuffled every turn.</b> {@code "A"} on turn 5 has
 * nothing to do with {@code "A"} on turn 6, so watching which letter Mr X picks
 * across turns carries no information (in particular, "he picked A again" does
 * not mean "he went to the same kind of place again").</li>
 * <li><b>The QR payload is padded to a constant length.</b> Padding slots emit
 * dummy lines and every line is exactly {@link #LINE_WIDTH} characters wide, so
 * the physical size / module density of the rendered QR code is identical for a
 * dead-end station and a hub. Otherwise the QR image itself would leak the
 * branching factor even though the dropdown did not.</li>
 * </ol>
 *
 * <h2>The padding size, and an honest tradeoff</h2>
 * Mr X does not have "about 20" moves: with two double-move tickets and a
 * secret ticket he has up to <b>368</b> legal moves on the standard board (node
 * 67), while a nearly-out-of-tickets Mr X may have 1. Truncating is not an
 * option - Mr X must be able to pick <i>any</i> legal move - so the padded slot
 * count has to cover the worst case.
 *
 * <p>
 * A single global constant (always 384 slots, the board maximum) would leak
 * exactly zero bits. It is not usable, though: 384 padded lines is roughly 5 kB
 * of payload, and a QR code holds at most about 2.9 kB (version 40, byte mode,
 * lowest error correction) - and a 5 kB QR would be unscannable off a laptop
 * screen anyway. Encoding the full double-move table simply does not fit in one
 * QR code, at any line format: 368 moves cannot be described in fewer than
 * ~3 kB even with a minimal encoding.
 *
 * <p>
 * So the slot count is rounded <b>up to the next multiple of
 * {@value #SLOT_BUCKET}</b> (minimum {@value #SLOT_BUCKET}). The tradeoff,
 * stated honestly:
 * <ul>
 * <li>What still leaks: {@code ceil(moves / 128)}, which on the standard board
 * is 1, 2 or 3 - under 1.6 bits per turn, and it is 1 for the overwhelming
 * majority of real positions (any Mr X below 128 legal moves, i.e. most of the
 * game, especially once the double tickets are spent). Detectives learn "Mr X
 * has a huge number of options", never how many, and never which.</li>
 * <li>What no longer leaks: the exact move count, which is what actually
 * identifies a station.</li>
 * <li>What we buy: the payload for one bucket is {@code 128 * 13 = 1664}
 * characters plus a constant legend, which encodes into a QR code that a phone
 * can actually read. {@link PrivateMovePane} renders one QR code per bucket, so
 * the rare 2- or 3-bucket turn shows 2 or 3 codes - which leaks exactly the
 * same coarse bucket and nothing more.</li>
 * </ul>
 *
 * <p>
 * Payload lines are deliberately terse for the same capacity reason
 * ({@code "A =T128     "}, {@code "AB=S089>T128"}) and a constant-length legend
 * line explains the ticket letters, so Mr X can act on what he scanned without
 * a decoder ring. The legend is constant, so it leaks nothing.
 *
 * <p>
 * This class is pure logic: no JavaFX, no ZXing, so it is directly unit
 * testable.
 */
public final class PrivateMoveChannel {

	/** Slots are padded up to a multiple of this. See the class javadoc. */
	public static final int SLOT_BUCKET = 128;

	/** Every payload line, real or dummy, is exactly this wide (sans newline). */
	public static final int LINE_WIDTH = 12;

	/** Constant header; explains the compact codes. Leaks nothing (it never changes). */
	public static final String LEGEND =
			"KEY T=TAXI B=BUS U=UNDERGROUND S=SECRET, A>B = DOUBLE MOVE";

	private static final String DUMMY_BODY = "---------";
	private static final String DUMMY_LABEL = "--";

	private final Random rng;

	/**
	 * @param seed seed for the shuffling RNG; a fixed seed makes the offers
	 *             reproducible (used by the tests). In the real game, pass
	 *             something unpredictable, e.g. {@code new SecureRandom().nextLong()}
	 *             or {@code System.nanoTime()}.
	 */
	public PrivateMoveChannel(long seed) {
		this.rng = new Random(seed);
	}

	/**
	 * A shuffled, padded letter -> move mapping for exactly one turn.
	 *
	 * <p>
	 * Instances are immutable. A fresh one must be built for every turn - reusing
	 * an {@link Offer} would defeat defence (2).
	 */
	public static final class Offer {
		private final ImmutableList<String> labels;
		private final ImmutableMap<String, Move> moves;
		private final String payload;

		private Offer(ImmutableList<String> labels, ImmutableMap<String, Move> moves, String payload) {
			this.labels = labels;
			this.moves = moves;
			this.payload = payload;
		}

		/**
		 * @return every label the dropdown must show, padding included, in order.
		 *         The size depends only on the coarse bucket of the legal move
		 *         count, never on the count itself.
		 */
		@Nonnull
		public List<String> labels() {
			return labels;
		}

		/**
		 * @param label a label, presumably one of {@link #labels()}
		 * @return the move behind that label, or empty if the label is a padding
		 *         slot (or unknown). The UI must treat empty as "nothing to do".
		 */
		@Nonnull
		public Optional<Move> moveFor(String label) {
			return Optional.ofNullable(moves.get(label));
		}

		/**
		 * @return the text encoded into the QR code(s). Its length is constant for
		 *         a given bucket, whatever the real moves are.
		 */
		@Nonnull
		public String qrPayload() {
			return payload;
		}

		/** @return how many labels actually carry a move (never shown on screen). */
		public int realMoveCount() {
			return moves.size();
		}
	}

	/**
	 * Builds a fresh {@link Offer} for the given legal moves: pads the slots,
	 * reshuffles the letter -> move mapping, and renders a constant-length QR
	 * payload.
	 *
	 * @param moves the legal moves (typically {@code board.getAvailableMoves()});
	 *              every one of them is reachable through exactly one label
	 * @return the offer for this turn
	 */
	@Nonnull
	public Offer offer(ImmutableSet<Move> moves) {
		// deterministic starting order, so that "same seed => same mapping" holds
		// regardless of the iteration order of the incoming set
		List<Move> ordered = new ArrayList<>(moves);
		ordered.sort(Comparator.comparing(Move::toString));

		int slots = slotsFor(ordered.size());

		// defence (1): pad the option count to the bucket size with empty slots
		List<Move> padded = new ArrayList<>(slots);
		padded.addAll(ordered);
		while (padded.size() < slots) padded.add(null);

		// defence (2): reshuffle the whole mapping, every single turn
		Collections.shuffle(padded, rng);

		ImmutableList.Builder<String> labels = ImmutableList.builder();
		Map<String, Move> mapping = new LinkedHashMap<>();
		StringBuilder payload = new StringBuilder(LEGEND).append('\n');

		for (int i = 0; i < slots; i++) {
			String label = labelFor(i);
			labels.add(label);
			Move move = padded.get(i);
			if (move == null) {
				// defence (3): dummy line, same width as a real one
				payload.append(DUMMY_LABEL).append('=').append(DUMMY_BODY).append('\n');
			} else {
				mapping.put(label, move);
				payload.append(pad2(label)).append('=').append(body(move)).append('\n');
			}
		}
		return new Offer(labels.build(), ImmutableMap.copyOf(mapping), payload.toString());
	}

	/** @return the padded slot count for {@code moveCount} legal moves. */
	public static int slotsFor(int moveCount) {
		int buckets = Math.max(1, (moveCount + SLOT_BUCKET - 1) / SLOT_BUCKET);
		return buckets * SLOT_BUCKET;
	}

	/**
	 * @param index a zero-based slot index
	 * @return {@code "A".."Z"}, then {@code "AA".."ZZ"} - enough for any bucket we
	 *         can realistically show
	 */
	public static String labelFor(int index) {
		if (index < 26) return String.valueOf((char) ('A' + index));
		int i = index - 26;
		return String.valueOf(new char[] { (char) ('A' + i / 26), (char) ('A' + i % 26) });
	}

	/** Human-usable, fixed-width rendering of a move (see {@link #LEGEND}). */
	private static String body(Move move) {
		String s = move.visit(new Move.FunctionalVisitor<>(
				single -> code(single.ticket) + dest(single.destination),
				dbl -> code(dbl.ticket1) + dest(dbl.destination1)
						+ ">" + code(dbl.ticket2) + dest(dbl.destination2)));
		StringBuilder b = new StringBuilder(s);
		while (b.length() < DUMMY_BODY.length()) b.append(' ');
		return b.toString();
	}

	private static String pad2(String label) {
		return label.length() >= 2 ? label : label + " ";
	}

	private static String dest(int destination) {
		return String.format("%03d", destination);
	}

	private static char code(Ticket ticket) {
		switch (ticket) {
			case TAXI: return 'T';
			case BUS: return 'B';
			case UNDERGROUND: return 'U';
			case SECRET: return 'S';
			case DOUBLE: return 'D';
			default: throw new AssertionError("unknown ticket " + ticket);
		}
	}
}
