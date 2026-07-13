package uk.ac.bris.cs.scotlandyard.ui.controller;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.atlassian.fugue.Pair;
import net.kurobako.gesturefx.GesturePane;
import net.kurobako.gesturefx.GesturePane.FitMode;
import net.kurobako.gesturefx.GesturePane.ScrollBarPolicy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import io.atlassian.fugue.Option;
import io.atlassian.fugue.Unit;
import javafx.animation.Interpolator;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.util.Duration;
import uk.ac.bris.cs.scotlandyard.ui.ai.Distances;
import uk.ac.bris.cs.scotlandyard.ui.ai.Explains;
import uk.ac.bris.cs.scotlandyard.ui.ai.Suspicion;
import uk.ac.bris.cs.fxkit.BindFXML;
import uk.ac.bris.cs.fxkit.Controller;
import uk.ac.bris.cs.fxkit.interpolator.DecelerateInterpolator;
import uk.ac.bris.cs.scotlandyard.ResourceManager;
import uk.ac.bris.cs.scotlandyard.ResourceManager.ImageResource;
import uk.ac.bris.cs.scotlandyard.model.Ai;
import uk.ac.bris.cs.scotlandyard.model.Board;
import uk.ac.bris.cs.scotlandyard.model.Model;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Move.DoubleMove;
import uk.ac.bris.cs.scotlandyard.model.Move.FunctionalVisitor;
import uk.ac.bris.cs.scotlandyard.model.Move.SingleMove;
import uk.ac.bris.cs.scotlandyard.model.Move.Visitor;
import uk.ac.bris.cs.scotlandyard.model.Piece;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard;
import uk.ac.bris.cs.scotlandyard.ui.GameControl;
import uk.ac.bris.cs.scotlandyard.ui.Utils;
import uk.ac.bris.cs.scotlandyard.ui.controller.NotificationController.NotificationBuilder;
import uk.ac.bris.cs.scotlandyard.ui.model.BoardViewProperty;
import uk.ac.bris.cs.scotlandyard.ui.model.ModelProperty;
import uk.ac.bris.cs.scotlandyard.ui.privacy.PrivateMoveChannel;
import uk.ac.bris.cs.scotlandyard.ui.privacy.PrivateMovePane;

import static io.atlassian.fugue.Option.none;
import static io.atlassian.fugue.Option.some;
import static java.util.Objects.requireNonNull;
import static uk.ac.bris.cs.scotlandyard.model.Piece.MrX.MRX;

/**
 * Map that holds playing pieces and draws annotations.<br>
 * Not required for the coursework.
 */
@BindFXML("layout/Map.fxml")
final class MapController implements Controller, GameControl {

	private static final Duration DURATION = Duration.millis(400);

	@FXML
	private Pane root;
	@FXML
	private ImageView mapView;
	@FXML
	private Pane historyPane;
	@FXML
	private Pane shadow;
	@FXML
	private Pane counterPane;
	@FXML
	private Pane hintPane;

	private final Pane mask;

	/**
	 * Sibling of {@link #mask}, same blend mode, drawn just underneath it. The
	 * analysis overlays live here rather than in {@code mask} itself because
	 * {@link #clearMoveHints()} wipes {@code mask} on every move, and the overlays
	 * outlive a move.
	 */
	private final Pane overlay;
	/** Un-blended layer for text: percentages, scores, the legend keys. */
	private final Pane annotations;
	/**
	 * What {@link #root()} hands out. Assigned late, and deliberately:
	 * {@code Controller.bind} calls {@code root()} while loading the FXML and feeds the
	 * result to {@code FXMLLoader.setRoot}, which throws "Root value already specified"
	 * if it is given anything but null. The original code got away with this by leaving
	 * {@code gesturePane} null at that point; this field must stay null there for the
	 * same reason.
	 */
	private StackPane rootStack;

	private final NotificationController notifications;
	private final BoardViewProperty view;
	private final GesturePane gesturePane;
	private final ResourceManager manager;

	private final Map<Piece, CounterController> counters = new HashMap<>();
	private final Map<Integer, MoveHintController> hints = new HashMap<>();
	private final Map<Piece, Path> historyPaths = new HashMap<>();

