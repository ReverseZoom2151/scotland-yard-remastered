package uk.ac.bris.cs.scotlandyard.model;

import com.google.common.collect.ImmutableList;
import javax.annotation.Nonnull;
import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Factory;

import java.util.Optional;
import java.util.*;
import java.util.stream.Collectors;

/**
 * cw-model
 * Stage 1: Complete this class
 */

public final class MyGameStateFactory implements Factory<GameState> {
	private final class MyGameState implements GameState {
		private GameSetup setup;
		private Player mrX;
		private ImmutableList<LogEntry> log;
		private ImmutableList<Player> everyone;
		private ImmutableSet<Move> moves;
		private ImmutableSet<Piece> winner;
		private ImmutableSet<Piece> remaining;
		public List<Player> detectives;

		private MyGameState(final GameSetup setup, final ImmutableSet<Piece> remaining,
				final ImmutableList<LogEntry> log, final Player mrX, final List<Player> detectives) {
			this.setup = setup;
			this.remaining = remaining;
			this.log = log;
			this.mrX = mrX;
			this.detectives = detectives;
			locationOverlap();
			duplicateColour();
			this.everyone = ImmutableList.<Player>builder()
					.addAll(detectives)
					.add(mrX)
					.build();
			this.winner = ImmutableSet.of();
			this.moves = getAvailableMoves();
			this.winner = getWinner();
		}

		private void locationOverlap() {
			for (int i = 0; i < this.detectives.size() - 1; i++) {
				for (int j = i + 1; j < this.detectives.size(); j++) {
					if ((this.detectives.get(i).location()) == (this.detectives.get(j).location())) {
						throw new IllegalArgumentException("Two Overlapped Locations");
					}
				}
			}
		}

		private void duplicateColour() {
			for (int i = 0; i < this.detectives.size() - 1; i++) {
				for (int j = i + 1; j < this.detectives.size(); j++) {
					if ((this.detectives.get(i).piece()) == (this.detectives.get(j).piece())) {
						throw new IllegalArgumentException("A Duplicated Colour");
					}
				}
			}
		}

		@Nonnull
		@Override
		public GameSetup getSetup() {
			return this.setup;
		}

		@Nonnull
		@Override
		public ImmutableSet<Piece> getPlayers() {
			Set<Piece> pieceList = new HashSet<>();
			for (Player p : this.everyone) {
				pieceList.add(p.piece());
			}
			Set<Piece> pieceSet = ImmutableSet.copyOf(pieceList);
			return (ImmutableSet<Piece>) pieceSet;
		}

		@Nonnull
		@Override
		public Optional<Integer> getDetectiveLocation(Piece.Detective detective) {
			for (Player d : this.detectives) {
				if (d.piece().equals(detective)) {
					return Optional.of(d.location());
				}
			}
			return Optional.empty();
		}

		@Nonnull
		@Override
		public Optional<TicketBoard> getPlayerTickets(Piece piece) {
			for (Player p : this.everyone) {
				if (p.piece().equals(piece)) {
					return Optional.of(new TicketBoard() {
						@Override
						public int getCount(@Nonnull ScotlandYard.Ticket ticket) {
							return p.tickets().get(ticket);
						}
					});
				}
			}
			return Optional.empty();
		}

		@Nonnull
		@Override
		public ImmutableList<LogEntry> getMrXTravelLog() {
			return this.log;
		}

		@Nonnull
		@Override
		public ImmutableSet<Piece> getWinner() {
			Set<Piece> pieceSet = new HashSet<>();
			boolean mrXLoses = false;
			if ((this.remaining.contains(this.mrX.piece()) == true) && (this.setup.rounds.size() <= this.log.size())) {
				return ImmutableSet.of(this.mrX.piece());
			}
			for (Player d : this.detectives)
				if (this.mrX.location() == d.location()) {
					mrXLoses = true;
				}
			if ((mrXLoses == true)
					|| (this.moves.isEmpty() == true) && (this.remaining.contains(this.mrX.piece()) == true)) {
				for (Player d : this.detectives) {
					pieceSet.add(d.piece());
				}
				return ImmutableSet.copyOf(pieceSet);
			}
			// A detective is out of the game when it has no legal move, which is
			// strictly weaker than having no tickets: it may hold tickets and still
			// be boxed in by its teammates.
			boolean detectivesCanMove = false;
			for (Player d : this.detectives) {
				if (!makeSingleMoves(this.setup, d, d.location(), this.detectives).isEmpty()) {
					detectivesCanMove = true;
				}
			}
			if ((detectivesCanMove == false)
					|| (this.moves.isEmpty() == true) && (this.remaining.contains(this.mrX.piece()) == false)) {
				return ImmutableSet.of(this.mrX.piece());
			}
			return ImmutableSet.of();
		}

