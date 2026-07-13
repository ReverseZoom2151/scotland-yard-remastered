package uk.ac.bris.cs.scotlandyard.persistence;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.MyGameStateFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Piece.Detective;
import uk.ac.bris.cs.scotlandyard.model.Piece.MrX;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A saved game must come back exactly as it went in; the whole point of saving
 * the seed rather than the state is that the fold is deterministic.
 */
public class GameRecordIOTest {

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final Player MR_X = new Player(MrX.MRX, ImmutableMap.of(
			Ticket.TAXI, 4, Ticket.BUS, 3, Ticket.UNDERGROUND, 3,
			Ticket.DOUBLE, 2, Ticket.SECRET, 5), 106);

	private static final ImmutableList<Player> DETECTIVES = ImmutableList.of(
			detective(Detective.RED, 26), detective(Detective.GREEN, 29), detective(Detective.BLUE, 50));

	private static Player detective(Detective piece, int location) {
		return new Player(piece, ImmutableMap.of(
				Ticket.TAXI, 11, Ticket.BUS, 8, Ticket.UNDERGROUND, 4,
				Ticket.DOUBLE, 0, Ticket.SECRET, 0), location);
	}

	private static GameSetup setup() {
		return new GameSetup(GameRecord.standardGraph(), ScotlandYard.STANDARD24ROUNDS);
	}

	/** A played game: MrX opens with a double move, then goes secret. */
	private static final class Played {
		private final GameState finalState;
		private final ImmutableList<Move> moves;

		private Played(GameState finalState, ImmutableList<Move> moves) {
			this.finalState = finalState;
			this.moves = moves;
		}
	}

	private static Played play(int moveCount) {
		GameState state = new MyGameStateFactory().build(setup(), MR_X, DETECTIVES);
		List<Move> moves = new ArrayList<>();
		int mrXLocation = MR_X.location();
		boolean doubleUsed = false;
		boolean secretUsed = false;
		while (moves.size() < moveCount) {
			assertThat(state.getWinner()).isEmpty();
			Move move;
			if (History.pieceToMove(state).orElseThrow().isMrX()) {
				if (!doubleUsed) {
					move = firstMatching(state, m -> isDouble(m));
					doubleUsed = true;
				} else if (!secretUsed) {
					move = firstMatching(state, m -> !isDouble(m) && usesSecret(m));
					secretUsed = true;
				} else {
					move = firstMatching(state, m -> !isDouble(m));
				}
				mrXLocation = lastDestination(move);
			} else {
				int hidingAt = mrXLocation;
				// the detectives must not stumble onto MrX and end the game early
				move = firstMatching(state, m -> lastDestination(m) != hidingAt);
			}
			moves.add(move);
			state = state.advance(move);
		}
		return new Played(state, ImmutableList.copyOf(moves));
	}

	private static Move firstMatching(GameState state, java.util.function.Predicate<Move> predicate) {
		return state.getAvailableMoves().stream()
				.filter(predicate)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No move matched in " + state.getAvailableMoves()));
	}

	private static boolean isDouble(Move move) {
		return move.visit(new Move.FunctionalVisitor<>(single -> false, doubleMove -> true));
	}

	private static boolean usesSecret(Move move) {
		return ImmutableList.copyOf(move.tickets()).contains(Ticket.SECRET);
	}

	private static int lastDestination(Move move) {
		return move.visit(new Move.FunctionalVisitor<>(
				single -> single.destination, doubleMove -> doubleMove.destination2));
	}

	private static void assertSameBoard(Board actual, Board expected) {
		assertThat(actual.getMrXTravelLog()).isEqualTo(expected.getMrXTravelLog());
		assertThat(actual.getWinner()).isEqualTo(expected.getWinner());
		assertThat(actual.getPlayers()).isEqualTo(expected.getPlayers());
		assertThat(actual.getAvailableMoves()).isEqualTo(expected.getAvailableMoves());
		assertThat(actual.getSetup().rounds).isEqualTo(expected.getSetup().rounds);
		for (Detective detective : Detective.values()) {
			assertThat(actual.getDetectiveLocation(detective))
					.isEqualTo(expected.getDetectiveLocation(detective));
		}
		for (Piece piece : expected.getPlayers()) {
			for (Ticket ticket : Ticket.values()) {
				assertThat(actual.getPlayerTickets(piece).orElseThrow().getCount(ticket))
						.as("%s's %s tickets", piece, ticket)
						.isEqualTo(expected.getPlayerTickets(piece).orElseThrow().getCount(ticket));
			}
		}
	}

	@Test
	public void savedGameRoundTripsThroughDisk() throws IOException {
		Played played = play(8);
		GameRecord record = GameRecord.of(setup(), MR_X, DETECTIVES, played.moves);
		Path file = folder.newFile("game.json").toPath();

		GameRecordIO.save(record, file);
		GameRecord loaded = GameRecordIO.load(file);

		assertThat(loaded.version()).isEqualTo(GameRecord.FORMAT_VERSION);
		assertThat(loaded.rounds()).isEqualTo(ScotlandYard.STANDARD24ROUNDS);
		assertThat(loaded.mrX()).isEqualTo(MR_X);
		assertThat(loaded.detectives()).isEqualTo(DETECTIVES);
		assertThat(loaded.moveCount()).isEqualTo(8);

		GameRecord.Replay replay = loaded.replay();
		assertThat(replay.moves()).isEqualTo(played.moves);
		assertThat(replay.states()).hasSize(9);
		assertSameBoard(replay.finalState(), played.finalState);
	}

