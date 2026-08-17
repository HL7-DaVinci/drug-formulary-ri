package org.hl7.davinci.publish.web;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.publish.PublishProperties;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Serves $bulk-publish output files. Content at a URL is immutable (a file is written once under
 * its snapshot id and never rewritten), so these are cacheable forever.
 */
@RestController
@RequestMapping(PublishFileController.BASE_PATH)
public class PublishFileController {

	/** The URL prefix these files are served under; {@code PublishService} builds manifest URLs from it. */
	public static final String BASE_PATH = "/api/publish";

	private static final MediaType NDJSON = MediaType.parseMediaType("application/fhir+ndjson");
	private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
	private static final Pattern SNAPSHOT_ID =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private final PublishProperties publishProps;
	private final FhirContext fhirContext;

	public PublishFileController(PublishProperties publishProps, FhirContext fhirContext) {
		this.publishProps = publishProps;
		this.fhirContext = fhirContext;
	}

	@GetMapping("/{snapshotId}/{fileName}")
	public ResponseEntity<StreamingResponseBody> file(
			@PathVariable("snapshotId") String snapshotId,
			@PathVariable("fileName") String fileName,
			@RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding,
			@RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
		if (!SNAPSHOT_ID.matcher(snapshotId).matches()
				|| !NdjsonFiles.SAFE_FILE.matcher(fileName).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot id or file name");
		}
		if (!acceptsNdjson(accept)) {
			return notAcceptable();
		}
		Path gz = Path.of(publishProps.getStoragePath(), snapshotId, fileName + ".gz");
		if (!Files.exists(gz)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
		}

		ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
				.contentType(NDJSON)
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
				.header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING + ", " + HttpHeaders.ACCEPT);

		if (clientAcceptsGzip(acceptEncoding)) {
			builder.header(HttpHeaders.CONTENT_ENCODING, "gzip");
			StreamingResponseBody body = out -> {
				try (InputStream in = Files.newInputStream(gz)) {
					in.transferTo(out);
				}
			};
			return builder.body(body);
		}

		StreamingResponseBody body = out -> {
			try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
				in.transferTo(out);
			}
		};
		return builder.body(body);
	}

	/**
	 * True when Accept-Encoding names gzip with a quality above zero. A coding without a q
	 * parameter is accepted; a q that does not parse rejects the coding, as does {@code q=0}.
	 */
	private static boolean clientAcceptsGzip(String acceptEncoding) {
		if (acceptEncoding == null) {
			return false;
		}
		for (String coding : acceptEncoding.split(",")) {
			String[] parts = coding.trim().split(";");
			if (parts[0].trim().equalsIgnoreCase("gzip")) {
				return qualityValue(parts) > 0;
			}
		}
		return false;
	}

	/** The q parameter of one parsed coding; 1 when absent and 0 when it does not parse. */
	private static double qualityValue(String[] codingParts) {
		for (int i = 1; i < codingParts.length; i++) {
			String parameter = codingParts[i].trim();
			if (parameter.toLowerCase(Locale.ROOT).startsWith("q=")) {
				try {
					return Double.parseDouble(parameter.substring(2).trim());
				} catch (NumberFormatException e) {
					return 0;
				}
			}
		}
		return 1;
	}

	/**
	 * True when Accept names a media type that covers application/fhir+ndjson with a quality above
	 * zero. The all-types wildcard and {@code application/*} cover it; an unparseable header does
	 * not.
	 */
	private static boolean acceptsNdjson(String accept) {
		if (accept == null || accept.isBlank()) {
			return true;
		}
		try {
			return MediaType.parseMediaTypes(accept).stream()
					.anyMatch(type -> type.getQualityValue() > 0 && type.includes(NDJSON));
		} catch (InvalidMediaTypeException e) {
			return false;
		}
	}

	private ResponseEntity<StreamingResponseBody> notAcceptable() {
		IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(fhirContext);
		OperationOutcomeUtil.addIssue(
				fhirContext, outcome, "error", "Only application/fhir+ndjson is supported.", null, "not-supported");
		byte[] body = fhirContext
				.newJsonParser()
				.setPrettyPrint(false)
				.encodeResourceToString(outcome)
				.getBytes(StandardCharsets.UTF_8);
		return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
				.contentType(FHIR_JSON)
				.header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING + ", " + HttpHeaders.ACCEPT)
				.body(out -> out.write(body));
	}
}
