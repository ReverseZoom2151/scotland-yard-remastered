package uk.ac.bris.cs.scotlandyard.ui.controller;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;
import uk.ac.bris.cs.scotlandyard.ResourceManager;
import uk.ac.bris.cs.scotlandyard.ResourceManager.ImageResource;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.GameSetup;
import uk.ac.bris.cs.scotlandyard.model.Model;
import uk.ac.bris.cs.scotlandyard.model.Model.Observer;
import uk.ac.bris.cs.scotlandyard.model.Model.Observer.Event;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Move.FunctionalVisitor;
import uk.ac.bris.cs.scotlandyard.model.MyModelFactory;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Factory;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Ticket;
import uk.ac.bris.cs.scotlandyard.persistence.GameRecord;
import uk.ac.bris.cs.scotlandyard.persistence.GameRecordIO;
import uk.ac.bris.cs.scotlandyard.persistence.History;
import uk.ac.bris.cs.scotlandyard.persistence.Scenarios;
import uk.ac.bris.cs.scotlandyard.ui.GameControl;
import uk.ac.bris.cs.scotlandyard.ui.controller.LocalSetupController.Features;
import uk.ac.bris.cs.scotlandyard.ui.controller.NotificationController.NotificationBuilder;
import uk.ac.bris.cs.scotlandyard.ui.model.BoardViewProperty;
import uk.ac.bris.cs.scotlandyard.ui.model.ModelProperty;
import uk.ac.bris.cs.scotlandyard.ui.model.PlayerProperty;

import static uk.ac.bris.cs.scotlandyard.ui.Utils.handleFatalException;

public final class LocalGameController extends BaseGameController {

	public static LocalGameController newGame(ResourceManager manager, Stage stage) {
		var controller = new LocalGameController(manager, stage);
		stage.setTitle("ScotlandYardNG");
		Parent root = controller.root();
		Scene scene = new Scene(root, 1280, 800);
		scene.getStylesheets().add(Resources.getResource("style/global.css").toExternalForm());
		stage.setScene(scene);
		stage.getIcons().add(manager.getImage(ImageResource.ICON));
		stage.show();
		return controller;
	}

	private LocalGameController(ResourceManager manager, Stage stage) {
		super(manager, stage, new BoardViewProperty());
	}

	/** Everything the game is currently playing out; null before the first game. */
	private GameSession session;

	@Override
	public void initialize(URL location, ResourceBundle resources) {
		MenuItem newGame = new MenuItem("New game");
		MenuItem reset = new MenuItem("Reset (discards current game)");
		newGame.setOnAction(e -> LocalGameController.newGame(resourceManager, new Stage()));
		reset.setOnAction(e -> {
			getStage().close();
			LocalGameController.newGame(resourceManager, new Stage());
		});

		MenuItem save = new MenuItem("Save game...");
		save.setOnAction(e -> saveGame());
		MenuItem load = new MenuItem("Load game...");
		load.setOnAction(e -> loadGame());
		MenuItem undo = new MenuItem("Undo move");
		undo.setOnAction(e -> undoMove());
		MenuItem redo = new MenuItem("Redo move");
		redo.setOnAction(e -> redoMove());

		Menu scenarios = new Menu("Scenarios");
		Scenarios.records().forEach((name, record) -> {
			MenuItem item = new MenuItem(name);
			item.setOnAction(e -> startScenario(name, record.get()));
			scenarios.getItems().add(item);
		});

		// addMenuItem inserts at the top, so add in reverse of the order wanted
		addMenuItem(scenarios);
		addMenuItem(redo);
		addMenuItem(undo);
		addMenuItem(load);
		addMenuItem(save);
		addMenuItem(reset);
		addMenuItem(newGame);
		addStatusNode(buildScrubber());
		setupGame();
	}

	// --- replay scrubber
	// ---------------------------------------------------------------

