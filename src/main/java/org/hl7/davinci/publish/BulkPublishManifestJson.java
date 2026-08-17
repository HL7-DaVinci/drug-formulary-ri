package org.hl7.davinci.publish;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The served $bulk-publish manifest body (Bulk Data $bulk-publish operation). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BulkPublishManifestJson(
		String manifestType,
		String transactionTime,
		String updateCadence,
		boolean requiresAccessToken,
		List<OutputEntry> output,
		List<OutcomeEntry> outcome) {

	public record OutputEntry(String type, String url, long count, long fileSize) {}

	/**
	 * One file of OperationOutcome resources reporting on this publication. The manifest defines no
	 * resource type on an outcome entry: the file always holds OperationOutcomes.
	 */
	public record OutcomeEntry(String url, long count, long fileSize) {}
}
