package org.hl7.davinci.common;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** Shared conventions for the {@code .ndjson.gz} snapshot files written by publish exports. */
public final class NdjsonFiles {

	/** A safe, path-traversal-free NDJSON file name: letters/digits plus the {@code .ndjson} suffix. */
	public static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9]+\\.ndjson");

	private static final int BUFFER = 1 << 16;
	private static final int GZIP_LEVEL = 3;

	private NdjsonFiles() {}

	/** Open a UTF-8 writer over a level-3 gzip stream at {@code file}, with 64KB I/O and gzip buffers. */
	public static Writer gzipWriter(Path file) throws IOException {
		OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(file), BUFFER);
		GZIPOutputStream gz = new GZIPOutputStream(fileOut, BUFFER) {
			{
				def.setLevel(GZIP_LEVEL);
			}
		};
		return new OutputStreamWriter(gz, StandardCharsets.UTF_8);
	}
}