	/**
	 * The post-mortem replay bar. It is deliberately <em>only</em> live once the game
	 * is over: scrubbing a running game would have to race the AI executor (which
	 * animates counters from a Platform.runLater the scrubber cannot see coming) and
	 * would show the detectives exactly where Mr X has been. A finished game has no
	 * observers left, no AI thinking and nothing left to hide, so walking it is safe.
	 */
	// NOTE: assigned in buildScrubber(), not at the declaration. Controller.bind runs
	// initialize() from the superclass constructor, i.e. before this class's field
	// initializers, so anything initialized here would still be null in initialize().
	private HBox scrubBar;
	private Slider scrubSlider;
	private Label scrubLabel;

	private HBox buildScrubber() {
		scrubBar = new HBox(6);
		scrubSlider = new Slider(0, 0, 0);
		scrubLabel = new Label("Replay available once the game is over");
		Button first = new Button("|<");
		Button previous = new Button("<");
		Button next = new Button(">");
		Button last = new Button(">|");
		first.setOnAction(e -> scrubTo(0));
		previous.setOnAction(e -> scrubTo((int) scrubSlider.getValue() - 1));
		next.setOnAction(e -> scrubTo((int) scrubSlider.getValue() + 1));
		last.setOnAction(e -> scrubTo((int) scrubSlider.getMax()));

		scrubSlider.setBlockIncrement(1);
		scrubSlider.setMajorTickUnit(1);
		scrubSlider.setMinorTickCount(0);
		scrubSlider.setSnapToTicks(true);
		HBox.setHgrow(scrubSlider, Priority.ALWAYS);
		scrubSlider.valueProperty().addListener((o, was, is) -> {
			// programmatic updates happen while the bar is disabled; ignore those
			if (!scrubBar.isDisabled())
				renderHistoryState((int) Math.round(is.doubleValue()));
		});

		scrubBar.setAlignment(Pos.CENTER_LEFT);
		scrubBar.setPadding(new Insets(4, 8, 4, 8));
		scrubBar.getChildren().addAll(new Label("Replay:"), first, previous, next, last,
				scrubSlider, scrubLabel);
		scrubBar.setDisable(true);
		return scrubBar;
	}

	/** Moves the scrubber, which moves the slider, which redraws the board. */
	private void scrubTo(int index) {
		if (scrubBar == null || scrubBar.isDisabled())
			return;
		int clamped = Math.max(0, Math.min(index, (int) scrubSlider.getMax()));
		if ((int) Math.round(scrubSlider.getValue()) == clamped) {
			renderHistoryState(clamped); // slider will not fire, so redraw by hand
		} else {
			scrubSlider.setValue(clamped);
		}
	}

	/** Turns the scrubber off (a game is starting) or on (a game has just ended). */
	private void refreshScrubber(boolean enabled) {
		if (scrubBar == null)
			return;
		GameSession current = session;
		boolean usable = enabled && current != null && current.history.cursorIndex() > 0;
		scrubBar.setDisable(true);
		if (!usable) {
			scrubSlider.setMax(0);
			scrubSlider.setValue(0);
			scrubLabel.setText("Replay available once the game is over");
			return;
		}
		int end = current.history.cursorIndex();
		scrubSlider.setMax(end);
		scrubSlider.setValue(end);
		scrubLabel.setText("Move " + end + " of " + end);
		scrubBar.setDisable(false);
	}

	/**
	 * Draws the board as it stood after {@code index} moves. Read-only throughout: the
	 * model is never advanced, no observer is notified and no AI is woken; the views
	 * are simply handed a historical {@link GameState} to render.
	 */
	private void renderHistoryState(int index) {
		GameSession current = session;
		if (current == null)
			return;
		History history = current.history;
		int end = history.cursorIndex();
		int i = Math.max(0, Math.min(index, end));
		GameState state = history.stateAt(i);

		Map<Piece, Integer> locations = new HashMap<>();
		for (Player detective : current.detectives) {
			if (detective.piece() instanceof Piece.Detective piece) {
				state.getDetectiveLocation(piece)
						.ifPresent(location -> locations.put(piece, location));
			}
		}
		locations.put(current.mrX.piece(), mrXLocationAfter(current.mrX.location(),
				ImmutableList.copyOf(history.movesToCurrent().subList(0, i))));

		map.showSnapshot(locations, true);
		travelLog.onModelChanged(state, Event.MOVE_MADE);
		ticketBoard.onModelChanged(state, Event.MOVE_MADE);
		scrubLabel.setText("Move " + i + " of " + end);
	}

