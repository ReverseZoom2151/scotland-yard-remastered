package uk.ac.bris.cs.scotlandyard.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Factory;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * Builds {@link GameState}s for a game of Scotland Yard.
 */
public final class MyGameStateFactory implements Factory<GameState> {

	/**
	 * An immutable snapshot of a game in progress.
	 *
	 * <p>
	 * Every derived value — the available moves, the winner, the set of pieces — is
	 * computed once in the constructor and cached. {@link #advance(Move)} builds a
	 * whole new state rather than mutating this one, so a search that explores many
	 * lines can hold on to any state it has seen.
	 */
	private static final class MyGameState implements GameState {

		private final GameSetup setup;
		private final ImmutableSet<Piece> remaining;
		private final ImmutableList<LogEntry> log;
		private final Player mrX;
		private final List<Player> detectives;

		private final ImmutableList<Player> everyone;
		private final ImmutableSet<Piece> pieces;
		private final ImmutableSet<Move> moves;
		private final ImmutableSet<Piece> winner;

		private MyGameState(GameSetup setup, ImmutableSet<Piece> remaining, ImmutableList<LogEntry> log,
				Player mrX, List<Player> detectives) {
			this.setup = setup;
			this.remaining = remaining;
			this.log = log;
			this.mrX = mrX;
			this.detectives = detectives;

			rejectOverlappingLocations();
			rejectDuplicateColours();

			this.everyone = ImmutableList.<Player>builder().addAll(detectives).add(mrX).build();
			this.pieces = this.everyone.stream()
					.map(Player::piece)
					.collect(ImmutableSet.toImmutableSet());

			// The winner is decided from the moves that would be available were the game
			// still running, so derive the moves first. Once someone has won there are no
			// available moves, which is what the Board contract requires.
			ImmutableSet<Move> candidateMoves = computeAvailableMoves();
			this.winner = computeWinner(candidateMoves);
			this.moves = this.winner.isEmpty() ? candidateMoves : ImmutableSet.of();
		}

		private void rejectOverlappingLocations() {
			Set<Integer> seen = new HashSet<>();
			for (Player d : detectives) {
				if (!seen.add(d.location())) {
					throw new IllegalArgumentException("Two detectives share location " + d.location());
				}
			}
		}

		private void rejectDuplicateColours() {
			Set<Piece> seen = new HashSet<>();
			for (Player d : detectives) {
				if (!seen.add(d.piece())) {
					throw new IllegalArgumentException("Duplicate detective colour " + d.piece());
				}
			}
		}

		@Nonnull
		@Override
		public GameSetup getSetup() {
			return setup;
		}

		@Nonnull
		@Override
		public ImmutableSet<Piece> getPlayers() {
			return pieces;
		}

		@Nonnull
		@Override
		public Optional<Integer> getDetectiveLocation(Piece.Detective detective) {
			return detectives.stream()
					.filter(d -> d.piece().equals(detective))
					.map(Player::location)
					.findFirst();
		}

		@Nonnull
		@Override
		public Optional<TicketBoard> getPlayerTickets(Piece piece) {
			return everyone.stream()
					.filter(p -> p.piece().equals(piece))
					.findFirst()
					.map(p -> ticket -> p.tickets().get(ticket));
		}

		@Nonnull
		@Override
		public ImmutableList<LogEntry> getMrXTravelLog() {
			return log;
		}

		@Nonnull
		@Override
		public ImmutableSet<Piece> getWinner() {
			return winner;
		}

		@Nonnull
		@Override
		public ImmutableSet<Move> getAvailableMoves() {
			return moves;
		}

