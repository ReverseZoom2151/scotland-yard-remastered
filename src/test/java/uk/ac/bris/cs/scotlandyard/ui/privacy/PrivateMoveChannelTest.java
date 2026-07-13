package uk.ac.bris.cs.scotlandyard.ui.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket.BUS;
import static uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket.DOUBLE;
import static uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket.SECRET;
import static uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket.TAXI;
import static uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket.UNDERGROUND;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.ui.privacy.PrivateMoveChannel.Offer;

/**
 * Tests the anti-side-channel properties of {@link PrivateMoveChannel} against
 * real move sets taken from a real game state.
 */
public class PrivateMoveChannelTest {

	private static GameSetup setup;

	@BeforeClass
	public static void loadGraph() throws IOException {
		setup = new GameSetup(ScotlandYard.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
	}

	// -- helpers ------------------------------------------------------------

	private static ImmutableMap<Ticket, Integer> tickets(int taxi, int bus, int underground, int dbl, int secret) {
		Map<Ticket, Integer> t = new HashMap<>();
		t.put(TAXI, taxi);
		t.put(BUS, bus);
		t.put(UNDERGROUND, underground);
		t.put(DOUBLE, dbl);
		t.put(SECRET, secret);
		return ImmutableMap.copyOf(t);
	}

	private static ImmutableSet<Move> movesFor(int mrXLocation, ImmutableMap<Ticket, Integer> mrXTickets) {
		Player mrX = new Player(Piece.MrX.MRX, mrXTickets, mrXLocation);
		Player red = new Player(Piece.Detective.RED, tickets(11, 8, 4, 0, 0), mrXLocation == 26 ? 29 : 26);
		Board board = new MyGameStateFactory().build(setup, mrX, ImmutableList.of(red));
		return board.getAvailableMoves();
	}

	/** A poorly connected Mr X down to a single taxi ticket: very few legal moves. */
	private static ImmutableSet<Move> fewMoves() {
		return movesFor(103, tickets(1, 0, 0, 0, 0));
	}

	/** A well connected Mr X with a full hand: an order of magnitude more moves. */
	private static ImmutableSet<Move> manyMoves() {
		return movesFor(67, tickets(4, 3, 3, 0, 5));
	}

	// -- tests --------------------------------------------------------------

	@Test
	public void testEveryLegalMoveReachableThroughExactlyOneLabel() {
		ImmutableSet<Move> moves = manyMoves();
		Offer offer = new PrivateMoveChannel(42).offer(moves);

		Set<Move> seen = new HashSet<>();
		int carried = 0;
		for (String label : offer.labels()) {
			Optional<Move> move = offer.moveFor(label);
			if (move.isPresent()) {
				carried++;
				seen.add(move.get());
			}
		}
		assertThat(carried).isEqualTo(moves.size()); // none duplicated
		assertThat(seen).isEqualTo(moves); // none lost, nothing invented
	}

	@Test
	public void testPaddingSlotsCarryNoMove() {
		ImmutableSet<Move> moves = fewMoves();
		Offer offer = new PrivateMoveChannel(7).offer(moves);

		List<String> labels = offer.labels();
		long empty = labels.stream().filter(l -> offer.moveFor(l).isEmpty()).count();
		assertThat(moves.size()).isLessThan(labels.size());
		assertThat(empty).isEqualTo(labels.size() - moves.size());
		assertThat(offer.moveFor("nonsense")).isEmpty();
	}

	/** THE anti-leak property: the dropdown size must not betray the branching factor. */
	@Test
	public void testLabelCountConstantAcrossVeryDifferentPositions() {
		ImmutableSet<Move> few = fewMoves();
		ImmutableSet<Move> many = manyMoves();
		// the two positions really are very different, otherwise the test is vacuous
		assertThat(few.size()).isLessThan(5);
		assertThat(many.size()).isGreaterThan(15);

		PrivateMoveChannel channel = new PrivateMoveChannel(1);
		assertThat(channel.offer(few).labels()).hasSameSizeAs(channel.offer(many).labels());
		assertThat(channel.offer(few).labels()).hasSize(PrivateMoveChannel.SLOT_BUCKET);
	}

	/** The QR image must not betray it either: identical payload length. */
	@Test
	public void testPayloadLengthConstantAcrossVeryDifferentPositions() {
		PrivateMoveChannel channel = new PrivateMoveChannel(1);
		String small = channel.offer(fewMoves()).qrPayload();
		String big = channel.offer(manyMoves()).qrPayload();
		assertThat(small.length()).isEqualTo(big.length());
		// legend + one fixed-width line per slot
		assertThat(small.length()).isEqualTo(
				PrivateMoveChannel.LEGEND.length() + 1
						+ PrivateMoveChannel.SLOT_BUCKET * (PrivateMoveChannel.LINE_WIDTH + 1));
		for (String line : big.split("\n", -1)) {
			if (line.isEmpty() || line.equals(PrivateMoveChannel.LEGEND)) continue;
			assertThat(line).hasSize(PrivateMoveChannel.LINE_WIDTH);
		}
	}

	@Test
	public void testMappingReshufflesWithDifferentSeedButIsDeterministicPerSeed() {
		ImmutableSet<Move> moves = manyMoves();
		Offer a = new PrivateMoveChannel(1).offer(moves);
		Offer b = new PrivateMoveChannel(1).offer(moves);
		Offer c = new PrivateMoveChannel(2).offer(moves);

		assertThat(a.qrPayload()).isEqualTo(b.qrPayload()); // deterministic per seed
		assertThat(a.qrPayload()).isNotEqualTo(c.qrPayload()); // reshuffled otherwise
		assertThat(mappingOf(a)).isEqualTo(mappingOf(b));
		assertThat(mappingOf(a)).isNotEqualTo(mappingOf(c));
	}

	/** Consecutive turns must reshuffle too, or "A" would carry information over time. */
	@Test
	public void testConsecutiveOffersReshuffle() {
		ImmutableSet<Move> moves = manyMoves();
		PrivateMoveChannel channel = new PrivateMoveChannel(99);
		assertThat(mappingOf(channel.offer(moves))).isNotEqualTo(mappingOf(channel.offer(moves)));
	}

	/**
	 * The one honest leak: an Mr X with doubles at the best connected station has
	 * 368 legal moves, which cannot fit in a single bucket. It must still be able
	 * to offer all of them (no truncation), at the cost of revealing the bucket.
	 */
	@Test
	public void testHugeMoveSetIsNotTruncatedAndOnlyLeaksItsBucket() {
		ImmutableSet<Move> huge = movesFor(67, tickets(4, 3, 3, 2, 5));
		assertThat(huge.size()).isGreaterThan(PrivateMoveChannel.SLOT_BUCKET);

		Offer offer = new PrivateMoveChannel(5).offer(huge);
		assertThat(offer.realMoveCount()).isEqualTo(huge.size()); // nothing truncated
		assertThat(offer.labels()).hasSize(PrivateMoveChannel.slotsFor(huge.size()));
		assertThat(offer.labels().size() % PrivateMoveChannel.SLOT_BUCKET).isZero();

		Set<Move> seen = new HashSet<>();
		for (String label : offer.labels()) offer.moveFor(label).ifPresent(seen::add);
		assertThat(seen).isEqualTo(huge);
		assertThat(offer.labels()).doesNotHaveDuplicates();
	}

	@Test
	public void testPayloadRendersMovesReadably() {
		Offer offer = new PrivateMoveChannel(3).offer(manyMoves());
		String payload = offer.qrPayload();
		assertThat(payload).startsWith(PrivateMoveChannel.LEGEND);
		for (String label : offer.labels()) {
			Move move = offer.moveFor(label).orElse(null);
			if (move == null) continue;
			String expected = move.visit(new Move.FunctionalVisitor<>(
					single -> String.format("%s%03d", single.ticket.name().charAt(0), single.destination),
					dbl -> String.format("%s%03d>%s%03d",
							dbl.ticket1 == SECRET ? "S" : dbl.ticket1.name().charAt(0), dbl.destination1,
							dbl.ticket2 == SECRET ? "S" : dbl.ticket2.name().charAt(0), dbl.destination2)));
			assertThat(payload).contains((label.length() == 1 ? label + " " : label) + "=" + expected);
		}
	}

	private static Map<String, Move> mappingOf(Offer offer) {
		Map<String, Move> map = new HashMap<>();
		for (String label : offer.labels()) offer.moveFor(label).ifPresent(m -> map.put(label, m));
		return map;
	}
}