	MapController(ResourceManager manager,
			NotificationController notifications,
			BoardViewProperty view) {
		Controller.bind(this);
		this.manager = requireNonNull(manager);
		this.notifications = requireNonNull(notifications);
		this.view = requireNonNull(view);
		StackPane pane = new StackPane(root);
		shadow.setStyle("-fx-background-color: rgba(0,0, 0, 0.4)");

		overlay = new Pane();
		overlay.setBlendMode(BlendMode.OVERLAY);
		overlay.setMouseTransparent(true);
		shadow.getChildren().add(overlay);

		mask = new Pane();
		mask.setBlendMode(BlendMode.OVERLAY);
		shadow.getChildren().add(mask);

		annotations = new Pane();
		annotations.setMouseTransparent(true);
		root.getChildren().add(annotations);

		gesturePane = new GesturePane(pane);
		// The overlay switches live in the View menu (see BaseGameController); nothing
		// floats over the map any more.
		rootStack = new StackPane(gesturePane);
		rootStack.setPickOnBounds(false);
		gesturePane.setScrollBarPolicy(ScrollBarPolicy.NEVER);
		gesturePane.setClipEnabled(false);
		gesturePane.setFitMode(FitMode.FIT);
		gesturePane.setMinScale(0.1f);
		gesturePane.scrollModeProperty().bind(view.scrollModeProperty());
		gesturePane.setOnMouseClicked(e -> {
			if (e.getButton() == MouseButton.SECONDARY) {
				gesturePane.cover();
			} else if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
				gesturePane.animate(Duration.millis(200))
						.interpolateWith(Interpolator.EASE_BOTH)
						.zoomBy(gesturePane.getCurrentScale(),
								gesturePane.targetPointAt(new Point2D(e.getX(), e.getY()))
										.orElse(gesturePane.targetPointAtViewportCentre()));
			}
		});
		historyPane.visibleProperty().bind(view.historyProperty());

		// Any toggle flip redraws everything; the overlays are cheap enough that it is
		// not worth tracking which one changed.
		final javafx.beans.value.ChangeListener<Boolean> redraw = (o, was, is) -> redrawOverlays();
		view.suspicionProperty().addListener(redraw);
		view.ambiguityProperty().addListener(redraw);
		view.aiExplainProperty().addListener(redraw);

