package uk.ac.bris.cs.scotlandyard.model;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import uk.ac.bris.cs.scotlandyard.model.Board.GameState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.ac.bris.cs.scotlandyard.model.ScotlandYard.*;

/**
 * A game dealt from the positions the app actually deals must be playable: Mr X
 * has somewhere to go, and nobody has won before a move is made.
 *
 * <p>
 * The rest of the suite builds its boards by hand. Nothing checked that the
 * <i>shipped</i> start positions produce a live game, so a state that declared a
 * winner at deal time would have gone unnoticed until someone launched the app.
 */
public class GameStartIsPlayableTest extends ParameterisedModelTestBase {

	@Test
	public void everyDealtStartIsPlayable() throws IOException {
		GameSetup setup = new GameSetup(ScotlandYard.standardGraph(), STANDARD24ROUNDS);

		for (int seed = 0; seed < 50; seed++) {
			int mrXStart = generateMrXLocation(seed);
			ImmutableList<Integer> detectiveStarts = generateDetectiveLocations(seed, 5);

			List<Player> detectives = new ArrayList<>();
			ImmutableList<Piece.Detective> colours = ImmutableList.of(
					Piece.Detective.RED, Piece.Detective.GREEN, Piece.Detective.BLUE,
					Piece.Detective.WHITE, Piece.Detective.YELLOW);
			for (int i = 0; i < colours.size(); i++) {
				detectives.add(new Player(colours.get(i), defaultDetectiveTickets(), detectiveStarts.get(i)));
			}
			Player mrX = new Player(Piece.MrX.MRX, defaultMrXTickets(), mrXStart);

			GameState state = gameStateFactory.build(setup, mrX, ImmutableList.copyOf(detectives));

			assertThat(state.getWinner())
					.as("seed %d: mrX at %s, detectives at %s -- nobody should have won yet",
							seed, mrXStart, detectiveStarts)
					.isEmpty();
			assertThat(state.getAvailableMoves())
					.as("seed %d: mrX at %s must have somewhere to go", seed, mrXStart)
					.isNotEmpty();
			assertThat(state.getAvailableMoves().stream().allMatch(m -> m.commencedBy().isMrX()))
					.as("seed %d: mrX moves first", seed)
					.isTrue();
		}
	}

	@Test
	public void mrXStartsOnADetectiveIsAnImmediateDetectiveWin() throws IOException {
		// The one degenerate deal worth pinning: if Mr X is placed on top of a
		// detective he is already caught, and the game is over before it begins.
		GameSetup setup = new GameSetup(ScotlandYard.standardGraph(), STANDARD24ROUNDS);
		Player red = new Player(Piece.Detective.RED, defaultDetectiveTickets(), 26);
		Player mrX = new Player(Piece.MrX.MRX, defaultMrXTickets(), 26);

		GameState state = gameStateFactory.build(setup, mrX, ImmutableList.of(red));

		assertThat(state.getWinner()).containsExactly(Piece.Detective.RED);
		assertThat(state.getAvailableMoves()).isEmpty();
	}
}