		@Nonnull
		@Override
		public GameState advance(Move move) {
			Piece thatPlayer = move.commencedBy();
			Set<Piece> remnant = new HashSet<>();
			List<LogEntry> logEntryList = new ArrayList<>();
			List<Player> playerList = new ArrayList<>();
			logEntryList.addAll(getMrXTravelLog());
			if (this.moves.contains(move) == false) {
				throw new IllegalArgumentException("Illegal move: " + move);
			}
			List<Integer> finalDestination = move.visit(new Move.Visitor<List<Integer>>() {
				@Override
				public List<Integer> visit(Move.SingleMove singleMove) {
					List<Integer> finalDestination = new ArrayList<>();
					finalDestination.add(singleMove.destination);
					return finalDestination;
				}

				@Override
				public List<Integer> visit(Move.DoubleMove doubleMove) {
					List<Integer> finalDestination = new ArrayList<>();
					finalDestination.add(doubleMove.destination1);
					finalDestination.add(doubleMove.destination2);
					return finalDestination;
				}
			});
			int primaryDestination = finalDestination.iterator().next(), secondaryDestination = -1;
			if ((finalDestination.size() > 1) && (thatPlayer.isMrX() == true)) {
				int lastElement = -1;
				for (int last : finalDestination) {
					lastElement = last;
				}
				secondaryDestination = lastElement;
			}
			Player mrX2 = this.mrX;
			if (thatPlayer.isMrX() == true) {
				if (secondaryDestination == -1) {
					mrX2 = this.mrX.at(primaryDestination);
				} else {
					mrX2 = this.mrX.at(secondaryDestination);
				}
				mrX2 = mrX2.use(move.tickets());
				ScotlandYard.Ticket lastTicket = null;
				if (secondaryDestination != -1) {
					int k = 0;
					for (ScotlandYard.Ticket ticket : move.tickets()) {
						k++;
						lastTicket = ticket;
						if (k == 2) {
							break;
						}
					}
				}
				LogEntry a, b;
				if (this.setup.rounds.get(this.log.size()) == true) {
					a = LogEntry.reveal(move.tickets().iterator().next(), primaryDestination);
				} else {
					a = LogEntry.hidden(move.tickets().iterator().next());
				}
				logEntryList.add(a);
				if (lastTicket != null) {
					if (this.setup.rounds.get(this.log.size() + 1) == true) {
						b = LogEntry.reveal(lastTicket, secondaryDestination);
					} else {
						b = LogEntry.hidden(lastTicket);
					}
					logEntryList.add(b);
				}
				for (Player d : detectives) {
					if (!makeSingleMoves(this.setup, d, d.location(), this.detectives).isEmpty()) {
						remnant.add(d.piece());
					}
					playerList.add(d);
				}
				if (remnant.isEmpty()) {
					remnant.add(mrX2.piece());
				}
			} else {
				for (Player d : detectives) {
					if (d.piece() == thatPlayer) {
						d = d.at(primaryDestination);
						d = d.use(move.tickets());
						playerList.add(d);
						mrX2 = this.mrX.give(move.tickets());
						for (var i : this.remaining) {
							if (i == thatPlayer) {
								continue;
							}
							remnant.add(i);
						}
					} else {
						playerList.add(d);
					}
				}
				// A teammate moving may have boxed in a detective that still had a
				// move at the start of the rotation; drop anyone now immobile.
				remnant.removeIf(p -> playerList.stream()
						.filter(pl -> pl.piece().equals(p))
						.findFirst()
						.map(pl -> makeSingleMoves(this.setup, pl, pl.location(), playerList).isEmpty())
						.orElse(true));
				if (remnant.isEmpty() == true) {
					remnant.add(this.mrX.piece());
				}
			}
			ImmutableSet<Piece> finalRemnant = ImmutableSet.copyOf(remnant);
			ImmutableList<LogEntry> finalLogEntryList = ImmutableList.copyOf(logEntryList);
			return new MyGameState(getSetup(), finalRemnant, finalLogEntryList, mrX2, playerList);
		}