		Image image = manager.getImage(ImageResource.MAP);
		mapView.setImage(image);
		lockSize(image.getWidth(), image.getHeight(), root, historyPane, mask, overlay, annotations);
		Platform.runLater(() -> gesturePane.zoomTo(0, Point2D.ZERO));
	}

	private static void lockSize(double width, double height, Region... regions) {
		for (Region region : regions) {
			region.setPrefSize(width, height);
			region.setMaxSize(width, height);
			region.setMinSize(width, height);
			// region.resize(width, height);
		}
	}

	private Model model;
	private ModelProperty config;
	private Consumer<ImmutableSet<Piece>> timeout;

	private ExecutorService aiExecutor;
	private Option<Ai> mrXAi = none();
	private Option<Ai> detectiveAi = none();

	// ------------------------------------------------- hot-seat private Mr X moves

	/** Opt-in, off by default; bound to a View menu item by {@link BaseGameController}. */
	private final BooleanProperty privateMrXMoves = new SimpleBooleanProperty(false);
	/** One per game, so the letter mapping reshuffles on every offer. */
	private PrivateMoveChannel privateChannel;
	/** How to put a node over the game, and how to take it away again; may be null. */
	private Consumer<Node> overlayShow;
	private Runnable overlayHide;
	/** Whether the overlay currently showing is ours, so we never hide somebody else's. */
	private boolean privateOverlayShowing;

	BooleanProperty privateMrXMovesProperty() {
		return privateMrXMoves;
	}

	/**
	 * Hands the map a way to show the private-move pane over the game. Both arguments
	 * may be null, in which case the private-move flow simply never engages and the
	 * normal click-the-map flow is untouched.
	 */
	void privateMoveOverlay(Consumer<Node> show, Runnable hide) {
		this.overlayShow = show;
		this.overlayHide = hide;
	}

	@Override
	public void onGameAttach(
			Model model, ModelProperty config, Consumer<ImmutableSet<Piece>> timeout) {
		this.model = model;
		hidePrivateOverlay();
		privateChannel = new PrivateMoveChannel(new SecureRandom().nextLong());
		this.config = requireNonNull(config);
		this.timeout = requireNonNull(timeout);
		unlock();
		counters.clear();
		counterPane.getChildren().clear();
		historyPaths.clear();
		historyPane.getChildren().clear();
		for (var player : config.everyone()) {
			CounterController counter = new CounterController(manager, view.animationProperty(),
					player.piece(), player.location());
			counters.put(player.piece(), counter);
			counterPane.getChildren().add(counter.root());

			// setup initial path history
			Path path = new Path();
			path.setFill(Color.TRANSPARENT);
			path.setStroke(Color.web(player.piece().webColour()));
			path.setStrokeWidth(30d);
			path.setOpacity(0.5);
			historyPane.getChildren().add(path);
			historyPaths.put(player.piece(), path);
		}

		if (config.getMrXAi().isDefined() || config.getDetectivesAi().isDefined()) {
			view.historyProperty().set(true);
		}

		aiExecutor = runInContainment(() -> {
			mrXAi = config.getMrXAi();
			detectiveAi = config.getDetectivesAi();
			mrXAi.forEach(Ai::onStart);
			detectiveAi.forEach(Ai::onStart);
			return Executors.newCachedThreadPool(new ThreadFactoryBuilder()
					.setNameFormat("ai-thread-%d")
					.setUncaughtExceptionHandler((t, e) -> Utils.handleFatalException(
							new RuntimeException("An ai instance crashed on thread " + t.getName(), e)))
					.build());
		});
		advanceModel(model);
	}

	@Override
	public void onGameDetached() {
		hidePrivateOverlay();
		lock();
		// The controller is detached on application stop whether or not a game was ever
		// attached (quitting from the setup screen does exactly that). With no game there
		// is no executor, no ai and no hint to clear, and dereferencing the executor here
		// is what used to make Application.stop throw.
		if (aiExecutor == null)
			return;
		clearMoveHints();
		runInContainment(() -> {
			mrXAi.forEach(Ai::onTerminate);
			detectiveAi.forEach(Ai::onTerminate);
			aiExecutor.shutdownNow();
			return Unit.VALUE;
		});
	}

	private static <T> T runInContainment(Callable<T> r) {
		try {
			return r.call();
		} catch (Throwable e) {
			Utils.handleFatalException(e);
			throw new AssertionError();
		}
	}

	double maxLength() {
		return Math.max(root.getWidth(), root.getHeight());
	}

	private Runnable requestAi(Model board, Ai ai) {
		// var terminate = new AtomicBoolean(false);
		var moves = board.getCurrentBoard().getAvailableMoves();
		drawMoveHighlights(moves);
		aiExecutor.submit(() -> {
			try {
				final var move = ai.pickMove(board.getCurrentBoard(),
						new Pair<>(config.timeoutProperty().get().getSeconds(), TimeUnit.SECONDS));
				if (!moves.contains(move)) {
					Utils.handleFatalException(
							new Exception("Ai(" + ai.name() + ") selected an invalid move, got: " + move
									+ ", was expecting one of " + moves));
				} else {
					Platform.runLater(() -> selectAndMove(board, move));
				}
			} catch (Exception e) {
				Utils.handleFatalException(new Exception("Ai(" + ai.name() + ") " +
						"threw an exception while picking a move", e));
			}
		}, aiExecutor);
		return () -> handleAITimeOut(ai);
	}

	private void handleAITimeOut(Ai ai) {
		try {
			aiExecutor.awaitTermination(1l, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Utils.handleFatalException(
					new Exception("Ai(" + ai.name() + ") was interrupted during the bail-out grace period.", e));
		}
	}

	private Runnable requestHuman(ImmutableSet<Move> moves, Consumer<Move> moveCallback) {
		clearMoveHints();
		BiFunction<Integer, Integer, MoveHintController> mapping = (source, location) -> new MoveHintController(manager,
				this,
				source, location, moveCallback);
		// attach tickets to hint
		for (Move move : moves) {
			move.visit(new Visitor<Unit>() {
				@Override
				public Unit visit(SingleMove move) {
					hints.computeIfAbsent(move.destination, t -> mapping.apply(move.source(), t)).addMove(move);
					return Unit.VALUE;
				}

				@Override
				public Unit visit(DoubleMove move) {
					hints.computeIfAbsent(move.destination1, t -> mapping.apply(move.source(), t));
					hints.computeIfAbsent(move.destination2, t -> mapping.apply(move.source(), t)).addMove(move);
					return Unit.VALUE;
				}
			});
		}
		hintPane.getChildren().setAll(hints.values().stream()
				.map(MoveHintController::root)
				.collect(Collectors.toList()));
		drawMoveHighlights(moves);
		return () -> {
		};
	}

	private void advanceModel(Model board) {
		var moves = board.getCurrentBoard().getAvailableMoves();
		var pieces = moves.stream().map(Move::commencedBy).collect(ImmutableSet.toImmutableSet());
		if (moves.isEmpty())
			throw new AssertionError("Model returned empty moves, did it pass all tests?");

		var mrX = moves.stream().map(Move::commencedBy)
				.collect(ImmutableSet.toImmutableSet())
				.equals(ImmutableSet.of(MRX));

		if (mrX)
			counters.get(MRX).animateVisibility(true);

		final Runnable terminateAction;
		if (mrX && mrXAi.isDefined()) {
			overlayAi = mrXAi;
			terminateAction = requestAi(board, mrXAi.get());
		} else if (!mrX && detectiveAi.isDefined()) {
			overlayAi = detectiveAi;
			terminateAction = requestAi(board, detectiveAi.get());
		} else {
			overlayAi = none();
			terminateAction = requestHuman(moves, m -> selectAndMove(model, m));
			// A human Mr X on a shared screen: offer the private (QR) flow on top of the
			// normal one, never instead of it, so a failure here can never strand the turn.
			if (mrX)
				offerPrivateMove(board, moves);
		}

		// The overlays describe the board as it now stands, whoever is to move.
		overlayBoard = board.getCurrentBoard();
		redrawOverlays();

		notifications.show("notify_timeout",
				new NotificationBuilder(
						"Waiting for " + pieces + " to make a move").create(
								Duration.millis(config.timeoutProperty().get().toMillis()),
								() -> {
									terminateAction.run();
									notifications.dismissAll();
									timeout.accept(
											pieces.stream().anyMatch(Piece::isMrX)
													? board.getCurrentBoard().getPlayers().stream()
															.filter(Piece::isDetective)
															.collect(ImmutableSet.toImmutableSet())
													: ImmutableSet.of(MRX));
								}));

	}

	private void selectAndMove(Model model, Move m) {
		notifications.dismissAll();
		clearMoveHints();
		var counter = counters.get(m.commencedBy());
		m.visit(new Visitor<Unit>() {
			@Override
			public Unit visit(SingleMove move) {
				counter.animateTicketMove(move.destination, some(() -> {
					counter.location(move.destination);
					counter.updateLocation();
					model.chooseMove(m);
					drawHistory(move, move.commencedBy());
				}));

				return Unit.VALUE;
			}

			@Override
			public Unit visit(DoubleMove move) {
				counter.animateTicketMove(move.destination1,
						some(() -> {
							counter.location(move.destination1);
							counter.animateTicketMove(move.destination2,
									some(() -> {
										counter.location(move.destination2);
										counter.updateLocation();
										model.chooseMove(m);
										drawHistory(move, move.commencedBy());
									}));
						}));
				return Unit.VALUE;
			}
		});

	}

	/**
	 * Shows the hot-seat privacy pane for a human Mr X: a QR code of a shuffled,
	 * padded {@code LETTER -> destination} table plus a dropdown of letters, so the
	 * detectives sitting next to him see him pick "K" and learn nothing.
	 *
	 * <p>
	 * Strictly additive: the move hints are already on the map underneath, and the
	 * pane can be dismissed to fall back to them, so no path through here can leave a
	 * turn unplayable.
	 */
	private void offerPrivateMove(Model board, ImmutableSet<Move> moves) {
		if (!privateMrXMoves.get() || overlayShow == null || privateChannel == null
				|| moves.isEmpty())
			return;
		final VBox box;
		try {
			var offer = privateChannel.offer(moves);
			PrivateMovePane pane = new PrivateMovePane(offer, move -> {
				hidePrivateOverlay();
				selectAndMove(board, move);
			});
			Button dismiss = new Button("Dismiss (play on the map instead)");
			dismiss.setOnAction(e -> hidePrivateOverlay());
			box = new VBox(8, pane, dismiss);
			box.setAlignment(Pos.CENTER);
			box.setPadding(new Insets(16));
			box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
			box.setStyle("-fx-background-color: white; -fx-background-radius: 8;");
		} catch (RuntimeException e) {
			// e.g. a payload no QR code could hold; the map hints still stand, so the
			// turn simply proceeds without the privacy pane
			notifications.show("notify_private_move",
					new NotificationBuilder("Private Mr X move unavailable, play on the map")
							.create());
			return;
		}
		privateOverlayShowing = true;
		overlayShow.accept(box);
	}

	private void hidePrivateOverlay() {
		if (!privateOverlayShowing)
			return;
		privateOverlayShowing = false;
		if (overlayHide != null)
			overlayHide.run();
	}

	/**
	 * Draws the counters at the given locations without touching the model: the replay
	 * scrubber's only handle on the map. Move input is disabled for as long as the map
	 * is showing a snapshot; {@link #unlock()} puts it back.
	 *
	 * @param locations where each piece stood; pieces absent from the map are left be
	 * @param showMrX   whether Mr X's counter should be drawn at all
	 */
	void showSnapshot(Map<Piece, Integer> locations, boolean showMrX) {
		lock();
		locations.forEach((piece, location) -> {
			CounterController counter = counters.get(piece);
			if (counter == null || location == null || location <= 0)
				return;
			counter.location(location);
			counter.updateLocation();
		});
		CounterController mrX = counters.get(MRX);
		if (mrX != null)
			mrX.animateVisibility(showMrX);
	}

	@Override
	public void onModelChanged(@Nonnull Board board, @Nonnull Event event) {
		if (event != Event.MOVE_MADE)
			return;
		counters.get(MRX).animateVisibility(Iterables.getLast(board.getMrXTravelLog()).location().isPresent());
		advanceModel(model);
	}

	MoveHintController hintAt(int node) {
		return hints.get(node);
	}

	Collection<MoveHintController> allHints() {
		return hints.values();
	}

	private void drawMoveHighlights(ImmutableSet<Move> moves) {
		var destinations = moves.stream().flatMap(a -> a.visit(new FunctionalVisitor<>(
				m -> ImmutableSet.of(m.destination),
				m -> ImmutableSet.of(m.destination1, m.destination2))).stream())
				.collect(ImmutableSet.toImmutableSet());

		for (Integer location : destinations) {
			Point2D point = manager.coordinateAtNode(location);
			Circle circle = new Circle(ScotlandYard.MAP_NODE_SIZE);
			circle.setFill(Color.WHITE);
			circle.setTranslateX(point.getX());
			circle.setTranslateY(point.getY());
			circle.setOpacity(1);
			circle.setStyle(
					"-fx-effect: dropshadow(two-pass-box, white, " + ScotlandYard.MAP_NODE_SIZE * 10 + ", 0.6, 0, 0)");
			mask.getChildren().add(circle);
		}
	}

	// ---------------------------------------------------------------- overlays

	/** The board the overlays currently describe; null before the first turn. */
	private Board overlayBoard;
	/** The Ai to interrogate for an explanation, if the current turn belongs to one. */
	private Option<Ai> overlayAi = none();
	/** Map-static, so computed once per JVM and shared by every game. */
	private static Map<Integer, Integer> ambiguityCache;

	private void redrawOverlays() {
		overlay.getChildren().clear();
		annotations.getChildren().clear();
		legendSlot = 0;
		if (overlayBoard == null) return;
		// Ambiguity first, so the suspicion heat sits on top of it.
		if (view.ambiguityProperty().get()) drawAmbiguity(overlayBoard);
		if (view.suspicionProperty().get()) drawSuspicion(overlayBoard);
		if (view.aiExplainProperty().get()) drawAiExplanation();
	}

	/**
	 * The heatmap of where Mr X could be. Computed from the public travel log alone
	 * (see {@link Suspicion}) — never by asking an Ai — so it is just as correct when
	 * Mr X is a human.
	 */
	private void drawSuspicion(Board board) {
		Map<Integer, Double> likelihoods = Suspicion.likelihoods(board);
		double peak = likelihoods.values().stream().mapToDouble(Double::doubleValue).max().orElse(0d);
		if (peak <= 0) return;

		for (Map.Entry<Integer, Double> entry : likelihoods.entrySet()) {
			double relative = entry.getValue() / peak;
			Circle circle = nodeCircle(entry.getKey(), Color.web("#ff2d55"));
			// Floor the opacity so a long-tail candidate is still visible at all; the peak
			// is opaque, and the ramp between is proportional to likelihood.
			circle.setOpacity(0.18 + 0.82 * relative);
			overlay.getChildren().add(circle);

			if (relative > 0.999) {
				// The strongest candidate gets a ring and its odds spelled out, so the
				// heatmap always has a readable "best guess" even when it is nearly flat.
				Circle ring = new Circle(ScotlandYard.MAP_NODE_SIZE * 1.6);
				ring.setFill(Color.TRANSPARENT);
				ring.setStroke(Color.WHITE);
				ring.setStrokeWidth(4);
				place(ring, entry.getKey());
				annotations.getChildren().add(ring);
				annotations.getChildren().add(
						label(entry.getKey(), String.format("%.0f%%", entry.getValue() * 100), "#ff2d55"));
			}
		}
		addLegend("Mr X suspicion",
				likelihoods.size() + " candidate stations, peak " + String.format("%.0f%%", peak * 100));
	}

	/**
	 * How many stations lie within two moves — the map's own hiding places. Static, so
	 * it is precomputed once and cached for the life of the JVM.
	 */
	private void drawAmbiguity(Board board) {
		if (ambiguityCache == null) {
			ambiguityCache = Suspicion.ambiguity(
					new Distances(board.getSetup().graph), board.getSetup().graph.nodes());
		}
		int max = ambiguityCache.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		int min = ambiguityCache.values().stream().mapToInt(Integer::intValue).min().orElse(0);
		if (max <= min) return;

		for (Map.Entry<Integer, Integer> entry : ambiguityCache.entrySet()) {
			double relative = (entry.getValue() - min) / (double) (max - min);
			// Cold (few ways out) to hot (many): a hue ramp reads faster than opacity alone.
			Circle circle = nodeCircle(entry.getKey(), Color.hsb(220 - 220 * relative, 0.85, 1.0));
			circle.setOpacity(0.25 + 0.55 * relative);
			overlay.getChildren().add(circle);
		}
		addLegend("Ambiguity (2-hop reach)", "blue " + min + " -> red " + max + " stations reachable");
	}

	/**
	 * The current Ai's own reasoning, if it has any to offer. Purely opt-in: an Ai that
	 * does not implement {@link Explains} draws nothing, and {@code Ai} itself is
	 * untouched.
	 */
	private void drawAiExplanation() {
		if (!overlayAi.isDefined()) return;
		Ai ai = overlayAi.get();
		if (!(ai instanceof Explains explains)) return; // the common case: nothing to say

		List<Explains.ScoredMove> evaluation = explains.lastEvaluation();
		if (evaluation == null || evaluation.isEmpty()) return;

		List<Explains.ScoredMove> top = new ArrayList<>(evaluation);
		top.sort(Comparator.comparingInt(Explains.ScoredMove::score).reversed());
		if (top.size() > 5) top = top.subList(0, 5);

		int rank = 0;
		for (Explains.ScoredMove scored : top) {
			Move move = scored.move();
			int destination = move.visit(new FunctionalVisitor<>(m -> m.destination, d -> d.destination2));
			// Best move brightest, fading down the ranking.
			double weight = 1.0 - (rank / (double) Math.max(1, top.size()));
			Color colour = Color.web("#00e5ff");

			Point2D from = manager.coordinateAtNode(move.source());
			Point2D to = manager.coordinateAtNode(destination);
			Line line = new Line(from.getX(), from.getY(), to.getX(), to.getY());
			line.setStroke(colour);
			line.setStrokeWidth(6 + 8 * weight);
			line.setOpacity(0.35 + 0.55 * weight);
			annotations.getChildren().add(line);

			Circle circle = new Circle(ScotlandYard.MAP_NODE_SIZE);
			circle.setFill(colour);
			circle.setOpacity(0.4 + 0.5 * weight);
			place(circle, destination);
			annotations.getChildren().add(circle);

			annotations.getChildren().add(
					label(destination, "#" + (rank + 1) + " " + scored.score(), "#00e5ff"));
			rank++;
		}
		addLegend("AI explain (" + ai.name() + ")", "top " + top.size() + " moves considered, best first");
	}

	private Circle nodeCircle(int location, Color colour) {
		Circle circle = new Circle(ScotlandYard.MAP_NODE_SIZE * 1.2);
		circle.setFill(colour);
		place(circle, location);
		circle.setStyle("-fx-effect: dropshadow(two-pass-box, " + toWeb(colour) + ", "
				+ ScotlandYard.MAP_NODE_SIZE * 4 + ", 0.4, 0, 0)");
		return circle;
	}

	private static String toWeb(Color colour) {
		return String.format("#%02X%02X%02X",
				(int) Math.round(colour.getRed() * 255),
				(int) Math.round(colour.getGreen() * 255),
				(int) Math.round(colour.getBlue() * 255));
	}

	private void place(Node node, int location) {
		Point2D point = manager.coordinateAtNode(location);
		node.setTranslateX(point.getX());
		node.setTranslateY(point.getY());
	}

	private Label label(int location, String text, String webColour) {
		Point2D point = manager.coordinateAtNode(location);
		Label label = new Label(text);
		label.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-background-radius: 4; "
				+ "-fx-padding: 2 6 2 6; -fx-font-size: 20; -fx-font-weight: bold; -fx-text-fill: "
				+ webColour + ";");
		label.setLayoutX(point.getX() + ScotlandYard.MAP_NODE_SIZE);
		label.setLayoutY(point.getY() - ScotlandYard.MAP_NODE_SIZE * 2.5);
		return label;
	}

	/** Appends one key to the legend stack in the top-right of the map. */
	private void addLegend(String title, String detail) {
		Label label = new Label(title + "  —  " + detail);
		label.setStyle("-fx-background-color: rgba(20,20,26,0.8); -fx-background-radius: 6; "
				+ "-fx-padding: 6 12 6 12; -fx-font-size: 22; -fx-text-fill: white;");
		label.setLayoutX(40);
		label.setLayoutY(40 + 50 * legendSlot++);
		annotations.getChildren().add(label);
	}

	private int legendSlot;

	private void clearMoveHints() {
		hints.values().forEach(MoveHintController::discard);
		hints.clear();
		hintPane.getChildren().clear();
		mask.getChildren().clear();
	}

	private void drawHistory(Move move, Piece piece) {
		var source = coordinateAtNode(move.source());
		historyPaths.get(piece).getElements().addAll(
				move.visit(new FunctionalVisitor<ImmutableList<PathElement>>(
						m -> {
							var target = coordinateAtNode(m.destination);
							return ImmutableList.of(
									new MoveTo(source.getX(), source.getY()),
									new LineTo(target.getX(), target.getY()));
						},
						m -> {
							var target1 = coordinateAtNode(m.destination1);
							var target2 = coordinateAtNode(m.destination2);
							return ImmutableList.of(
									new MoveTo(source.getX(), source.getY()),
									new LineTo(target1.getX(), target1.getY()),
									new LineTo(target2.getX(), target2.getY()));
						})));
	}

	Point2D coordinateAtNode(int node) {
		return manager.coordinateAtNode(node);
	}

	@Override
	public Parent root() {
		return rootStack;
	}

	void resetViewport() {
		gesturePane.animate(DURATION)
				.interpolateWith(DecelerateInterpolator.DEFAULT)
				.zoomTo(0, gesturePane.targetPointAtViewportCentre());
	}

	void lock() {
		List.of(hintPane).forEach(p -> p.setVisible(false));
	}

	void unlock() {
		List.of(hintPane).forEach(p -> p.setVisible(true));
	}

}
