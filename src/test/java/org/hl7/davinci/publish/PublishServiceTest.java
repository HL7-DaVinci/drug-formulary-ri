package org.hl7.davinci.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.hl7.davinci.publish.BulkPublishManifestJson;
import org.hl7.davinci.publish.PublishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishServiceTest {

	/** Far past every fixture date below, so the retention grace-period floor never interferes with
	 * the count-window assertions below. */
	private static final long FAR_FUTURE_NOW = Instant.parse("2027-01-01T00:00:00Z").toEpochMilli();

	private static final long DEFAULT_GRACE_PERIOD_MS = 3_600_000;

	@Test
	void rendersManifestFromSnapshotMeta() {
		PublishProperties publishProps = new PublishProperties();
		publishProps.setIntervalMs(5000);
		PublishService service = new PublishService(null, null, null, publishProps, null);

		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta(
				"2026-07-07T15:00:00Z", List.of(new PublishService.FileMeta("Organization", 3, 456, "snap-1", "d1")));

		BulkPublishManifestJson manifest = service.render(meta, "https://directory.example.org");

		assertEquals(PublishService.MANIFEST_TYPE, manifest.manifestType());
		assertEquals("2026-07-07T15:00:00Z", manifest.transactionTime());
		assertEquals("PT5S", manifest.updateCadence());
		assertFalse(manifest.requiresAccessToken());
		assertEquals(1, manifest.output().size());
		BulkPublishManifestJson.OutputEntry entry = manifest.output().get(0);
		assertEquals("Organization", entry.type());
		assertEquals("https://directory.example.org/api/publish/snap-1/Organization.ndjson", entry.url());
		assertEquals(3, entry.count());
		assertEquals(456, entry.fileSize());
	}

	@Test
	void omitsOutputEntriesForTypesWithNoResources() {
		PublishService service = new PublishService(null, null, null, new PublishProperties(), null);

		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta("2026-07-07T15:00:00Z", List.of());

		BulkPublishManifestJson manifest = service.render(meta, "https://directory.example.org");

		assertTrue(manifest.output().isEmpty(), "a snapshot with no exported types renders an empty output array");
		assertTrue(manifest.outcome().isEmpty(), "a snapshot with no failed types renders an empty outcome array");
	}

	/**
	 * A tick that failed a type renders that tick's outcome file under the manifest outcome
	 * property, addressed by its owning snapshot like an output entry so a reused outcome file keeps
	 * a byte-identical URL. The outcome entry carries no resource type: the Bulk Publish manifest
	 * defines type on output entries only.
	 */
	@Test
	void rendersOutcomeFilesUnderTheOutcomeProperty() {
		PublishService service = new PublishService(null, null, null, new PublishProperties(), null);

		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta(
				"2026-07-07T15:00:00Z",
				List.of(new PublishService.FileMeta("Organization", 3, 456, "new-snapshot", "d1")),
				List.of(new PublishService.FileMeta("OperationOutcome", 2, 240, "older-snapshot", "d2")));

		BulkPublishManifestJson manifest = service.render(meta, "https://directory.example.org");

		assertEquals(1, manifest.output().size(), "a failed type leaves the exported types alone");
		assertEquals(1, manifest.outcome().size());
		BulkPublishManifestJson.OutcomeEntry entry = manifest.outcome().get(0);
		assertEquals(
				"https://directory.example.org/api/publish/older-snapshot/OperationOutcome.ndjson",
				entry.url(),
				"a reused outcome file keeps pointing at the snapshot that physically holds it");
		assertEquals(2, entry.count());
		assertEquals(240, entry.fileSize());
	}

	@Test
	void reusedTypeUrlUsesItsOwningSnapshotIdWhileAReExportedTypeUsesTheNewOne() {
		PublishService service = new PublishService(null, null, null, new PublishProperties(), null);

		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta(
				"2026-07-07T15:00:00Z",
				List.of(
						new PublishService.FileMeta("Organization", 5, 900, "new-snapshot", "d1"),
						new PublishService.FileMeta("Location", 2, 300, "older-snapshot", "d2")));

		BulkPublishManifestJson manifest = service.render(meta, "https://directory.example.org");

		Map<String, String> urlByType = new HashMap<>();
		manifest.output().forEach(entry -> urlByType.put(entry.type(), entry.url()));
		assertEquals(
				"https://directory.example.org/api/publish/new-snapshot/Organization.ndjson",
				urlByType.get("Organization"),
				"a re-exported type's URL points at the new snapshot");
		assertEquals(
				"https://directory.example.org/api/publish/older-snapshot/Location.ndjson",
				urlByType.get("Location"),
				"a reused type's URL keeps pointing at the snapshot that physically holds its file");
	}

	/**
	 * The digest gate decides whether a type is republished: a type is reused only when both sides
	 * carry the same non-null digest, so a file written without one always forces a fresh export.
	 */
	@Test
	void sameContentRequiresMatchingNonNullDigestsOrBothAbsent() {
		PublishService.FileMeta a = new PublishService.FileMeta("Organization", 1, 10, "s1", "abc");
		PublishService.FileMeta b = new PublishService.FileMeta("Organization", 1, 10, "s0", "abc");
		PublishService.FileMeta c = new PublishService.FileMeta("Organization", 1, 10, "s0", "def");
		PublishService.FileMeta noDigest = new PublishService.FileMeta("Organization", 1, 10, "s1", null);

		assertTrue(PublishService.sameContent(Optional.of(a), Optional.of(b)));
		assertFalse(PublishService.sameContent(Optional.of(a), Optional.of(c)));
		assertFalse(PublishService.sameContent(Optional.of(a), Optional.empty()));
		assertFalse(PublishService.sameContent(Optional.empty(), Optional.of(b)));
		assertTrue(PublishService.sameContent(Optional.empty(), Optional.empty()));
		assertFalse(PublishService.sameContent(Optional.of(noDigest), Optional.of(b)));
	}

	/**
	 * A configured type dropped from the configuration makes the previous snapshot stale, so the
	 * next tick publishes a manifest without it whether or not a remaining type changed.
	 */
	@Test
	void typesRemovedOnlyFlagsAPreviousTypeThatIsNoLongerConfigured() {
		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta(
				"2026-07-07T15:00:00Z",
				List.of(
						new PublishService.FileMeta("Organization", 1, 10, "s1", "d1"),
						new PublishService.FileMeta("Location", 1, 10, "s1", "d2")));

		assertTrue(PublishService.typesRemoved(meta, List.of("Organization")));
		assertFalse(PublishService.typesRemoved(meta, List.of("Organization", "Location")));
		assertFalse(
				PublishService.typesRemoved(meta, List.of("Organization", "Location", "Practitioner")),
				"a newly configured type is not a removal; its own export drives the republish");
		assertFalse(
				PublishService.typesRemoved(meta, List.of("Organization?active=true", "Location")),
				"a filter added to a configured entry leaves the type configured; the digest gate republishes it");
	}

	/**
	 * A configured list is published as given, apart from OperationOutcome. The generator rejects
	 * that entry, so it can only arrive by hand-editing the server properties, where it would
	 * collide with the file the publisher writes for the manifest outcome property.
	 */
	@Test
	void resolveEntriesKeepsTheConfiguredEntriesAndLeavesTheRegistryAlone() {
		PublishProperties props = new PublishProperties();
		props.setResourceTypes(
				List.of("Organization", "OperationOutcome", "Patient?active=true", "OperationOutcome?_id=1"));
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		PublishService service = new PublishService(null, daoRegistry, null, props, null);

		assertEquals(
				List.of("Organization", "Patient?active=true"),
				service.resolveEntries(),
				"a hand-added OperationOutcome entry is dropped whether or not it carries a filter");
		verifyNoInteractions(daoRegistry);
	}

	/**
	 * An empty configuration publishes every type the server supports. The DaoRegistry already
	 * carries the hapi.fhir.supported_resource_types limitation, so filtering the registered types
	 * through it yields the supported set when the server configures one and all of them otherwise.
	 * OperationOutcome stays out of that set: the publisher owns that file for the outcome property.
	 */
	@Test
	void resolveEntriesFallsBackToTheSupportedRegisteredTypesSorted() {
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		when(daoRegistry.getRegisteredDaoTypes())
				.thenReturn(Set.of("Patient", "Organization", "Binary", "OperationOutcome"));
		when(daoRegistry.isResourceTypeSupported("Patient")).thenReturn(true);
		when(daoRegistry.isResourceTypeSupported("Organization")).thenReturn(true);
		when(daoRegistry.isResourceTypeSupported("OperationOutcome")).thenReturn(true);
		when(daoRegistry.isResourceTypeSupported("Binary")).thenReturn(false);
		PublishService service = new PublishService(null, daoRegistry, null, new PublishProperties(), null);

		assertEquals(
				List.of("Organization", "Patient"),
				service.resolveEntries(),
				"a registered type the server does not support is left out, OperationOutcome is reserved for the"
						+ " outcome property, and the rest are sorted");
	}

	/**
	 * The base type is the identity a published file carries, so an entry keeps naming the same file
	 * whether or not it narrows the export with a filter.
	 */
	@Test
	void baseTypeDropsTheSearchFilterFromAConfiguredEntry() {
		assertEquals("Patient", PublishService.baseType("Patient"));
		assertEquals("Patient", PublishService.baseType("Patient?active=true"));
		assertEquals("Patient", PublishService.baseType("Patient?active=true&gender=male"));
	}

	@Test
	void clearPublishRootDeletesSnapshotsAndThePointerOnly(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		PublishService service = new PublishService(null, null, null, props, null);

		Path snapshot = tmp.resolve("11111111-1111-1111-1111-111111111111");
		Files.createDirectories(snapshot);
		Files.writeString(snapshot.resolve("meta.json"), "{}");
		Path unrelatedDir = tmp.resolve("uploads");
		Files.createDirectories(unrelatedDir);
		Path unrelatedFile = tmp.resolve("notes.txt");
		Files.writeString(unrelatedFile, "keep me");
		Path pointer = tmp.resolve("current");
		Files.writeString(pointer, "11111111-1111-1111-1111-111111111111");

		service.clearPublishRoot();

		assertFalse(Files.exists(snapshot), "a snapshot directory is deleted");
		assertFalse(Files.exists(pointer), "the current pointer is deleted");
		assertTrue(Files.isDirectory(unrelatedDir), "a directory that is not a snapshot id survives");
		assertTrue(Files.exists(unrelatedFile), "a file that is not the pointer survives");
	}

	@Test
	void retentionKeepsOnlyTheNewestWithinTheWindow() {
		Map<String, Instant> idToTransactionTime = Map.of(
				"oldest", Instant.parse("2026-07-01T00:00:00Z"),
				"middle", Instant.parse("2026-07-02T00:00:00Z"),
				"newest", Instant.parse("2026-07-03T00:00:00Z"));

		Set<String> toDelete = PublishService.idsToDelete(
				idToTransactionTime, "newest", 2, FAR_FUTURE_NOW, DEFAULT_GRACE_PERIOD_MS);

		assertEquals(Set.of("oldest"), toDelete);
	}

	@Test
	void retentionNeverDeletesTheCurrentSnapshotEvenWhenOutsideTheWindow() {
		Map<String, Instant> idToTransactionTime = Map.of(
				"stale-current", Instant.parse("2026-01-01T00:00:00Z"),
				"a", Instant.parse("2026-07-04T00:00:00Z"),
				"b", Instant.parse("2026-07-03T00:00:00Z"),
				"c", Instant.parse("2026-07-02T00:00:00Z"));

		Set<String> toDelete = PublishService.idsToDelete(
				idToTransactionTime, "stale-current", 2, FAR_FUTURE_NOW, DEFAULT_GRACE_PERIOD_MS);

		assertEquals(Set.of("c"), toDelete, "the two newest (a, b) are kept by the window; stale-current survives"
				+ " only because it is current; c falls outside both");
	}

	@Test
	void retentionOfZeroOrLessMeansUnlimited() {
		Map<String, Instant> idToTransactionTime =
				Map.of("a", Instant.parse("2026-07-03T00:00:00Z"), "b", Instant.parse("2026-07-02T00:00:00Z"));

		assertTrue(PublishService.idsToDelete(idToTransactionTime, "a", 0, FAR_FUTURE_NOW, DEFAULT_GRACE_PERIOD_MS)
				.isEmpty());
	}

	@Test
	void retentionFloorSparesASnapshotYoungerThanTheGracePeriodEvenOutsideTheCountWindow() {
		Map<String, Instant> idToTransactionTime = Map.of(
				"ancient", Instant.parse("2026-06-09T12:00:00Z"),
				"recent-outside-window", Instant.parse("2026-07-09T11:30:00Z"),
				"b", Instant.parse("2026-07-09T11:59:58Z"),
				"c", Instant.parse("2026-07-09T11:59:59Z"));
		long now = Instant.parse("2026-07-09T12:00:00Z").toEpochMilli();

		Set<String> toDelete = PublishService.idsToDelete(idToTransactionTime, "c", 2, now, DEFAULT_GRACE_PERIOD_MS);

		assertEquals(
				Set.of("ancient"),
				toDelete,
				"recent-outside-window falls outside the retention=2 count window (b and c are newer) but is"
						+ " only 30 minutes old, inside the 1-hour grace period, so it survives; ancient is old enough"
						+ " to be pruned");
	}

	@Test
	void aPrunedByCountSnapshotSurvivesWhileARetainedMetaReferencesIt() {
		Set<String> byCount = Set.of("old");
		Map<String, PublishService.SnapshotMeta> retainedMetas = Map.of(
				"kept",
				new PublishService.SnapshotMeta(
						"2026-07-03T00:00:00Z", List.of(new PublishService.FileMeta("Organization", 5, 900, "old", "d1"))));

		assertTrue(
				PublishService.subtractReferencedIds(byCount, retainedMetas).isEmpty(),
				"old is spared because the kept snapshot's Organization file still lives in it");
	}

	/**
	 * A reused outcome file protects its owning snapshot exactly like a reused output file: the
	 * current manifest addresses that directory, so deleting it would break the outcome URL.
	 */
	@Test
	void aPrunedByCountSnapshotSurvivesWhileARetainedMetaReferencesItThroughAnOutcomeFile() {
		Set<String> byCount = Set.of("old");
		Map<String, PublishService.SnapshotMeta> retainedMetas = Map.of(
				"kept",
				new PublishService.SnapshotMeta(
						"2026-07-03T00:00:00Z",
						List.of(new PublishService.FileMeta("Organization", 5, 900, "kept", "d1")),
						List.of(new PublishService.FileMeta("OperationOutcome", 1, 120, "old", "d2"))));

		assertTrue(
				PublishService.subtractReferencedIds(byCount, retainedMetas).isEmpty(),
				"old is spared because the kept snapshot's outcome file still lives in it");
	}

	@Test
	void aPrunedByCountSnapshotIsDeletedOnceNoRetainedMetaReferencesIt() {
		Set<String> byCount = Set.of("old");
		Map<String, PublishService.SnapshotMeta> retainedMetas = Map.of(
				"kept",
				new PublishService.SnapshotMeta(
						"2026-07-03T00:00:00Z", List.of(new PublishService.FileMeta("Organization", 5, 900, "kept", "d1"))));

		assertEquals(Set.of("old"), PublishService.subtractReferencedIds(byCount, retainedMetas));
	}

	@Test
	void pageBoundaryDefersRowsAtTheMaxInstantToTheDrain() {
		Instant t1 = Instant.parse("2026-07-01T00:00:00.000Z");
		Instant t2 = Instant.parse("2026-07-01T00:00:00.001Z");
		Instant t3 = Instant.parse("2026-07-01T00:00:00.002Z");

		PublishService.PageBoundary boundary = PublishService.pageBoundary(List.of(t1, t2, t3), 10);

		assertEquals(2, boundary.writeThroughIndex(), "rows strictly below the max instant are written directly");
		assertEquals(t3, boundary.maxInstant());
		assertTrue(boundary.isFinalPage(), "a page shorter than the requested size is the last page");
	}

	@Test
	void pageBoundaryDefersTheWholePageWhenEveryRowSharesTheMaxInstant() {
		Instant t = Instant.parse("2026-07-01T00:00:00.000Z");

		PublishService.PageBoundary boundary = PublishService.pageBoundary(List.of(t, t, t), 3);

		assertEquals(0, boundary.writeThroughIndex(), "a cluster spanning the entire page defers all rows to the drain");
		assertEquals(t, boundary.maxInstant());
		assertFalse(boundary.isFinalPage(), "a full page cannot be assumed to be the last page for its type");
	}

	@Test
	void pageBoundaryIsNotFinalWhenThePageIsFull() {
		Instant t1 = Instant.parse("2026-07-01T00:00:00.000Z");
		Instant t2 = Instant.parse("2026-07-01T00:00:00.001Z");

		PublishService.PageBoundary boundary = PublishService.pageBoundary(List.of(t1, t2), 2);

		assertFalse(boundary.isFinalPage(), "a page exactly at the page size must be followed by another query");
	}

	@Test
	void listSnapshotsReturnsNewestFirstWithCurrentFlag(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		ObjectMapper mapper = new ObjectMapper();
		PublishService service = new PublishService(null, null, mapper, props, null);

		writeSnapshotDir(
				tmp,
				mapper,
				"11111111-1111-1111-1111-111111111111",
				"2026-07-01T00:00:00Z",
				List.of(new PublishService.FileMeta(
						"Organization", 1, 100, "11111111-1111-1111-1111-111111111111", "d1")));
		writeSnapshotDir(
				tmp,
				mapper,
				"22222222-2222-2222-2222-222222222222",
				"2026-07-02T00:00:00Z",
				List.of(
						new PublishService.FileMeta(
								"Organization", 2, 200, "22222222-2222-2222-2222-222222222222", "d2"),
						new PublishService.FileMeta("Location", 1, 50, "11111111-1111-1111-1111-111111111111", "d3")));
		Files.writeString(tmp.resolve("current"), "22222222-2222-2222-2222-222222222222");

		List<PublishService.SnapshotListing> listings = service.listSnapshots();

		assertEquals(2, listings.size());
		assertEquals("22222222-2222-2222-2222-222222222222", listings.get(0).id());
		assertTrue(listings.get(0).current());
		assertEquals("2026-07-02T00:00:00Z", listings.get(0).transactionTime());
		assertEquals(2, listings.get(0).files().size());
		assertFalse(listings.get(1).current());
	}

	@Test
	void listSnapshotsSkipsDirectoriesWithUnreadableMeta(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		ObjectMapper mapper = new ObjectMapper();
		PublishService service = new PublishService(null, null, mapper, props, null);

		writeSnapshotDir(
				tmp,
				mapper,
				"11111111-1111-1111-1111-111111111111",
				"2026-07-01T00:00:00Z",
				List.of());
		Path corrupt = tmp.resolve("33333333-3333-3333-3333-333333333333");
		Files.createDirectories(corrupt);
		Files.writeString(corrupt.resolve("meta.json"), "not json");
		Path noMeta = tmp.resolve("44444444-4444-4444-4444-444444444444");
		Files.createDirectories(noMeta);

		List<PublishService.SnapshotListing> listings = service.listSnapshots();

		assertEquals(1, listings.size());
		assertEquals("11111111-1111-1111-1111-111111111111", listings.get(0).id());
	}

	/**
	 * An unreadable meta reads as no snapshot, so a tick whose current pointer names one republishes
	 * from scratch and swaps the pointer to the fresh snapshot.
	 */
	@Test
	void publishTickRecoversWhenTheCurrentSnapshotHasAnUnreadableMeta(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		when(daoRegistry.getRegisteredDaoTypes()).thenReturn(Set.of());
		PublishService service = new PublishService(null, daoRegistry, new ObjectMapper(), props, null);

		String corruptId = "33333333-3333-3333-3333-333333333333";
		Path corrupt = tmp.resolve(corruptId);
		Files.createDirectories(corrupt);
		Files.writeString(corrupt.resolve("meta.json"), "not json");
		Files.writeString(tmp.resolve("current"), corruptId);

		service.publishTick();

		Optional<PublishService.CurrentSnapshot> current = service.currentSnapshot();
		assertTrue(current.isPresent(), "the tick should complete and leave a readable current snapshot");
		assertFalse(
				corruptId.equals(current.get().snapshotId()),
				"the current pointer should move off the snapshot with the unreadable meta");
		// Parses or throws: the swapped-in snapshot carries a valid manifest.
		Instant.parse(current.get().meta().transactionTime());
		assertTrue(current.get().meta().files().isEmpty(), "no configured type publishes no files");
	}

	@Test
	void listSnapshotsIsEmptyBeforeFirstPublish(@TempDir Path tmp) {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.resolve("never-created").toString());
		PublishService service = new PublishService(null, null, new ObjectMapper(), props, null);

		assertTrue(service.listSnapshots().isEmpty());
	}

	/**
	 * A disabled publisher returns before the tick thread starts, so the assertion needs no settle:
	 * the only code path that creates a snapshot has already been skipped when the call returns.
	 */
	@Test
	void onApplicationReadyPublishesNothingWhenDisabled(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		props.setEnabled(false);
		PublishService service = new PublishService(null, null, new ObjectMapper(), props, null);

		service.onApplicationReady();

		try (Stream<Path> children = Files.list(tmp)) {
			assertTrue(children.findAny().isEmpty(), "a disabled publisher creates no snapshot");
		}
	}

	/** The reset runs inline, ahead of the enabled check, so a disabled publisher still performs it. */
	@Test
	void onApplicationReadyClearsPublishedSnapshotsWhenResetOnStartupIsSet(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		props.setResetOnStartup(true);
		props.setEnabled(false);
		PublishService service = new PublishService(null, null, new ObjectMapper(), props, null);

		String seededId = "11111111-1111-1111-1111-111111111111";
		Path snapshot = tmp.resolve(seededId);
		Files.createDirectories(snapshot);
		Files.writeString(snapshot.resolve("meta.json"), "{}");
		Path pointer = tmp.resolve("current");
		Files.writeString(pointer, seededId);

		service.onApplicationReady();

		assertFalse(Files.exists(snapshot), "the seeded snapshot is deleted");
		assertFalse(Files.exists(pointer), "the current pointer is deleted with it");
	}

	/**
	 * The first tick runs off the startup thread, so the call returns before the snapshot exists and
	 * the assertion polls for it.
	 */
	@Test
	void onApplicationReadyPublishesTheFirstSnapshotOffTheStartupThread(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		when(daoRegistry.getRegisteredDaoTypes()).thenReturn(Set.of());
		PublishService service = new PublishService(null, daoRegistry, new ObjectMapper(), props, null);

		service.onApplicationReady();

		long deadline = System.currentTimeMillis() + 10_000;
		Optional<PublishService.CurrentSnapshot> current = service.currentSnapshot();
		while (current.isEmpty() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
			current = service.currentSnapshot();
		}

		assertTrue(current.isPresent(), "the initial tick publishes a snapshot");
	}

	/**
	 * A tick killed mid-export leaves a snapshot directory with no meta.json, which the retention
	 * pass cannot reclaim because it works from the metas. The tick here reaches prune by publishing
	 * its own first snapshot over an empty previous state.
	 */
	@Test
	void pruneDeletesAnOrphanedSnapshotDirectoryPastTheGracePeriod(@TempDir Path tmp) throws Exception {
		PublishProperties props = new PublishProperties();
		props.setStoragePath(tmp.toString());
		DaoRegistry daoRegistry = mock(DaoRegistry.class);
		when(daoRegistry.getRegisteredDaoTypes()).thenReturn(Set.of());
		PublishService service = new PublishService(null, daoRegistry, new ObjectMapper(), props, null);

		Path staleOrphan = tmp.resolve("55555555-5555-5555-5555-555555555555");
		Files.createDirectories(staleOrphan);
		Files.writeString(staleOrphan.resolve("Organization.ndjson.gz"), "partial export");
		Files.setLastModifiedTime(
				staleOrphan, FileTime.fromMillis(System.currentTimeMillis() - 2 * DEFAULT_GRACE_PERIOD_MS));
		Path freshOrphan = tmp.resolve("66666666-6666-6666-6666-666666666666");
		Files.createDirectories(freshOrphan);

		service.publishTick();

		assertFalse(Files.exists(staleOrphan), "an orphan older than the grace period is reclaimed");
		assertTrue(
				Files.isDirectory(freshOrphan),
				"an orphan inside the grace period may still be filling, so it survives");
	}

	private static void writeSnapshotDir(
			Path root,
			ObjectMapper mapper,
			String id,
			String transactionTime,
			List<PublishService.FileMeta> files)
			throws Exception {
		Path dir = root.resolve(id);
		Files.createDirectories(dir);
		mapper.writeValue(
				dir.resolve("meta.json").toFile(), new PublishService.SnapshotMeta(transactionTime, files));
	}
}
