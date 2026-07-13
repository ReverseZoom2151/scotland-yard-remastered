package uk.ac.bris.cs.scotlandyard.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.annotation.Nonnull;

/**
 * Reads and writes {@link GameRecord}s as human readable JSON.
 */
public final class GameRecordIO {

	/** The extension saved games conventionally use. */
	public static final String EXTENSION = "json";

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.serializeNulls()
			.create();

	private GameRecordIO() {
	}

	/**
	 * @param record the game to write
	 * @param path   the file to write it to; parent directories are created
	 * @throws IOException if the file cannot be written
	 */
	public static void save(@Nonnull GameRecord record, @Nonnull Path path) throws IOException {
		Objects.requireNonNull(record, "record");
		Objects.requireNonNull(path, "path");
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(record, writer);
			writer.write(System.lineSeparator());
		}
	}

	/**
	 * @param path the file to read
	 * @return the game recorded in that file
	 * @throws IOException if the file cannot be read or does not hold a game
	 */
	@Nonnull
	public static GameRecord load(@Nonnull Path path) throws IOException {
		Objects.requireNonNull(path, "path");
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			GameRecord record = GSON.fromJson(reader, GameRecord.class);
			if (record == null) {
				throw new IOException("Not a saved game: " + path + " is empty");
			}
			try {
				record.validate();
			} catch (IllegalStateException e) {
				throw new IOException("Not a valid saved game: " + path + " (" + e.getMessage() + ")", e);
			}
			return record;
		} catch (JsonParseException e) {
			throw new IOException("Not a valid saved game: " + path + " is not well formed JSON", e);
		}
	}
}
