package uk.ac.bris.cs.scotlandyard.ui.model;

import net.kurobako.gesturefx.GesturePane.ScrollMode;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

public class BoardViewProperty {

	private final ObjectProperty<ScrollMode> scrollMode = new SimpleObjectProperty<>(ScrollMode.PAN);
	private final BooleanProperty animation = new SimpleBooleanProperty(true);
	private final BooleanProperty focusPlayer = new SimpleBooleanProperty(false);
	private final BooleanProperty history = new SimpleBooleanProperty(false);

	/** Heatmap of where Mr X could be, inferred from the public travel log. */
	private final BooleanProperty suspicion = new SimpleBooleanProperty(false);
	/** Heatmap of how many stations sit within two moves of each station. */
	private final BooleanProperty ambiguity = new SimpleBooleanProperty(false);
	/** The current Ai's top scored moves, if it can explain itself. */
	private final BooleanProperty aiExplain = new SimpleBooleanProperty(false);

	public ScrollMode getScrollMode() {
		return scrollMode.get();
	}

	public ObjectProperty<ScrollMode> scrollModeProperty() {
		return scrollMode;
	}

	public BooleanProperty animationProperty() {
		return animation;
	}

	public BooleanProperty focusPlayerProperty() {
		return focusPlayer;
	}

	public BooleanProperty historyProperty() {
		return history;
	}

	public BooleanProperty suspicionProperty() {
		return suspicion;
	}

	public BooleanProperty ambiguityProperty() {
		return ambiguity;
	}

	public BooleanProperty aiExplainProperty() {
		return aiExplain;
	}

}
