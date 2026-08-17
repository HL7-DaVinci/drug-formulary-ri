package org.hl7.davinci.publish;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bound from {@code publish.*}: periodic publish of this server's data via the Bulk Data
 * $bulk-publish operation. Bean name {@code publishProperties} is referenced by SpEL in
 * {@link PublishService}.
 */
@Component
@ConfigurationProperties(prefix = "publish")
public class PublishProperties {

	private boolean enabled = true;

	private long intervalMs = 60_000;

	/**
	 * How far behind the export start each snapshot claims its transactionTime. A write whose
	 * database transaction stays open longer than this lag can miss one snapshot and lands in
	 * the next.
	 */
	private long transactionLagMs = 60_000;

	/**
	 * Resource types exported on every tick. An entry is a resource type optionally followed by
	 * search parameters in match URL form ({@code Organization}, {@code Patient?active=true}); the
	 * parameters narrow the export and the base type names the published file. An empty list
	 * publishes every type the server supports, which includes heavy types such as Binary and
	 * Bundle; narrow it with {@code hapi.fhir.supported_resource_types} or by pinning the types
	 * here.
	 *
	 * <p>A type missing from a manifest {@code output} array while {@code outcome} is non-empty
	 * failed to export in that snapshot; it does not mean the server holds no data for the type.
	 */
	private List<String> resourceTypes = new ArrayList<>();

	/** Snapshot directories kept on disk after a publish; the grace period for prior file URLs. */
	private int retention = 3;

	/** Floor under retention: a snapshot younger than this is never pruned regardless of the count window. */
	private long gracePeriodMs = 3_600_000;

	/** Resources read per page during a type's streaming export. */
	private int exportPageSize = 1000;

	/** Directory under which snapshot directories are written. */
	private String storagePath = "./publish-data";

	/** Absolute base URL used in manifest file URLs; when blank the URL is rebuilt from the request. */
	private String publicBaseUrl;

	/**
	 * Delete the published snapshots under the storage path on startup, before the first publish.
	 * Other entries under that path are left alone. When false, a surviving {@code current}
	 * snapshot keeps serving across a restart until the first tick replaces it.
	 */
	private boolean resetOnStartup = false;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public long getIntervalMs() {
		return intervalMs;
	}

	public void setIntervalMs(long intervalMs) {
		this.intervalMs = intervalMs;
	}

	public long getTransactionLagMs() {
		return transactionLagMs;
	}

	public void setTransactionLagMs(long transactionLagMs) {
		this.transactionLagMs = transactionLagMs;
	}

	public List<String> getResourceTypes() {
		return resourceTypes;
	}

	public void setResourceTypes(List<String> resourceTypes) {
		this.resourceTypes = resourceTypes;
	}

	public int getRetention() {
		return retention;
	}

	public void setRetention(int retention) {
		this.retention = retention;
	}

	public long getGracePeriodMs() {
		return gracePeriodMs;
	}

	public void setGracePeriodMs(long gracePeriodMs) {
		this.gracePeriodMs = gracePeriodMs;
	}

	public int getExportPageSize() {
		return exportPageSize;
	}

	public void setExportPageSize(int exportPageSize) {
		this.exportPageSize = exportPageSize;
	}

	public String getStoragePath() {
		return storagePath;
	}

	public void setStoragePath(String storagePath) {
		this.storagePath = storagePath;
	}

	public String getPublicBaseUrl() {
		return publicBaseUrl;
	}

	public void setPublicBaseUrl(String publicBaseUrl) {
		this.publicBaseUrl = publicBaseUrl;
	}

	public boolean isResetOnStartup() {
		return resetOnStartup;
	}

	public void setResetOnStartup(boolean resetOnStartup) {
		this.resetOnStartup = resetOnStartup;
	}
}
