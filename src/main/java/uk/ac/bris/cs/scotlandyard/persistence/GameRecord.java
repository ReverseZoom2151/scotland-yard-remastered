package uk.ac.bris.cs.scotlandyard.persistence;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;

import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Transport;

/**
 * An immutable, serialisable description of a whole game of Scotland Yard.
 *
 * <p>
 * The model is immutable and every state is derived, so a game is fully
 * described by its seed: the reveal rounds, the players it started with, and the
 * moves played since. {@link #replay()} folds the moves back over the initial
 * state, which is all that save/load, replay and undo need.
 *
 * <p>
 * The graph is deliberately <em>not</em> serialised; the standard 199 node map
 * is reloaded from the classpath on replay.
 */
public final class GameRecord {

	/** Bumped whenever the on-disk shape changes. */
	public static final int FORMAT_VERSION = 1;

	/** A player as written to disk: piece name, location and ticket counts. */
	static final class PlayerEntry {
		private String piece;
		private int location;
		private Map<String, Integer> tickets;

		private PlayerEntry(String piece, int location, Map<String, Integer> tickets) {
			this.piece = piece;
			this.location = location;
			this.tickets = tickets;
		}

		static PlayerEntry of(Player player) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (Ticket ticket : Ticket.values()) {
				counts.put(ticket.name(), player.tickets().getOrDefault(ticket, 0));
			}
			return new PlayerEntry(player.piece().toString(), player.location(), counts);
		}