	private void setupGame() {
		refreshScrubber(false);
		var startScreen = new LocalSetupController(resourceManager, config,
				ModelProperty.createDefault(resourceManager),
				ResourceManager.scanAis(),
				EnumSet.allOf(Features.class),
				this::createGame);
		showOverlay(startScreen.root());
	}

	void notifyGameOver(Model model, ImmutableList<? extends GameControl> controls,
			ModelProperty setup, ImmutableSet<Piece> winners) {
		controls.forEach(GameControl::onGameDetached);
		controls.forEach(model::unregisterObserver);
		map.lock();
		// nothing is observing the model any more, so the replay is now safe to walk
		refreshScrubber(true);
		notifications.dismissAll();
		notifications.show("notify_gameover",
				new NotificationBuilder(
						"Game over, winner is \n" + winners)
						.addAction("Start again(same location)", () -> {
							notifications.dismissAll();
							createGame(setup);
						}, true)
						.addAction("Main menu", () -> {
							notifications.dismissAll();
							setupGame();
						}, false).create());
	}

	interface RecordingModel extends Model {
		ImmutableList<String> recorded();
	}

	static final class TestRecordingModelFactory implements Factory<Model> {
		private final Factory<Model> original;

		TestRecordingModelFactory(Factory<Model> original) {
			this.original = original;
		}

		private static String mkPlayerName(Player p) {
			return p.isMrX() ? "mrX" : p.piece().toString().toLowerCase();
		}

		private static String mkPlayerLn(Player p) {
			return String.format("var %s = new Player(%s, makeTickets(%d,%d,%d,%d,%d), %d)",
					mkPlayerName(p), p.piece(),
					p.tickets().getOrDefault(Ticket.TAXI, 0),
					p.tickets().getOrDefault(Ticket.BUS, 0),
					p.tickets().getOrDefault(Ticket.UNDERGROUND, 0),
					p.tickets().getOrDefault(Ticket.DOUBLE, 0),
					p.tickets().getOrDefault(Ticket.SECRET, 0),
					p.location());
		}

		private static String mkTicketLn(Ticket ticket) {
			//@formatter:off
			switch (ticket) {
				case TAXI: return "taxi";
				case BUS: return "bus";
				case UNDERGROUND: return "underg";
				case DOUBLE: return "x2";
				case SECRET: return "secret";
				default:
					throw new AssertionError();
			}
			//@formatter:on
		}

		private static String mkMoveLn(Move move) {
			return move.visit(new FunctionalVisitor<>(m -> String.format("%s(%s, %d, %d)",
					mkTicketLn(m.ticket), m.commencedBy(), m.source(), m.destination),
					m -> String.format("%s(%s, %d,  %s, %d, %s, %d)",
							mkTicketLn(Ticket.DOUBLE), m.commencedBy(), m.source(),
							m.ticket1, m.destination1, m.ticket2, m.destination2)));
		}

