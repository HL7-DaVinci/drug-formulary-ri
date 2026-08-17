package org.hl7.davinci.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Filesystem helpers shared by the publish snapshot writers. */
public final class PathUtils {

	private PathUtils() {}

	/** Delete a directory and its contents. Best-effort: a failure on any one file is swallowed. */
	public static void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(PathUtils::deleteQuietly);
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}
}
