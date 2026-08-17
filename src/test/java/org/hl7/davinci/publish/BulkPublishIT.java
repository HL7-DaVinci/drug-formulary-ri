package org.hl7.davinci.publish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Boots the full server and exercises the FHIR Bulk Data $bulk-publish operation end to end:
 * manifest shape, ETag/If-None-Match, gzip content negotiation on output files, that a resource
 * update produces a fresh snapshot while the prior snapshot's files remain servable (grace
 * period), and that the operation is declared in the CapabilityStatement.
 *
 * <p>The endpoint is {@code GET /fhir/$bulk-publish}, served by {@code BulkPublishProvider}.
 * Output files remain served from {@code /api/publish/...}.
 *
 * <p>The configured entry {@code Practitioner?nosuchparam=true} names a search parameter
 * Practitioner does not have, so it fails on every tick of every test here. It exercises the
 * per-type error isolation: each tick publishes the other types and carries the same error
 * OperationOutcome, whose unchanged digest keeps it from forcing a snapshot of its own.
 *
 * <p>Each test arranges its data through the FHIR REST API and then calls
 * {@link PublishService#publishTick()} directly, so no test waits on the publish schedule. The
 * publish interval is long enough that the scheduled tick never fires during the run;
 * {@code publish.transaction-lag-ms=0} keeps every write just made inside the claimed
 * transactionTime.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:bulkpublishit",
			"publish.enabled=true",
			"publish.interval-ms=600000",
			"publish.transaction-lag-ms=0",
			"publish.export-page-size=2",
			"publish.storage-path=./target/bulkpublishit-data",
			"publish.reset-on-startup=true",
			"publish.public-base-url=",
			"publish.resource-types[0]=Organization",
			"publish.resource-types[1]=Location",
			"publish.resource-types[2]=Patient?active=true",
			"publish.resource-types[3]=Practitioner?nosuchparam=true",
			"spring.ai.mcp.server.enabled=false",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class BulkPublishIT {

	@LocalServerPort
	private int port;

	@Autowired
	private FhirContext fhirContext;

	// The JDK request factory keeps this test independent of the httpclient5 version the starter
	// puts on the classpath.
	private final TestRestTemplate rest =
			new TestRestTemplate(new RestTemplateBuilder().requestFactory(SimpleClientHttpRequestFactory.class));

	@Autowired
	private PublishProperties publishProperties;

	@Autowired
	private PublishService publishService;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void bulkPublishReflectsCreateThenUpdateWithGzipNegotiationAndConditionalGet() throws Exception {
		String bulkPublishUrl = bulkPublishUrl();
		IGenericClient client = fhirClient();

		String marker = "BulkPublishIT-" + UUID.randomUUID();
		String orgId = client.create()
				.resource(resource("Organization", marker))
				.execute()
				.getId()
				.getIdPart();

		// Created alongside the Organization so it is untouched by the later Organization-only
		// update below, proving an unchanged type's file URL is reused rather than re-exported.
		String locationMarker = "BulkPublishIT-location-" + UUID.randomUUID();
		client.create().resource(resource("Location", locationMarker)).execute();

		publishService.publishTick();

		ResponseEntity<BulkPublishManifestJson> firstResponse =
				rest.getForEntity(bulkPublishUrl, BulkPublishManifestJson.class);
		assertEquals(HttpStatus.OK, firstResponse.getStatusCode());
		assertTrue(
				firstResponse.getHeaders().getContentType().toString().startsWith("application/json"),
				"manifest should be served as application/json");
		String etag = firstResponse.getHeaders().getFirst(HttpHeaders.ETAG);
		assertNotNull(etag, "manifest response should carry an ETag");

		BulkPublishManifestJson firstManifest = firstResponse.getBody();
		assertNotNull(firstManifest);
		assertEquals(PublishService.MANIFEST_TYPE, firstManifest.manifestType());
		assertNotNull(firstManifest.transactionTime());
		assertFalse(firstManifest.requiresAccessToken());
		assertEquals(
				Duration.ofMillis(publishProperties.getIntervalMs()).toString(),
				firstManifest.updateCadence());
		assertFalse(firstManifest.output().isEmpty());
		for (BulkPublishManifestJson.OutputEntry entry : firstManifest.output()) {
			assertTrue(entry.count() > 0, "each output entry should report a positive count: " + entry.type());
			assertTrue(entry.fileSize() > 0, "each output entry should report a positive fileSize: " + entry.type());
		}

		String orgFileUrl = organizationFileUrl(firstManifest);
		assertNotNull(orgFileUrl, "manifest should include an Organization output entry");
		assertTrue(
				new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8).contains(marker),
				"Organization ndjson should include the newly created org");

		String locationFileUrl = fileUrl(firstManifest, "Location");
		assertNotNull(locationFileUrl, "manifest should include a Location output entry");
		assertTrue(
				new String(downloadPlain(locationFileUrl), StandardCharsets.UTF_8).contains(locationMarker),
				"Location ndjson should include the newly created location");

		byte[] gzipDecoded = downloadGzip(orgFileUrl);
		assertTrue(
				new String(gzipDecoded, StandardCharsets.UTF_8).contains(marker),
				"gzip-negotiated download should decompress to the same NDJSON content");

		HttpResponse<byte[]> gzipRefused = httpClient.send(
				HttpRequest.newBuilder(URI.create(orgFileUrl))
						.header("Accept-Encoding", "gzip;q=0")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, gzipRefused.statusCode());
		assertTrue(
				gzipRefused.headers().firstValue("Content-Encoding").isEmpty(),
				"Accept-Encoding: gzip;q=0 should get an uncompressed body");
		assertTrue(
				new String(gzipRefused.body(), StandardCharsets.UTF_8).contains(marker),
				"the uncompressed body should hold the same NDJSON content");

		RequestEntity<Void> conditionalGet =
				RequestEntity.get(URI.create(bulkPublishUrl)).header(HttpHeaders.IF_NONE_MATCH, etag).build();
		ResponseEntity<String> conditionalResponse = rest.exchange(conditionalGet, String.class);
		assertEquals(HttpStatus.NOT_MODIFIED, conditionalResponse.getStatusCode());

		String updatedMarker = marker + "-updated";
		IBaseResource updated = resource("Organization", updatedMarker, orgId);
		client.update().resource(updated).execute();

		publishService.publishTick();

		BulkPublishManifestJson secondManifest = manifest();
		String updatedOrgFileUrl = organizationFileUrl(secondManifest);
		assertNotNull(updatedOrgFileUrl);
		assertFalse(updatedOrgFileUrl.equals(orgFileUrl), "a new snapshot should not reuse the previous file URL");
		assertTrue(
				new String(downloadPlain(updatedOrgFileUrl), StandardCharsets.UTF_8).contains(updatedMarker),
				"Organization ndjson should reflect the update");
		assertTrue(
				Instant.parse(secondManifest.transactionTime()).isAfter(Instant.parse(firstManifest.transactionTime())),
				"transactionTime should advance with the new snapshot");

		ResponseEntity<byte[]> priorSnapshotStillServed = rest.getForEntity(orgFileUrl, byte[].class);
		assertEquals(
				HttpStatus.OK,
				priorSnapshotStillServed.getStatusCode(),
				"the previous snapshot's file URL should remain servable during its retention grace period");

		// Only the Organization changed between the two manifests; Location's file URL must be
		// reused byte-identical rather than re-exported into the new snapshot.
		assertEquals(
				locationFileUrl,
				fileUrl(secondManifest, "Location"),
				"an untouched type's file URL must not change across a publish that only touched another type");
		ResponseEntity<byte[]> reusedLocationFile = rest.getForEntity(locationFileUrl, byte[].class);
		assertEquals(
				HttpStatus.OK,
				reusedLocationFile.getStatusCode(),
				"the reused type's file must still be servable at its unchanged URL");

		ResponseEntity<String> capabilityStatement =
				rest.getForEntity(fhirBase() + "/metadata?_format=json", String.class);
		assertEquals(HttpStatus.OK, capabilityStatement.getStatusCode());
		assertTrue(
				capabilityStatement.getBody().contains("bulk-publish"),
				"CapabilityStatement should declare the bulk-publish operation");
	}

	/**
	 * With publish.export-page-size=2, 6 Organizations created in a single transaction land at the
	 * exact same lastUpdated instant (HAPI stamps one transaction time for every resource in a
	 * transaction Bundle), forcing a same-instant cluster spanning multiple pages. Every one must
	 * still appear in the export, and exactly once, proving the watermark/pinned-drain paging never
	 * drops or duplicates a row.
	 */
	@Test
	void clusteredOrganizationsSpanningMultiplePagesAllAppearExactlyOnce() throws Exception {
		IGenericClient client = fhirClient();

		String marker = "BulkPublishIT-cluster-" + UUID.randomUUID();
		List<String> names = new ArrayList<>();
		StringBuilder bundleJson = new StringBuilder("{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":[");
		for (int i = 0; i < 6; i++) {
			String name = marker + "-" + i;
			names.add(name);
			if (i > 0) {
				bundleJson.append(",");
			}
			bundleJson
					.append("{\"resource\":{\"resourceType\":\"Organization\",\"name\":\"")
					.append(name)
					.append("\"},\"request\":{\"method\":\"POST\",\"url\":\"Organization\"}}");
		}
		bundleJson.append("]}");
		IBaseBundle transaction =
				(IBaseBundle) fhirContext.newJsonParser().parseResource(bundleJson.toString());
		client.transaction().withBundle(transaction).execute();

		publishService.publishTick();

		String orgFileUrl = organizationFileUrl(manifest());
		assertNotNull(orgFileUrl, "manifest should include an Organization output entry");
		String ndjson = new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8);
		for (String name : names) {
			assertTrue(ndjson.contains(name), "export should include " + name);
			assertEquals(
					ndjson.indexOf(name),
					ndjson.lastIndexOf(name),
					name + " should appear exactly once in the export");
		}
	}

	/**
	 * The configured entry {@code Patient?active=true} publishes under the bare type {@code Patient}
	 * and exports only the resources its filter matches. Both patients are created in one transaction
	 * so they share a lastUpdated instant, which routes them through the same-instant drain; the
	 * filter has to narrow that read too, not only the page read.
	 */
	@Test
	void aFilteredTypeExportsOnlyMatchingResourcesUnderItsBaseType() throws Exception {
		IGenericClient client = fhirClient();

		String marker = "BulkPublishIT-filter-" + UUID.randomUUID();
		String bundleJson = "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
				+ patientEntry(marker + "-active", true)
				+ ","
				+ patientEntry(marker + "-excluded", false)
				+ "]}";
		IBaseBundle transaction = (IBaseBundle) fhirContext.newJsonParser().parseResource(bundleJson);
		client.transaction().withBundle(transaction).execute();

		publishService.publishTick();

		String patientFileUrl = fileUrl(manifest(), "Patient");
		assertNotNull(patientFileUrl, "a filtered entry publishes an output entry under its bare resource type");
		String ndjson = new String(downloadPlain(patientFileUrl), StandardCharsets.UTF_8);
		assertTrue(ndjson.contains(marker + "-active"), "the Patient the filter matches should be exported");
		assertFalse(
				ndjson.contains(marker + "-excluded"), "the Patient the filter excludes should not be exported");
	}

	/** A full re-export sees deletes; the deleted Organization must drop out of the next snapshot. */
	@Test
	void deletedOrganizationIsAbsentFromTheNextSnapshot() throws Exception {
		IGenericClient client = fhirClient();

		String keptMarker = "BulkPublishIT-kept-" + UUID.randomUUID();
		client.create().resource(resource("Organization", keptMarker)).execute();
		String marker = "BulkPublishIT-delete-" + UUID.randomUUID();
		String orgId = client.create()
				.resource(resource("Organization", marker))
				.execute()
				.getId()
				.getIdPart();

		publishService.publishTick();

		String beforeUrl = organizationFileUrl(manifest());
		assertNotNull(beforeUrl);
		assertTrue(new String(downloadPlain(beforeUrl), StandardCharsets.UTF_8).contains(marker));

		client.delete().resourceById("Organization", orgId).execute();

		publishService.publishTick();

		String afterUrl = organizationFileUrl(manifest());
		assertNotNull(afterUrl, "the retained Organization keeps the file present");
		String ndjson = new String(downloadPlain(afterUrl), StandardCharsets.UTF_8);
		assertFalse(ndjson.contains(marker), "deleted Organization should be absent from the new export");
		assertTrue(ndjson.contains(keptMarker), "the retained Organization should still be exported");
	}

	/** Accept header content negotiation on file downloads: only application/fhir+ndjson (or a wildcard) is served. */
	@Test
	void fileDownloadRejectsUnsupportedAcceptHeader() throws Exception {
		IGenericClient client = fhirClient();

		String marker = "BulkPublishIT-accept-" + UUID.randomUUID();
		client.create().resource(resource("Organization", marker)).execute();

		publishService.publishTick();

		String orgFileUrl = organizationFileUrl(manifest());
		assertNotNull(orgFileUrl);
		assertTrue(new String(downloadPlain(orgFileUrl), StandardCharsets.UTF_8).contains(marker));

		HttpResponse<byte[]> rejected = httpClient.send(
				HttpRequest.newBuilder(URI.create(orgFileUrl))
						.header("Accept", "text/csv")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(406, rejected.statusCode());
		assertEquals(
				"Accept-Encoding, Accept",
				rejected.headers().firstValue("Vary").orElse(null),
				"a refused Accept still selects on both headers, so the 406 carries the same Vary");
		assertTrue(
				rejected.headers().firstValue("Content-Type").orElse("").startsWith("application/fhir+json"),
				"an unsupported Accept header should get an OperationOutcome as application/fhir+json");

		HttpResponse<byte[]> zeroQuality = httpClient.send(
				HttpRequest.newBuilder(URI.create(orgFileUrl))
						.header("Accept", "application/fhir+ndjson;q=0")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(406, zeroQuality.statusCode(), "an Accept of q=0 refuses the only media type served");

		HttpResponse<byte[]> accepted = httpClient.send(
				HttpRequest.newBuilder(URI.create(orgFileUrl))
						.header("Accept", "application/fhir+ndjson")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, accepted.statusCode());
	}

	/**
	 * The manifest ETag is a content hash: it stays identical across repeated GETs with no writes in
	 * between, and differs when the request's Host changes, since the manifest embeds request-derived
	 * absolute file URLs and public-base-url is unset in this test's properties.
	 */
	@Test
	void manifestEtagIsAStableContentHashThatVariesByHost() throws Exception {
		IGenericClient client = fhirClient();

		String marker = "BulkPublishIT-etag-" + UUID.randomUUID();
		client.create().resource(resource("Organization", marker)).execute();

		publishService.publishTick();

		ResponseEntity<byte[]> first = rest.getForEntity(bulkPublishUrl(), byte[].class);
		ResponseEntity<byte[]> second = rest.getForEntity(bulkPublishUrl(), byte[].class);
		String firstEtag = first.getHeaders().getFirst(HttpHeaders.ETAG);
		String secondEtag = second.getHeaders().getFirst(HttpHeaders.ETAG);
		assertNotNull(firstEtag);
		assertEquals(firstEtag, secondEtag, "repeated GETs with no writes between them should carry the same ETag");
		assertArrayEquals(
				first.getBody(), second.getBody(), "repeated GETs with no writes between them should be byte-identical");
		assertTrue(
				firstEtag.matches("\"[0-9a-f]{64}\""),
				"ETag should be a quoted 64-character lowercase hex SHA-256 digest: " + firstEtag);

		String loopbackUrl = "http://127.0.0.1:" + port + "/fhir/$bulk-publish";
		ResponseEntity<byte[]> viaLoopback = rest.getForEntity(loopbackUrl, byte[].class);
		String loopbackEtag = viaLoopback.getHeaders().getFirst(HttpHeaders.ETAG);
		assertNotNull(loopbackEtag);
		assertFalse(
				firstEtag.equals(loopbackEtag),
				"a different Host should embed a different absolute base URL and yield a different ETag");
	}

	/**
	 * A configured entry whose filter cannot be translated fails on every tick. The tick publishes
	 * the types that did export and reports the failure as an error OperationOutcome in a file the
	 * manifest outcome property points at.
	 */
	@Test
	void aFailingEntryPublishesAnErrorOutcomeAndLeavesTheOtherTypesPublished() throws Exception {
		IGenericClient client = fhirClient();

		String marker = "BulkPublishIT-outcome-" + UUID.randomUUID();
		client.create().resource(resource("Organization", marker)).execute();

		publishService.publishTick();

		BulkPublishManifestJson manifest = manifest();
		assertNotNull(organizationFileUrl(manifest), "the types that exported are published");
		assertNull(fileUrl(manifest, "Practitioner"), "the failing entry contributes no output entry");
		assertEquals(1, manifest.outcome().size(), "the failing entry contributes one outcome file");

		BulkPublishManifestJson.OutcomeEntry outcomeEntry = manifest.outcome().get(0);
		assertTrue(
				outcomeEntry.url().endsWith("/OperationOutcome.ndjson"),
				"the outcome file is served like any other published file: " + outcomeEntry.url());
		assertEquals(1, outcomeEntry.count(), "one failed entry is one OperationOutcome");
		assertTrue(outcomeEntry.fileSize() > 0, "the outcome entry should report a positive fileSize");

		List<String> lines = new String(downloadPlain(outcomeEntry.url()), StandardCharsets.UTF_8)
				.lines()
				.filter(line -> !line.isBlank())
				.toList();
		assertEquals(1, lines.size(), "the outcome file holds one resource per line");
		IBaseOperationOutcome outcome =
				(IBaseOperationOutcome) fhirContext.newJsonParser().parseResource(lines.get(0));
		assertTrue(
				OperationOutcomeUtil.hasIssuesOfSeverity(fhirContext, outcome, "error"),
				"a failed export is reported at severity error");
		IPrimitiveType<?> diagnosticsElement = (IPrimitiveType<?>)
				fhirContext.newTerser().getSingleValueOrNull(outcome, "issue.diagnostics");
		String diagnostics = diagnosticsElement == null ? null : diagnosticsElement.getValueAsString();
		assertTrue(
				diagnostics.contains("Practitioner?nosuchparam=true"),
				"the diagnostics name the entry that failed: " + diagnostics);
		assertTrue(
				diagnostics.contains("UnrecognizedSearchParameterException"),
				"the diagnostics name the exception class: " + diagnostics);
	}

	/**
	 * Nothing changed between two ticks, so the second one keeps the first one's snapshot current:
	 * an idle server republishes neither its files nor its manifest. The permanently failing entry
	 * fails identically on both ticks, so its outcome file is reused rather than republished.
	 */
	@Test
	void aSecondTickOverAnIdleDatasetPublishesNothingNew() throws Exception {
		IGenericClient client = fhirClient();

		String marker = "BulkPublishIT-idle-" + UUID.randomUUID();
		client.create().resource(resource("Organization", marker)).execute();

		publishService.publishTick();
		String snapshotId = currentSnapshotId();
		String etag = rest.getForEntity(bulkPublishUrl(), byte[].class)
				.getHeaders()
				.getFirst(HttpHeaders.ETAG);
		// The permanently failing entry has to be reported in this manifest for the second tick to
		// prove anything: an idle tick that carries no outcome never exercises the outcome digest.
		assertEquals(1, manifest().outcome().size(), "the failing entry reports its outcome before the idle tick");

		// The second tick must claim a later transactionTime than the first, otherwise it stops at
		// the timestamp guard before it ever reaches the per-type digest comparison.
		Thread.sleep(50);
		publishService.publishTick();

		assertEquals(snapshotId, currentSnapshotId(), "an idle tick should leave the current snapshot in place");
		assertEquals(
				etag,
				rest.getForEntity(bulkPublishUrl(), byte[].class).getHeaders().getFirst(HttpHeaders.ETAG),
				"an idle tick should leave the served manifest byte-identical");
	}

	private String fhirBase() {
		return "http://localhost:" + port + "/fhir";
	}

	private String bulkPublishUrl() {
		return fhirBase() + "/$bulk-publish";
	}

	private IGenericClient fhirClient() {
		return fhirContext.newRestfulGenericClient(fhirBase());
	}

	private BulkPublishManifestJson manifest() {
		ResponseEntity<BulkPublishManifestJson> response =
				rest.getForEntity(bulkPublishUrl(), BulkPublishManifestJson.class);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		BulkPublishManifestJson manifest = response.getBody();
		assertNotNull(manifest);
		return manifest;
	}

	private String currentSnapshotId() {
		return publishService
				.currentSnapshot()
				.orElseThrow(() -> new AssertionError("no snapshot has been published"))
				.snapshotId();
	}

	/**
	 * Built from JSON rather than a versioned model class so the test compiles and runs against any
	 * FHIR version this server may be configured for; {@code name} is a string on both types in every
	 * supported version.
	 */
	private IBaseResource resource(String resourceType, String name) {
		return fhirContext
				.newJsonParser()
				.parseResource("{\"resourceType\":\"" + resourceType + "\",\"name\":\"" + name + "\"}");
	}

	private IBaseResource resource(String resourceType, String name, String id) {
		return fhirContext
				.newJsonParser()
				.parseResource("{\"resourceType\":\"" + resourceType + "\",\"id\":\"" + id + "\",\"name\":\"" + name
						+ "\"}");
	}

	/**
	 * One transaction Bundle entry creating a Patient. Raw JSON like the fixtures above, so it runs
	 * against any FHIR version this server may be configured for; {@code identifier.value} is a
	 * string on Patient in every supported version, while {@code name} is not.
	 */
	private static String patientEntry(String identifier, boolean active) {
		return "{\"resource\":{\"resourceType\":\"Patient\",\"active\":"
				+ active
				+ ",\"identifier\":[{\"value\":\""
				+ identifier
				+ "\"}]},\"request\":{\"method\":\"POST\",\"url\":\"Patient\"}}";
	}

	private static String organizationFileUrl(BulkPublishManifestJson manifest) {
		return fileUrl(manifest, "Organization");
	}

	private static String fileUrl(BulkPublishManifestJson manifest, String type) {
		return manifest.output().stream()
				.filter(entry -> type.equals(entry.type()))
				.map(BulkPublishManifestJson.OutputEntry::url)
				.findFirst()
				.orElse(null);
	}

	/** GET without an Accept-Encoding header; the server should decompress and serve plain NDJSON. */
	private byte[] downloadPlain(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, response.statusCode());
		assertTrue(
				response.headers().firstValue("Content-Type").orElse("").startsWith("application/fhir+ndjson"));
		assertTrue(
				response.headers().firstValue("Content-Encoding").isEmpty(),
				"a request without Accept-Encoding should not receive a Content-Encoding header");
		assertEquals(
				"Accept-Encoding, Accept",
				response.headers().firstValue("Vary").orElse(null),
				"file responses select on both Accept-Encoding and Accept, so Vary names both");
		return response.body();
	}

	/**
	 * GET with an explicit Accept-Encoding: gzip header, using a raw byte-array body handler so the
	 * JDK client neither adds its own Accept-Encoding nor auto-decompresses; the raw gzip bytes are
	 * decompressed here so the test controls and observes the Content-Encoding negotiation itself.
	 */
	private byte[] downloadGzip(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, response.statusCode());
		assertTrue(
				response.headers().firstValue("Content-Type").orElse("").startsWith("application/fhir+ndjson"));
		assertEquals(
				"gzip",
				response.headers().firstValue("Content-Encoding").orElse(null),
				"requesting gzip should receive a Content-Encoding: gzip response");
		assertEquals(
				"Accept-Encoding, Accept",
				response.headers().firstValue("Vary").orElse(null),
				"file responses select on both Accept-Encoding and Accept, so Vary names both");
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
			return gzip.readAllBytes();
		}
	}
}