		@Nonnull
		@Override
		public RecordingModel build(GameSetup setup, Player mrX,
				ImmutableList<Player> detectives) {
			var model = original.build(setup, mrX, detectives);
			var lines = new ArrayList<String>();
			lines.add(mkPlayerLn(mrX));
			detectives.stream().map(TestRecordingModelFactory::mkPlayerLn).forEach(lines::add);
			var gameName = "game";
			var gameSetup = "standard24RoundSetup()";
			var xs = mkPlayerName(mrX);
			var ds = detectives.stream().map(TestRecordingModelFactory::mkPlayerName)
					.collect(Collectors.joining(", "));
			lines.add(String.format("GameState %s = gameStateFactory.build(%s, %s, %s);",
					gameName, gameSetup, xs, ds));

			return new RecordingModel() {
				@Override
				public ImmutableList<String> recorded() {
					return ImmutableList.copyOf(lines);
				}

				@Override
				@Nonnull
				public Board getCurrentBoard() {
					return model.getCurrentBoard();
				}

				@Override
				public void registerObserver(@Nonnull Observer observer) {
					model.registerObserver(observer);
				}

				@Override
				public void unregisterObserver(@Nonnull Observer observer) {
					model.unregisterObserver(observer);
				}

				@Override
				@Nonnull
				public ImmutableSet<Observer> getObservers() {
					return model.getObservers();
				}

				@Override
				public void chooseMove(@Nonnull Move move) {
					lines.add(String.format("%s = %s.advance(%s);",
							gameName, gameName, mkMoveLn(move)));
					model.chooseMove(move);
				}
			};
		}
	}

	private void createGame(ModelProperty setup) {
		GameSetup gameSetup = new GameSetup(
				setup.graphProperty().get(),
				ImmutableList.copyOf(setup.revealRounds()));
		Player mrX = setup.mrX().asPlayer();
		ImmutableList<Player> detectives = setup.detectives().stream()
				.map(PlayerProperty::asPlayer)
				.collect(ImmutableList.toImmutableList());
		startGame(GameRecord.of(gameSetup, mrX, detectives, ImmutableList.of()), setup);
	}

	// --- save / load / undo / redo / scenarios
	// ---------------------------------------------

	/**
	 * A game in progress. The model is immutable and every state is derived, so
	 * there is nothing here that cannot be rebuilt from the players the game started
	 * with plus the moves played since — which is exactly what a save file holds and
	 * what {@link History} rewinds.
	 */
	private static final class GameSession {
		private final ModelProperty config;
		private final GameSetup setup;
		private final Player mrX;
		private final ImmutableList<Player> detectives;
		private final History history;
		private final Model model;
		private final ImmutableList<GameControl> controls;
		private boolean attached = true;

		private GameSession(ModelProperty config, GameSetup setup, Player mrX,
				ImmutableList<Player> detectives, History history, Model model,
				ImmutableList<GameControl> controls) {
			this.config = config;
			this.setup = setup;
			this.mrX = mrX;
			this.detectives = detectives;
			this.history = history;
			this.model = model;
			this.controls = controls;
		}

		private GameRecord record() {
			return GameRecord.of(setup, mrX, detectives, history.movesToCurrent());
		}
	}

	/**
	 * Starts a game from a record: a fresh setup, a save file, a scenario and an
	 * undone game are all the same thing, so they all come through here.
	 *
	 * <p>
	 * {@code MyModelFactory} only builds a model from a starting position, and it is
	 * graded coursework, so it is left alone: the moves are instead folded back into
	 * a freshly built model <em>before</em> any observer is attached, which replays
	 * the game silently and leaves the model sitting on the state we want.
	 *
	 * @param record the game to play out
	 * @param base   the UI configuration to inherit the timeout and the AIs from
	 */
	private void startGame(GameRecord record, ModelProperty base) {
		hideOverlay();
		try {
			detachCurrentGame();
			refreshScrubber(false);
			GameRecord.Replay replay = record.replay();
			GameSetup gameSetup = record.setup();
			Player mrX = record.mrX();
			ImmutableList<Player> detectives = record.detectives();

			Model built = new MyModelFactory().build(gameSetup, mrX, detectives);
			// no observers yet: the replay is silent, no AI wakes up, no counter moves
			for (Move move : replay.moves()) {
				built.chooseMove(move);
			}
			if (!built.getCurrentBoard().getWinner().isEmpty()) {
				alert(AlertType.INFORMATION, "That game is already over",
						"The winner was " + built.getCurrentBoard().getWinner() + ".");
				setupGame();
				return;
			}

			History history = new History(replay.states().get(0));
			for (int i = 0; i < replay.moves().size(); i++) {
				history.push(replay.moves().get(i), replay.states().get(i + 1));
			}

			ModelProperty config = configFor(base, record, replay);
			// XXX var causes LambdaFactory related errors
			ImmutableList<GameControl> controls = ImmutableList.of(map, travelLog, ticketBoard,
					status);
			Model model = recording(built, history);
			GameSession current = new GameSession(config, gameSetup, mrX, detectives, history, model,
					controls);
			this.session = current;

			controls.forEach(model::registerObserver);
			model.registerObserver(new Observer() {
				@Override
				public void onModelChanged(@Nonnull Board board, @Nonnull Event event) {
					if (event == Event.GAME_OVER) {
						Platform.runLater(() -> {
							current.attached = false;
							notifyGameOver(model, controls, config, board.getWinner());
						});
					}
				}
			});
			controls.forEach(l -> l.onGameAttach(model, config, timeoutWinner -> {
				current.attached = false;
				notifyGameOver(model, controls, config, timeoutWinner);
			}));
		} catch (Exception e) {
			e.printStackTrace();
			handleFatalException(e);
		}
	}