	@Test
	public void theGameIsPlayedOutAsAskedIncludingADoubleAndASecretMove() {
		Played played = play(8);
		assertThat(played.moves).anyMatch(GameRecordIOTest::isDouble);
		assertThat(played.moves).anyMatch(m -> !isDouble(m) && usesSecret(m));
		assertThat(played.finalState.getMrXTravelLog()).isNotEmpty();
	}

	@Test
	public void theSavedJsonIsHumanReadableAndHoldsNoGraph() throws IOException {
		GameRecord record = GameRecord.of(setup(), MR_X, DETECTIVES, play(4).moves);
		Path file = folder.newFile("readable.json").toPath();
		GameRecordIO.save(record, file);

		String json = Files.readString(file, StandardCharsets.UTF_8);
		assertThat(json).contains("\n").contains("\"rounds\"").contains("\"mrX\"")
				.contains("\"detectives\"").contains("\"moves\"").contains("\"MRX\"");
		// the 199 node map is reloaded from the classpath, never written out
		assertThat(json).doesNotContain("graph").doesNotContain("FERRY");
		assertThat(json.length()).isLessThan(8000);
	}

	@Test
	public void replayingAnIntermediateStateGivesTheStateBeforeThatMove() {
		Played played = play(6);
		GameRecord.Replay replay = GameRecord.of(setup(), MR_X, DETECTIVES, played.moves).replay();
		GameState beforeLast = replay.states().get(replay.states().size() - 2);
		assertThat(beforeLast.advance(played.moves.get(5))).isNotNull();
		assertSameBoard(beforeLast.advance(played.moves.get(5)), played.finalState);
	}

	@Test
	public void aCorruptSaveIsRejectedWithAClearMessage() throws IOException {
		Path file = folder.newFile("corrupt.json").toPath();
		Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
		assertThatThrownBy(() -> GameRecordIO.load(file))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("Not a valid saved game");

		Path empty = folder.newFile("empty.json").toPath();
		Files.writeString(empty, "", StandardCharsets.UTF_8);
		assertThatThrownBy(() -> GameRecordIO.load(empty)).isInstanceOf(IOException.class);

		Path missing = folder.newFile("missing.json").toPath();
		Files.writeString(missing, "{\"version\":1,\"rounds\":[]}", StandardCharsets.UTF_8);
		assertThatThrownBy(() -> GameRecordIO.load(missing))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("rounds");
	}

	@Test
	public void aMoveThatIsNotLegalInTheStateItWasRecordedForIsRejected() throws IOException {
		Played played = play(2);
		GameRecord record = GameRecord.of(setup(), MR_X, DETECTIVES, played.moves);
		Path file = folder.newFile("tampered.json").toPath();
		GameRecordIO.save(record, file);

		String json = Files.readString(file, StandardCharsets.UTF_8).replace("\"source\": 106", "\"source\": 1");
		Files.writeString(file, json, StandardCharsets.UTF_8);

		assertThatThrownBy(() -> GameRecordIO.load(file).replay())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Cannot replay");
	}

	@Test
	public void everyScenarioIsLegalAndPlayable() {
		assertThat(Scenarios.all()).hasSize(5);
		Scenarios.all().forEach((name, supplier) -> {
			GameState state = supplier.get();
			assertThat(state.getWinner()).as("%s has no winner yet", name).isEmpty();
			assertThat(state.getAvailableMoves()).as("%s has moves to make", name).isNotEmpty();
		});

		GameState lastRound = Scenarios.all().get(Scenarios.LAST_ROUND).get();
		assertThat(lastRound.getSetup().rounds).hasSize(3);
		assertThat(lastRound.getMrXTravelLog()).hasSize(2);
		assertThat(History.pieceToMove(lastRound)).contains(MrX.MRX);

		GameState reveal = Scenarios.all().get(Scenarios.REVEAL).get();
		assertThat(reveal.getMrXTravelLog()).hasSize(2);
		// round 3 of the standard game is a reveal, and MrX is about to play it
		assertThat(reveal.getSetup().rounds.get(reveal.getMrXTravelLog().size())).isTrue();
		assertThat(History.pieceToMove(reveal)).contains(MrX.MRX);

		GameState ferry = Scenarios.all().get(Scenarios.FERRY).get();
		int mrXAt = ferry.getAvailableMoves().iterator().next().source();
		assertThat(Scenarios.ferryStops()).contains(mrXAt);

		GameState cornered = Scenarios.all().get(Scenarios.CORNERED).get();
		assertThat(cornered.getAvailableMoves())
				.allMatch(m -> ImmutableList.copyOf(m.tickets()).contains(Ticket.SECRET));
	}

	@Test
	public void aScenarioSavesAndLoadsLikeAnyOtherGame() throws IOException {
		GameRecord record = Scenarios.record(Scenarios.LAST_ROUND);
		Path file = folder.newFile("scenario.json").toPath();
		GameRecordIO.save(record, file);
		assertSameBoard(GameRecordIO.load(file).replay().finalState(), record.replay().finalState());
	}
}