		@Nonnull
		@Override
		public ImmutableSet<Move> getAvailableMoves() {
			if (this.winner.isEmpty() == false) {
				return ImmutableSet.<Move>builder().build();
			}
			Set<Move> moveSet = new HashSet<>();
			if (this.remaining.contains(this.mrX.piece()) == false) {
				for (Player d : this.detectives) {
					if (this.remaining.contains(d.piece()) == true) {
						moveSet.addAll(makeSingleMoves(this.setup, d, d.location(), this.detectives));
					}
				}
			} else {
				if ((this.mrX.hasAtLeast(ScotlandYard.Ticket.DOUBLE, 1) == true)
						&& (getMrXTravelLog().size() <= this.setup.rounds.size() - 2)) {
					moveSet.addAll(makeDoubleMove(this.setup, this.mrX, this.mrX.location(), this.detectives));
				}
				moveSet.addAll(makeSingleMoves(this.setup, this.mrX, this.mrX.location(), this.detectives));
			}
			ImmutableSet<Move> setFinal = ImmutableSet.copyOf(moveSet);
			return setFinal;
		}
	}

	private static Set<Move.SingleMove> makeSingleMoves(GameSetup setup, Player player, int source,
			List<Player> detectives) {
		final var singleMoves = new ArrayList<Move.SingleMove>();
		for (int primaryDestination : setup.graph.adjacentNodes(source)) {
			boolean taken = false;
			for (Player d : detectives) {
				if ((d.location() == primaryDestination) && (d != player)) {
					taken = true;
				}
			}
			if (taken == true) {
				continue;
			}
			for (ScotlandYard.Transport t : setup.graph.edgeValueOrDefault(source, primaryDestination,
					ImmutableSet.of())) {
				if (player.hasAtLeast(t.requiredTicket(), 1) == true) {
					singleMoves
							.add(new Move.SingleMove(player.piece(), source, t.requiredTicket(), primaryDestination));
				}
			}
			if (player.hasAtLeast(ScotlandYard.Ticket.SECRET, 1) == true) {
				for (ScotlandYard.Transport t : setup.graph.edgeValueOrDefault(source, primaryDestination,
						ImmutableSet.of())) {
					singleMoves
							.add(new Move.SingleMove(player.piece(), source, ScotlandYard.Ticket.SECRET,
									primaryDestination));
				}
			}
		}
		return ImmutableSet.copyOf(singleMoves);
	}

	private static Set<Move.DoubleMove> makeDoubleMove(GameSetup setup, Player player, int source,
			List<Player> detectives) {
		Set<Move.SingleMove> x = makeSingleMoves(setup, player, source, detectives);
		Set<Move.DoubleMove> doubleMoves = new HashSet<>();
		for (Move.SingleMove primaryMove : x) {
			Set<Move.SingleMove> y = makeSingleMoves(setup, player, primaryMove.destination, detectives);
			ScotlandYard.Ticket theFirstTicket = primaryMove.ticket;
			for (Move.SingleMove secondaryMove : y) {
				if ((secondaryMove.ticket == theFirstTicket) && (player.hasAtLeast(theFirstTicket, 2) == false)) {
					continue;
				}
				Move.DoubleMove ticket = new Move.DoubleMove(player.piece(), source, primaryMove.ticket,
						primaryMove.destination, secondaryMove.ticket, secondaryMove.destination);
				doubleMoves.add(ticket);
			}
		}
		return ImmutableSet.copyOf(doubleMoves);
	}

	@Nonnull
	@Override
	public GameState build(GameSetup setup, Player mrX, ImmutableList<Player> detectives) {
		if (setup.rounds.isEmpty() == true) {
			throw new IllegalArgumentException("Zero Rounds! Fatal Error!");
		}
		if (setup.graph.nodes().isEmpty() == true) {
			throw new IllegalArgumentException("Empty Graph! Fatal Error!");
		}
		if (mrX.equals(null)) {
			throw new NullPointerException("mrX is Missing in Action (MIA)! Fatal Error!");
		}
		if (mrX.isMrX() == false) {
			throw new IllegalArgumentException("mrX is Missing a Ticket! Fatal Error!");
		}
		for (Player d : detectives) {
			if (d.equals(null)) {
				throw new NullPointerException("Detective is Missing in Action (MIA)! Fatal Error!");
			}
			if (d.isMrX() == true) {
				throw new IllegalArgumentException("Detective Equals mrX! Fatal Error!");
			}
			if (d.has(ScotlandYard.Ticket.DOUBLE) == true) {
				throw new IllegalArgumentException("Detective Can't Have a Double Ticket! Fatal Error!");
			}
			if (d.has(ScotlandYard.Ticket.SECRET) == true) {
				throw new IllegalArgumentException("Detective Can't Have a Secret Ticket! Fatal Error!");
			}
		}
		return new MyGameState(setup, ImmutableSet.of(Piece.MrX.MRX), ImmutableList.of(), mrX, detectives);
	}
}