	/**
	 * Wraps the model so that every move the board makes is recorded. The next state
	 * is folded here rather than read back from the model, so the history is up to
	 * date before any observer — the AI included — sees the move.
	 */
	private static Model recording(Model delegate, History history) {
		return new Model() {
			@Nonnull
			@Override
			public Board getCurrentBoard() {
				return delegate.getCurrentBoard();
			}

			@Override
			public void registerObserver(@Nonnull Observer observer) {
				delegate.registerObserver(observer);
			}

			@Override
			public void unregisterObserver(@Nonnull Observer observer) {
				delegate.unregisterObserver(observer);
			}

			@Nonnull
			@Override
			public ImmutableSet<Observer> getObservers() {
				return delegate.getObservers();
			}

			@Override
			public void chooseMove(@Nonnull Move move) {
				history.push(move, ((GameState) delegate.getCurrentBoard()).advance(move));
				delegate.chooseMove(move);
			}
		};
	}

	private void detachCurrentGame() {
		GameSession current = session;
		session = null;
		if (current == null || !current.attached) {
			return;
		}
		current.attached = false;
		current.controls.forEach(GameControl::onGameDetached);
		current.controls.forEach(current.model::unregisterObserver);
		notifications.dismissAll();
	}

	/**
	 * The board views draw their counters and ticket boards from the configuration,
	 * so a resumed game needs one describing where everybody is <em>now</em>, not
	 * where they started.
	 */
	private ModelProperty configFor(ModelProperty base, GameRecord record,
			GameRecord.Replay replay) {
		Board now = replay.finalState();
		int mrXLocation = mrXLocationAfter(record.mrX().location(), replay.moves());
		List<PlayerProperty<? super Piece>> players = new ArrayList<>();
		for (Player player : ImmutableList.<Player>builder()
				.add(record.mrX()).addAll(record.detectives()).build()) {
			PlayerProperty<Piece> property = new PlayerProperty<>(player.piece());
			property.locationProperty().set(player.isMrX()
					? mrXLocation
					: now.getDetectiveLocation((Piece.Detective) player.piece())
							.orElse(player.location()));
			property.tickets().forEach(ticket -> now.getPlayerTickets(player.piece())
					.ifPresent(board -> ticket.countProperty().set(board.getCount(ticket.ticket()))));
			players.add(property);
		}
		return new ModelProperty(base.timeoutProperty().get(), record.rounds(),
				ImmutableList.copyOf(players), GameRecord.standardGraph(),
				base.getMrXAi(), base.getDetectivesAi());
	}

	/** MrX hides from the board, but not from us: we know every move he has made. */
	private static int mrXLocationAfter(int start, ImmutableList<Move> moves) {
		int location = start;
		for (Move move : moves) {
			if (move.commencedBy().isMrX()) {
				location = move.visit(new FunctionalVisitor<>(
						m -> m.destination, m -> m.destination2));
			}
		}
		return location;
	}

