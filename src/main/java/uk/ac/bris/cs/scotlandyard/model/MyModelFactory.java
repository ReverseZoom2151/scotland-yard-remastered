package uk.ac.bris.cs.scotlandyard.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Factory;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * Builds the observable {@link Model} that the UI and the AI players drive.
 */
public final class MyModelFactory implements Factory<Model> {

	@Nonnull
	@Override
	public Model build(GameSetup setup, Player mrX, ImmutableList<Player> detectives) {
		return new MyModel(new MyGameStateFactory().build(setup, mrX, detectives));
	}

	private static final class MyModel implements Model {

		// Insertion-ordered so observers are notified in the order they registered.
		private final Set<Observer> observers = new LinkedHashSet<>();
		private Board.GameState state;

		private MyModel(Board.GameState state) {
			this.state = state;
		}

		@Nonnull
		@Override
		public Board getCurrentBoard() {
			return state;
		}

		@Override
		public void registerObserver(@Nonnull Observer observer) {
			Objects.requireNonNull(observer, "Cannot register a null observer");
			if (!observers.add(observer)) {
				throw new IllegalArgumentException("Observer is already registered");
			}
		}

		@Override
		public void unregisterObserver(@Nonnull Observer observer) {
			Objects.requireNonNull(observer, "Cannot unregister a null observer");
			if (!observers.remove(observer)) {
				throw new IllegalArgumentException("Observer was never registered");
			}
		}

		@Nonnull
		@Override
		public ImmutableSet<Observer> getObservers() {
			return ImmutableSet.copyOf(observers);
		}

		@Override
		public void chooseMove(@Nonnull Move move) {
			state = state.advance(move);
			// Observers see the board as it is after the move, never before.
			Observer.Event event = state.getWinner().isEmpty()
					? Observer.Event.MOVE_MADE
					: Observer.Event.GAME_OVER;
			for (Observer observer : ImmutableSet.copyOf(observers)) {
				observer.onModelChanged(state, event);
			}
		}
	}
}
