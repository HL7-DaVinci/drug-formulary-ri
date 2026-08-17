package org.hl7.davinci.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a future field added to {@link PublishService.SnapshotMeta} or
 * {@link PublishService.FileMeta} cannot break reading of a meta.json written by an older build.
 */
class SnapshotMetaJsonTest {

	@Test
	void roundTripsAndToleratesUnknownFieldsOnReplay() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta(
				"2026-07-07T15:00:00Z",
				List.of(
						new PublishService.FileMeta("Organization", 3, 456, "snap-1", "d1"),
						new PublishService.FileMeta("Location", 1, 100, "snap-1", "d2")));

		String json = mapper.writeValueAsString(meta);
		PublishService.SnapshotMeta roundTripped = mapper.readValue(json, PublishService.SnapshotMeta.class);
		assertEquals(meta, roundTripped);

		String jsonWithUnknownFields =
				"""
				{
				  "transactionTime": "2026-07-07T15:00:00Z",
				  "bucketVersion": 3,
				  "files": [
				    {"type": "Organization", "count": 3, "fileSize": 456, "snapshotId": "snap-1", "digest": "d1", "bucket": 3},
				    {"type": "Location", "count": 1, "fileSize": 100, "snapshotId": "snap-1", "digest": "d2", "bucket": 3}
				  ]
				}
				""";

		PublishService.SnapshotMeta parsed =
				mapper.readValue(jsonWithUnknownFields, PublishService.SnapshotMeta.class);

		assertEquals(meta, parsed);
	}

	/**
	 * A meta.json with no outcomeFiles field reads back with an empty list rather than null, so
	 * every walk over it stays null-safe.
	 */
	@Test
	void aMetaWithoutOutcomeFilesReadsBackWithAnEmptyList() throws Exception {
		String json =
				"""
				{
				  "transactionTime": "2026-07-07T15:00:00Z",
				  "files": [
				    {"type": "Organization", "count": 3, "fileSize": 456, "snapshotId": "snap-1", "digest": "d1"}
				  ]
				}
				""";

		PublishService.SnapshotMeta parsed = new ObjectMapper().readValue(json, PublishService.SnapshotMeta.class);

		assertEquals(List.of(), parsed.outcomeFiles());
	}

	@Test
	void roundTripsTheOutcomeFilesOfAFailedTick() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		PublishService.SnapshotMeta meta = new PublishService.SnapshotMeta(
				"2026-07-07T15:00:00Z",
				List.of(new PublishService.FileMeta("Organization", 3, 456, "snap-1", "d1")),
				List.of(new PublishService.FileMeta("OperationOutcome", 1, 120, "snap-0", "d2")));

		PublishService.SnapshotMeta roundTripped =
				mapper.readValue(mapper.writeValueAsString(meta), PublishService.SnapshotMeta.class);

		assertEquals(meta, roundTripped);
	}
}