	private void saveGame() {
		GameSession current = session;
		if (current == null) {
			alert(AlertType.WARNING, "Nothing to save", "Start a game first.");
			return;
		}
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Save game");
		chooser.setInitialFileName("scotlandyard." + GameRecordIO.EXTENSION);
		chooser.getExtensionFilters().add(
				new ExtensionFilter("Scotland Yard save", "*." + GameRecordIO.EXTENSION));
		File file = chooser.showSaveDialog(getStage());
		if (file == null) {
			return;
		}
		try {
			GameRecordIO.save(current.record(), file.toPath());
			notifications.show("notify_saved",
					new NotificationBuilder("Game saved to " + file.getName()).create());
		} catch (IOException | RuntimeException e) {
			alert(AlertType.ERROR, "Could not save the game", String.valueOf(e.getMessage()));
		}
	}

	private void loadGame() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Load game");
		chooser.getExtensionFilters().add(
				new ExtensionFilter("Scotland Yard save", "*." + GameRecordIO.EXTENSION));
		File file = chooser.showOpenDialog(getStage());
		if (file == null) {
			return;
		}
		Path path = file.toPath();
		try {
			GameRecord record = GameRecordIO.load(path);
			// fail before tearing the running game down
			record.replay();
			startGame(record, baseConfig());
		} catch (IOException | RuntimeException e) {
			alert(AlertType.ERROR, "Could not load " + file.getName(), String.valueOf(e.getMessage()));
		}
	}

	private void startScenario(String name, GameRecord record) {
		try {
			startGame(record, baseConfig());
			notifications.show("notify_scenario",
					new NotificationBuilder("Scenario: " + name).create());
		} catch (RuntimeException e) {
			alert(AlertType.ERROR, "Could not start the scenario " + name,
					String.valueOf(e.getMessage()));
		}
	}

	/**
	 * Undo has to step back a whole ply, not a half-move: an AI handed the turn back
	 * would simply play the same move again.
	 */
	private void undoMove() {
		GameSession current = session;
		if (current == null || !current.history.canUndo()) {
			alert(AlertType.INFORMATION, "Nothing to undo", "No move has been made yet.");
			return;
		}
		Optional<Piece> toMove = History.pieceToMove(current.history.current());
		if (toMove.isPresent()) {
			current.history.undoUntilPieceToMove(toMove.get());
		} else {
			current.history.undo();
		}
		rebuildFromHistory(current);
	}

	private void redoMove() {
		GameSession current = session;
		if (current == null || !current.history.canRedo()) {
			alert(AlertType.INFORMATION, "Nothing to redo", "No move has been taken back.");
			return;
		}
		current.history.redo();
		rebuildFromHistory(current);
	}

	/**
	 * Rebuilds the model at the history's cursor. The history itself survives, so a
	 * move that has been undone can still be put back.
	 */
	private void rebuildFromHistory(GameSession current) {
		History history = current.history;
		GameRecord record = GameRecord.of(current.setup, current.mrX, current.detectives,
				history.movesToCurrent());
		startGame(record, current.config);
		GameSession rebuilt = session;
		if (rebuilt != null) {
			// carry the redo tail, and the cursor, over to the rebuilt game
			int cursor = history.cursorIndex();
			for (int i = rebuilt.history.size(); i < history.size(); i++) {
				rebuilt.history.push(history.allMoves().get(i - 1), history.stateAt(i));
			}
			while (rebuilt.history.cursorIndex() > cursor) {
				rebuilt.history.undo();
			}
		}
	}

	private ModelProperty baseConfig() {
		GameSession current = session;
		return current == null ? ModelProperty.createDefault(resourceManager) : current.config;
	}

	private void alert(AlertType type, String header, String content) {
		Alert alert = new Alert(type, content);
		alert.setHeaderText(header);
		alert.initOwner(getStage());
		alert.show();
	}

}