		/**
		 * @param candidateMoves the moves available to whoever is due to play
		 * @return the winning pieces, or an empty set while the game is still running
		 */
		private ImmutableSet<Piece> computeWinner(ImmutableSet<Move> candidateMoves) {
			boolean mrXToPlay = remaining.contains(mrX.piece());

			// MrX survived every round in the travel log.
			if (mrXToPlay && setup.rounds.size() <= log.size()) {
				return ImmutableSet.of(mrX.piece());
			}

			boolean captured = detectives.stream().anyMatch(d -> d.location() == mrX.location());
			if (captured || (candidateMoves.isEmpty() && mrXToPlay)) {
				return detectives.stream().map(Player::piece).collect(ImmutableSet.toImmutableSet());
			}

			// A detective is out of the game once it has no legal move. That is weaker
			// than holding no tickets: it can hold a full hand and still be boxed in by
			// its own teammates.
			boolean detectivesCanMove = detectives.stream()
					.anyMatch(d -> !makeSingleMoves(setup, d, d.location(), detectives).isEmpty());
			if (!detectivesCanMove || (candidateMoves.isEmpty() && !mrXToPlay)) {
				return ImmutableSet.of(mrX.piece());
			}

			return ImmutableSet.of();
		}

		private ImmutableSet<Move> computeAvailableMoves() {
			Set<Move> available = new HashSet<>();
			if (remaining.contains(mrX.piece())) {
				// A double move spans two rounds, so it needs two left in the log.
				if (mrX.hasAtLeast(Ticket.DOUBLE, 1) && log.size() <= setup.rounds.size() - 2) {
					available.addAll(makeDoubleMoves(setup, mrX, mrX.location(), detectives));
				}
				available.addAll(makeSingleMoves(setup, mrX, mrX.location(), detectives));
			} else {
				for (Player d : detectives) {
					if (remaining.contains(d.piece())) {
						available.addAll(makeSingleMoves(setup, d, d.location(), detectives));
					}
				}
			}
			return ImmutableSet.copyOf(available);
		}

		@Nonnull
		@Override
		public GameState advance(Move move) {
			if (!moves.contains(move)) {
				throw new IllegalArgumentException("Illegal move: " + move);
			}
			return move.commencedBy().isMrX() ? advanceMrX(move) : advanceDetective(move);
		}

		private GameState advanceMrX(Move move) {
			List<Integer> destinations = destinationsOf(move);
			List<Ticket> tickets = ImmutableList.copyOf(move.tickets());

			Player movedMrX = mrX.at(destinations.get(destinations.size() - 1)).use(move.tickets());

			// Each leg is logged against its own round, so a double move straddling a
			// reveal round is hidden for one leg and revealed for the other.
			List<LogEntry> entries = new ArrayList<>(log);
			for (int leg = 0; leg < destinations.size(); leg++) {
				Ticket ticket = tickets.get(leg);
				int destination = destinations.get(leg);
				entries.add(setup.rounds.get(entries.size())
						? LogEntry.reveal(ticket, destination)
						: LogEntry.hidden(ticket));
			}

			Set<Piece> next = new HashSet<>();
			for (Player d : detectives) {
				if (!makeSingleMoves(setup, d, d.location(), detectives).isEmpty()) {
					next.add(d.piece());
				}
			}
			if (next.isEmpty()) {
				next.add(movedMrX.piece());
			}

			return new MyGameState(setup, ImmutableSet.copyOf(next), ImmutableList.copyOf(entries),
					movedMrX, detectives);
		}

		private GameState advanceDetective(Move move) {
			Piece mover = move.commencedBy();
			int destination = destinationsOf(move).get(0);

			// A detective hands the ticket it spends to MrX.
			Player movedMrX = mrX.give(move.tickets());
			List<Player> movedDetectives = new ArrayList<>(detectives.size());
			for (Player d : detectives) {
				movedDetectives.add(d.piece().equals(mover) ? d.at(destination).use(move.tickets()) : d);
			}

			Set<Piece> next = new HashSet<>();
			for (Piece p : remaining) {
				if (!p.equals(mover)) {
					next.add(p);
				}
			}
			// This move may have boxed in a teammate that still had somewhere to go at
			// the start of the rotation.
			next.removeIf(p -> movedDetectives.stream()
					.filter(d -> d.piece().equals(p))
					.findFirst()
					.map(d -> makeSingleMoves(setup, d, d.location(), movedDetectives).isEmpty())
					.orElse(true));
			if (next.isEmpty()) {
				next.add(mrX.piece());
			}

			return new MyGameState(setup, ImmutableSet.copyOf(next), log, movedMrX, movedDetectives);
		}