		Player asPlayer() {
			Map<Ticket, Integer> counts = new EnumMap<>(Ticket.class);
			if (tickets != null) {
				tickets.forEach((name, count) -> counts.put(parseTicket(name), count == null ? 0 : count));
			}
			return new Player(parsePiece(piece), ImmutableMap.copyOf(counts), location);
		}
	}

	/**
	 * A move as written to disk. Deliberately a flat DTO rather than the
	 * {@link Move} interface: serialising the polymorphic Guava-backed
	 * implementations directly is fragile, whereas this rebuilds by matching
	 * against {@link GameState#getAvailableMoves()}, which also validates the save.
	 */
	static final class MoveEntry {
		private String commencedBy;
		private int source;
		private List<String> tickets;
		private List<Integer> destinations;

		private MoveEntry(String commencedBy, int source, List<String> tickets, List<Integer> destinations) {
			this.commencedBy = commencedBy;
			this.source = source;
			this.tickets = tickets;
			this.destinations = destinations;
		}

		static MoveEntry of(Move move) {
			List<String> usedTickets = new ArrayList<>();
			for (Ticket ticket : move.tickets()) {
				usedTickets.add(ticket.name());
			}
			List<Integer> to = move.visit(new Move.FunctionalVisitor<>(
					single -> List.of(single.destination),
					doubleMove -> List.of(doubleMove.destination1, doubleMove.destination2)));
			return new MoveEntry(move.commencedBy().toString(), move.source(), usedTickets, new ArrayList<>(to));
		}

		private boolean describes(Move move) {
			MoveEntry other = of(move);
			return Objects.equals(commencedBy, other.commencedBy)
					&& source == other.source
					&& Objects.equals(tickets, other.tickets)
					&& Objects.equals(destinations, other.destinations);
		}

		/** @return the one available move this entry describes */
		Move resolve(GameState state) {
			List<Move> matches = new ArrayList<>();
			for (Move move : state.getAvailableMoves()) {
				if (describes(move)) {
					matches.add(move);
				}
			}
			if (matches.size() != 1) {
				throw new IllegalStateException("Cannot replay " + this + ": expected exactly one matching "
						+ "available move but found " + matches.size()
						+ ". The save is corrupt or was made with a different version of the game.");
			}
			return matches.get(0);
		}

		@Override
		public String toString() {
			return commencedBy + "@" + source + " " + tickets + " -> " + destinations;
		}
	}

	private int version;
	private List<Boolean> rounds;
	private PlayerEntry mrX;
	private List<PlayerEntry> detectives;
	private List<MoveEntry> moves;

	private GameRecord(int version, List<Boolean> rounds, PlayerEntry mrX,
			List<PlayerEntry> detectives, List<MoveEntry> moves) {
		this.version = version;
		this.rounds = rounds;
		this.mrX = mrX;
		this.detectives = detectives;
		this.moves = moves;
	}

	/**
	 * @param setup      the setup the game started with; only the rounds are kept
	 * @param mrX        MrX as he was at the start of the game
	 * @param detectives the detectives as they were at the start of the game
	 * @param moves      every move played since, in order
	 * @return the record
	 */
	@Nonnull
	public static GameRecord of(@Nonnull GameSetup setup, @Nonnull Player mrX,
			@Nonnull List<Player> detectives, @Nonnull List<Move> moves) {
		Objects.requireNonNull(setup, "setup");
		Objects.requireNonNull(mrX, "mrX");
		Objects.requireNonNull(detectives, "detectives");
		Objects.requireNonNull(moves, "moves");
		List<PlayerEntry> detectiveEntries = new ArrayList<>();
		for (Player detective : detectives) {
			detectiveEntries.add(PlayerEntry.of(detective));
		}
		List<MoveEntry> moveEntries = new ArrayList<>();
		for (Move move : moves) {
			moveEntries.add(MoveEntry.of(move));
		}
		return new GameRecord(FORMAT_VERSION, new ArrayList<>(setup.rounds),
				PlayerEntry.of(mrX), detectiveEntries, moveEntries);
	}

	/** @throws IllegalStateException if the record did not survive deserialisation */
	void validate() {
		if (rounds == null || rounds.isEmpty()) {
			throw new IllegalStateException("The save has no rounds");
		}
		if (mrX == null) {
			throw new IllegalStateException("The save has no MrX");
		}
		if (detectives == null || detectives.isEmpty()) {
			throw new IllegalStateException("The save has no detectives");
		}
		if (moves == null) {
			throw new IllegalStateException("The save has no move list");
		}
		if (version > FORMAT_VERSION) {
			throw new IllegalStateException("The save is version " + version
					+ " but this game only understands up to version " + FORMAT_VERSION);
		}
	}

	/** @return the format version this record was written with */
	public int version() {
		return version;
	}

	/** @return the reveal rounds; true is a reveal round */
	@Nonnull
	public ImmutableList<Boolean> rounds() {
		return ImmutableList.copyOf(rounds);
	}

	/** @return MrX as he was at the start of the game */
	@Nonnull
	public Player mrX() {
		return mrX.asPlayer();
	}

	/** @return the detectives as they were at the start of the game */
	@Nonnull
	public ImmutableList<Player> detectives() {
		return detectives.stream().map(PlayerEntry::asPlayer).collect(ImmutableList.toImmutableList());
	}

	/** @return how many moves have been recorded */
	public int moveCount() {
		return moves.size();
	}

	/** @return the setup this game started with, over the standard map */
	@Nonnull
	public GameSetup setup() {
		return new GameSetup(standardGraph(), rounds());
	}

	/** @return the state the game started from */
	@Nonnull
	public GameState initialState() {
		return new MyGameStateFactory().build(setup(), mrX(), detectives());
	}

	/**
	 * Folds every recorded move back over the initial state.
	 *
	 * @return the states and moves of the whole game
	 * @throws IllegalStateException if a recorded move is not legal in the state it
	 *                               was recorded for
	 */
	@Nonnull
	public Replay replay() {
		GameState state = initialState();
		List<GameState> states = new ArrayList<>();
		List<Move> replayed = new ArrayList<>();
		states.add(state);
		for (MoveEntry entry : moves) {
			Move move = entry.resolve(state);
			state = state.advance(move);
			replayed.add(move);
			states.add(state);
		}
		return new Replay(ImmutableList.copyOf(states), ImmutableList.copyOf(replayed));
	}

	/** The result of {@link GameRecord#replay()}. */
	public static final class Replay {
		private final ImmutableList<GameState> states;
		private final ImmutableList<Move> moves;

		private Replay(ImmutableList<GameState> states, ImmutableList<Move> moves) {
			this.states = states;
			this.moves = moves;
		}

		/** @return every state, starting with the initial one; size is moves + 1 */
		@Nonnull
		public ImmutableList<GameState> states() {
			return states;
		}

		/** @return the moves that produced {@link #states()} */
		@Nonnull
		public ImmutableList<Move> moves() {
			return moves;
		}

		/** @return the state the game is in now */
		@Nonnull
		public GameState finalState() {
			return states.get(states.size() - 1);
		}
	}

	static Piece parsePiece(String name) {
		Objects.requireNonNull(name, "piece name");
		String upper = name.toUpperCase(Locale.ENGLISH);
		if (upper.equals(Piece.MrX.MRX.name())) {
			return Piece.MrX.MRX;
		}
		for (Piece.Detective detective : Piece.Detective.values()) {
			if (detective.name().equals(upper)) {
				return detective;
			}
		}
		throw new IllegalStateException("Unknown piece in save: " + name);
	}

	private static Ticket parseTicket(String name) {
		Objects.requireNonNull(name, "ticket name");
		for (Ticket ticket : Ticket.values()) {
			if (ticket.name().equals(name.toUpperCase(Locale.ENGLISH))) {
				return ticket;
			}
		}
		throw new IllegalStateException("Unknown ticket in save: " + name);
	}

	/** Lazily loaded so a bad classpath fails at replay rather than at class load. */
	private static final class Graph {
		private static final ImmutableValueGraph<Integer, ImmutableSet<Transport>> STANDARD = load();

		private Graph() {
		}

		private static ImmutableValueGraph<Integer, ImmutableSet<Transport>> load() {
			try {
				return ScotlandYard.standardGraph();
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot read the standard Scotland Yard map", e);
			}
		}
	}

	/** @return the standard 199 node map, read once and shared */
	@Nonnull
	public static ImmutableValueGraph<Integer, ImmutableSet<Transport>> standardGraph() {
		return Graph.STANDARD;
	}
}
