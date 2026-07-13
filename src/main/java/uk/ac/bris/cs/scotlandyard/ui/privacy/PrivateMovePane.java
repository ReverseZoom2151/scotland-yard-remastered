package uk.ac.bris.cs.scotlandyard.ui.privacy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.ui.privacy.PrivateMoveChannel.Offer;

/**
 * The hot-seat privacy pane: shows the QR code(s) for one {@link Offer}, a
 * dropdown of the (meaningless to onlookers) labels, and a confirm button.
 *
 * <p>
 * Mr X scans the code with his phone, reads the {@code LETTER -> MOVE} table
 * there, and picks his letter on the shared screen. The detectives see him pick
 * a letter and learn nothing: the mapping only lives on his phone and is
 * reshuffled next turn.
 *
 * <p>
 * The pane is self-contained: constructing it needs nothing but an
 * {@link Offer} and a callback, so it can be dropped into any container.
 *
 * <p>
 * QR rendering deliberately avoids {@code javafx.embed.swing.SwingFXUtils}
 * (the {@code javafx.swing} module is not on this project's classpath): the
 * ZXing {@link BitMatrix} is written straight into a JavaFX
 * {@link WritableImage}.
 *
 * <p>
 * A payload larger than a single QR code can hold is split into several codes
 * (one per {@link PrivateMoveChannel#SLOT_BUCKET} slots), which reveals the
 * same coarse bucket the padded slot count already reveals, and nothing more.
 */
public final class PrivateMovePane extends VBox {

	private static final int QR_SIZE = 260;
	/** Payload lines per QR code; matches the padding bucket so it leaks nothing extra. */
	private static final int LINES_PER_CODE = PrivateMoveChannel.SLOT_BUCKET;

	private final Offer offer;
	private final ChoiceBox<String> choices = new ChoiceBox<>();

	/**
	 * @param offer    the letter -> move offer for this turn
	 * @param onChosen called with the chosen move when Mr X confirms; padding slots
	 *                 never reach it
	 */
	public PrivateMovePane(Offer offer, Consumer<Move> onChosen) {
		this.offer = offer;

		setSpacing(8);
		setPadding(new Insets(12));
		setAlignment(Pos.CENTER);

		Label title = new Label("Mr X: scan the code, then pick your letter.");
		title.setWrapText(true);

		HBox codes = new HBox(8);
		codes.setAlignment(Pos.CENTER);
		codes.getChildren().addAll(qrNodes(offer.qrPayload()));

		choices.getItems().setAll(offer.labels());
		choices.getSelectionModel().selectFirst();

		Label hint = new Label("Only your phone knows where each letter goes; the letters are reshuffled every turn.");
		hint.setWrapText(true);

		Button confirm = new Button("Confirm");
		confirm.setDefaultButton(true);
		confirm.setOnAction(e -> {
			String label = choices.getSelectionModel().getSelectedItem();
			if (label == null) return;
			// padding slots simply do nothing; no feedback, so onlookers learn nothing
			offer.moveFor(label).ifPresent(onChosen);
		});

		getChildren().addAll(title, codes, choices, hint, confirm);
	}

	/** @return the offer this pane is showing */
	public Offer offer() {
		return offer;
	}

	/** @return the label currently selected, may be a padding label */
	public String selectedLabel() {
		return choices.getSelectionModel().getSelectedItem();
	}

	private static List<Node> qrNodes(String payload) {
		List<Node> nodes = new ArrayList<>();
		for (String chunk : chunk(payload)) {
			try {
				nodes.add(new ImageView(qrImage(chunk, QR_SIZE)));
			} catch (WriterException e) {
				// never expected: chunks are sized to fit a QR code by construction
				Label failed = new Label("QR code could not be rendered");
				failed.setWrapText(true);
				nodes.add(failed);
			}
		}
		return nodes;
	}

	/**
	 * Splits the payload on line boundaries into pieces small enough for one QR
	 * code, repeating the (constant) legend line in each piece.
	 */
	static List<String> chunk(String payload) {
		String[] lines = payload.split("\n", -1);
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int count = 0;
		for (int i = 1; i < lines.length; i++) { // line 0 is the legend
			if (lines[i].isEmpty()) continue;
			if (count == 0) current.append(lines[0]).append('\n');
			current.append(lines[i]).append('\n');
			if (++count == LINES_PER_CODE) {
				chunks.add(current.toString());
				current = new StringBuilder();
				count = 0;
			}
		}
		if (count > 0) chunks.add(current.toString());
		return chunks;
	}

	/** Renders a ZXing QR code straight into a JavaFX image; no Swing involved. */
	static WritableImage qrImage(String payload, int size) throws WriterException {
		Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
		hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
		hints.put(EncodeHintType.MARGIN, 1);
		hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
		BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints);
		WritableImage image = new WritableImage(matrix.getWidth(), matrix.getHeight());
		PixelWriter pixels = image.getPixelWriter();
		for (int x = 0; x < matrix.getWidth(); x++)
			for (int y = 0; y < matrix.getHeight(); y++)
				pixels.setColor(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
		return image;
	}
}