		/** @return the destination of each leg, in order. */
		private static List<Integer> destinationsOf(Move move) {
			return move.visit(new Move.FunctionalVisitor<>(
					single -> List.of(single.destination),
					doubleMove -> List.of(doubleMove.destination1, doubleMove.destination2)));
		}
	}

	/**
	 * @return every legal one-step move for {@code player} from {@code source}:
	 *         one per transport it holds the ticket for, plus a secret move over any
	 *         edge at all. Destinations held by another detective are excluded.
	 */
	private static Set<Move.SingleMove> makeSingleMoves(GameSetup setup, Player player, int source,
			List<Player> detectives) {
		Set<Integer> occupied = new HashSet<>();
		for (Player d : detectives) {
			if (!d.piece().equals(player.piece())) {
				occupied.add(d.location());
			}
		}

		Set<Move.SingleMove> singleMoves = new HashSet<>();
		boolean hasSecret = player.hasAtLeast(Ticket.SECRET, 1);
		for (int destination : setup.graph.adjacentNodes(source)) {
			if (occupied.contains(destination)) {
				continue;
			}
			Set<Transport> transports = setup.graph.edgeValueOrDefault(source, destination, ImmutableSet.of());
			for (Transport t : transports) {
				if (player.hasAtLeast(t.requiredTicket(), 1)) {
					singleMoves.add(new Move.SingleMove(player.piece(), source, t.requiredTicket(), destination));
				}
			}
			// A secret ticket covers any transport, ferries included.
			if (hasSecret && !transports.isEmpty()) {
				singleMoves.add(new Move.SingleMove(player.piece(), source, Ticket.SECRET, destination));
			}
		}
		return singleMoves;
	}

	/**
	 * @return every legal two-step move, with the first leg's ticket already spent
	 *         when the second leg is costed.
	 */
	private static Set<Move.DoubleMove> makeDoubleMoves(GameSetup setup, Player player, int source,
			List<Player> detectives) {
		Set<Move.DoubleMove> doubleMoves = new HashSet<>();
		for (Move.SingleMove first : makeSingleMoves(setup, player, source, detectives)) {
			for (Move.SingleMove second : makeSingleMoves(setup, player, first.destination, detectives)) {
				// Reusing the same ticket for both legs takes two of them.
				if (second.ticket == first.ticket && !player.hasAtLeast(first.ticket, 2)) {
					continue;
				}
				doubleMoves.add(new Move.DoubleMove(player.piece(), source,
						first.ticket, first.destination,
						second.ticket, second.destination));
			}
		}
		return doubleMoves;
	}

	@Nonnull
	@Override
	public GameState build(GameSetup setup, Player mrX, ImmutableList<Player> detectives) {
		Objects.requireNonNull(mrX, "MrX is missing");
		Objects.requireNonNull(detectives, "detectives are missing");
		if (setup.rounds.isEmpty()) {
			throw new IllegalArgumentException("The game needs at least one round");
		}
		if (setup.graph.nodes().isEmpty()) {
			throw new IllegalArgumentException("The graph is empty");
		}
		if (!mrX.isMrX()) {
			throw new IllegalArgumentException("The MrX slot holds " + mrX.piece());
		}
		for (Player d : detectives) {
			Objects.requireNonNull(d, "A detective is missing");
			if (d.isMrX()) {
				throw new IllegalArgumentException("A detective slot holds MrX");
			}
			if (d.has(Ticket.DOUBLE)) {
				throw new IllegalArgumentException(d.piece() + " holds a double ticket");
			}
			if (d.has(Ticket.SECRET)) {
				throw new IllegalArgumentException(d.piece() + " holds a secret ticket");
			}
		}
		return new MyGameState(setup, ImmutableSet.of(Piece.MrX.MRX), ImmutableList.of(), mrX, detectives);
	}
}
