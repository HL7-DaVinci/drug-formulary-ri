package org.hl7.davinci.providers;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.davinci.common.BaseProvider;
import org.hl7.davinci.publish.BulkPublishManifestJson;
import org.hl7.davinci.publish.PublishProperties;
import org.hl7.davinci.publish.PublishService;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The Bulk Data $bulk-publish operation (Data Provider, snapshot mode): {@code GET [base]/$bulk-publish}.
 */
@Component
public class BulkPublishProvider extends BaseProvider {

	private static final String CACHE_CONTROL = "public, max-age=10";

	private final PublishService publishService;
	private final PublishProperties publishProps;
	private final FhirContext fhirContext;
	private final ObjectMapper objectMapper;

	public BulkPublishProvider(
			PublishService publishService,
			PublishProperties publishProps,
			FhirContext fhirContext,
			ObjectMapper objectMapper) {
		this.publishService = publishService;
		this.publishProps = publishProps;
		this.fhirContext = fhirContext;
		this.objectMapper = objectMapper;
	}

	@Operation(name = "$bulk-publish", idempotent = true, manualResponse = true)
	public void bulkPublish(HttpServletRequest theServletRequest, HttpServletResponse theServletResponse)
			throws IOException {
		Optional<PublishService.CurrentSnapshot> snapshot = publishService.currentSnapshot();
		if (snapshot.isEmpty()) {
			writeUnavailable(theServletResponse);
			return;
		}

		BulkPublishManifestJson manifest = publishService.render(snapshot.get().meta(), baseUrl(theServletRequest));
		byte[] body = objectMapper.writeValueAsBytes(manifest);
		String etag = sha256Hex(body);

		String ifNoneMatch = theServletRequest.getHeader(Constants.HEADER_IF_NONE_MATCH);
		if (matchesEtag(ifNoneMatch, etag)) {
			theServletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			theServletResponse.setHeader(Constants.HEADER_ETAG, quote(etag));
			theServletResponse.setHeader(Constants.HEADER_CACHE_CONTROL, CACHE_CONTROL);
			return;
		}

		theServletResponse.setStatus(HttpServletResponse.SC_OK);
		theServletResponse.setContentType(Constants.CT_JSON);
		theServletResponse.setHeader(Constants.HEADER_ETAG, quote(etag));
		theServletResponse.setHeader(Constants.HEADER_CACHE_CONTROL, CACHE_CONTROL);
		theServletResponse.getOutputStream().write(body);
	}

	/** Content hash so the ETag changes only when the manifest body actually would. */
	private static String sha256Hex(byte[] body) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(body));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private void writeUnavailable(HttpServletResponse response) throws IOException {
		IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(fhirContext);
		OperationOutcomeUtil.addIssue(
				fhirContext, outcome, "error", "No bulk-publish snapshot is available yet.", null, "transient");
		String body = fhirContext.newJsonParser().setPrettyPrint(false).encodeResourceToString(outcome);
		response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		response.setContentType(Constants.CT_FHIR_JSON_NEW);
		response.getWriter().write(body);
	}

	/** True if any entity tag in the (possibly comma-separated, possibly weak) header matches. */
	private static boolean matchesEtag(String ifNoneMatch, String etag) {
		if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
			return false;
		}
		for (String candidate : ifNoneMatch.split(",")) {
			String trimmed = candidate.trim();
			if (trimmed.startsWith("W/")) {
				trimmed = trimmed.substring(2);
			}
			if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
				trimmed = trimmed.substring(1, trimmed.length() - 1);
			}
			if (trimmed.equals("*") || trimmed.equals(etag)) {
				return true;
			}
		}
		return false;
	}

	private static String quote(String etag) {
		return "\"" + etag + "\"";
	}

	private String baseUrl(HttpServletRequest request) {
		String configured = publishProps.getPublicBaseUrl();
		if (configured != null && !configured.isBlank()) {
			return configured.replaceAll("/+$", "");
		}
		String scheme = request.getScheme();
		int port = request.getServerPort();
		boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
		StringBuilder url = new StringBuilder(scheme).append("://").append(request.getServerName());
		if (!defaultPort) {
			url.append(':').append(port);
		}
		// The file controller sits under the web application context path, which is empty at the
		// root context. A proxy or a servlet path prefix beyond it needs publish.public-base-url.
		url.append(request.getContextPath());
		return url.toString();
	}
}
