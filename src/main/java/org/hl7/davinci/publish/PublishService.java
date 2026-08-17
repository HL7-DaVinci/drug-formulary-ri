package org.hl7.davinci.publish;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.common.PathUtils;
import org.hl7.davinci.publish.web.PublishFileController;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Periodically publishes a snapshot of this server's data via the Bulk Data $bulk-publish
 * operation. Each tick exports every configured resource type, narrowed by that entry's search
 * filter when it carries one, with lastUpdated bounded at a transactionTime claimed
 * transaction-lag-ms behind the export start. The lag lets in-flight writes become visible
 * before their timestamps fall inside a claimed window; a database transaction open longer
 * than the lag can miss one snapshot and lands in the next.
 *
 * <p>A type that fails to export, for example one whose configured filter names a search parameter
 * it does not have, is isolated to itself: the tick publishes the other types and reports the
 * failure as an error OperationOutcome in the manifest outcome property.
 */
@Service
public class PublishService {

	private static final Logger ourLog = LoggerFactory.getLogger(PublishService.class);

	public static final String MANIFEST_TYPE =
			"http://hl7.org/fhir/uv/bulkdata/StructureDefinition/BulkPublishManifest";

	/** The resource type of the outcome file, which also names it on disk and in its {@link FileMeta}. */
	private static final String OUTCOME_TYPE = "OperationOutcome";

	private static final String CURRENT_FILE = "current";
	private static final String META_FILE = "meta.json";
	private static final Pattern SNAPSHOT_ID =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private final FhirContext fhirContext;
	private final DaoRegistry daoRegistry;
	private final ObjectMapper objectMapper;
	private final PublishProperties publishProps;
	private final MatchUrlService matchUrlService;

	// With an empty configured list the published set is only known at runtime, so the resolved
	// entries are logged; the guard keeps the log to one line per distinct set instead of per tick.
	private List<String> lastLoggedEntries;

	public PublishService(
			FhirContext fhirContext,
			DaoRegistry daoRegistry,
			ObjectMapper objectMapper,
			PublishProperties publishProps,
			MatchUrlService matchUrlService) {
		this.fhirContext = fhirContext;
		this.daoRegistry = daoRegistry;
		this.objectMapper = objectMapper;
		this.publishProps = publishProps;
		this.matchUrlService = matchUrlService;
	}

	/**
	 * One published file's stats, captured at export time; {@code snapshotId} is the dir that
	 * physically holds it. {@code digest} is the SHA-256 hex of the uncompressed ndjson bytes; a
	 * null digest, as written by another tool, never matches and so forces a republish of the type.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FileMeta(String type, long count, long fileSize, String snapshotId, String digest) {}

	/**
	 * The contents of a snapshot's {@code meta.json}: the files it publishes, plus the outcome files
	 * reporting the types that failed. Unknown fields are ignored on read so a future field added to
	 * this record does not break deserialization of metas written by an older build; a meta written
	 * without a list reads back with an empty one.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SnapshotMeta(String transactionTime, List<FileMeta> files, List<FileMeta> outcomeFiles) {

		public SnapshotMeta {
			files = files == null ? List.of() : files;
			outcomeFiles = outcomeFiles == null ? List.of() : outcomeFiles;
		}

		/** A snapshot whose every configured type exported cleanly. */
		public SnapshotMeta(String transactionTime, List<FileMeta> files) {
			this(transactionTime, files, List.of());
		}
	}

	/** The currently active snapshot: its id (also the ETag) and metadata. */
	public record CurrentSnapshot(String snapshotId, SnapshotMeta meta) {}

	/** One retained snapshot directory on disk: its metadata plus whether it is the active one. */
	public record SnapshotListing(String id, String transactionTime, boolean current, List<FileMeta> files) {}

	/**
	 * When reset-on-startup is enabled the published snapshots are deleted before the first
	 * publish. Otherwise a surviving {@code current} snapshot keeps serving until the first tick
	 * replaces it.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		if (publishProps.isResetOnStartup()) {
			clearPublishRoot();
		}
		if (!publishProps.isEnabled()) {
			return;
		}
		// Off the startup thread: readiness, and under the starter's probe config liveness too,
		// must not wait on the first full export.
		Thread initialTick = new Thread(this::runGuardedTick, "bulk-publish-initial-tick");
		initialTick.setDaemon(true);
		initialTick.start();
	}

	/** The first tick runs without a scheduler around it, so it carries its own failure guard. */
	private void runGuardedTick() {
		try {
			publishTick();
		} catch (Exception e) {
			ourLog.warn("Initial bulk publish tick failed; the scheduled ticks continue", e);
		}
	}

	@Scheduled(
			initialDelayString = "#{@publishProperties.intervalMs}",
			fixedDelayString = "#{@publishProperties.intervalMs}")
	public void scheduledPublish() {
		if (!publishProps.isEnabled()) {
			return;
		}
		publishTick();
	}

	/**
	 * Exports every configured type up to the lagged transaction time and publishes the result
	 * as a new snapshot. A type whose content digest matches the previous snapshot keeps its
	 * previous file and URL; when no type changed, the previous snapshot stays current.
	 *
	 * <p>A failed type contributes no output entry and one error OperationOutcome to the snapshot's
	 * outcome file. That file passes the same digest gate as an exported type, so a failure that
	 * repeats unchanged leaves the snapshot alone while a new, changed or resolved failure
	 * republishes it. An I/O failure of the publish itself is not per-type and aborts the tick.
	 */
	synchronized void publishTick() {
		long startNanos = System.nanoTime();
		Instant transactionTime = Instant.now().minusMillis(publishProps.getTransactionLagMs());
		SnapshotMeta previousMeta = currentSnapshotId().flatMap(this::readMeta).orElse(null);
		if (previousMeta != null
				&& !Instant.parse(previousMeta.transactionTime()).isBefore(transactionTime)) {
			return;
		}
		String snapshotId = UUID.randomUUID().toString();
		Path dir = publishRoot().resolve(snapshotId);
		SystemRequestDetails details = new SystemRequestDetails();
		boolean published = false;
		try {
			Files.createDirectories(dir);
			List<String> entries = resolveEntries();
			if (!entries.equals(lastLoggedEntries)) {
				ourLog.info("Bulk publish covers {}", entries);
				lastLoggedEntries = entries;
			}
			List<FileMeta> previousFiles = previousMeta == null ? List.of() : previousMeta.files();
			List<FileMeta> previousOutcomes = previousMeta == null ? List.of() : previousMeta.outcomeFiles();
			List<FileMeta> files = new ArrayList<>();
			List<String> failures = new ArrayList<>();
			boolean anyChanged = previousMeta != null && typesRemoved(previousMeta, entries);
			for (String entry : entries) {
				String type = baseType(entry);
				Optional<FileMeta> exported = Optional.empty();
				try {
					exported = exportType(dir, snapshotId, entry, transactionTime, details);
				} catch (Exception e) {
					ourLog.warn("Bulk publish export failed for configured entry {}", entry, e);
					failures.add("Failed to export configured entry '" + entry + "': "
							+ e.getClass().getSimpleName() + ". The server log carries the detail.");
					deleteQuietly(dir.resolve(type + ".ndjson.gz"));
				}
				Optional<FileMeta> previousFile = reusedFileMeta(previousFiles, type);
				if (sameContent(exported, previousFile)) {
					previousFile.ifPresent(files::add);
					exported.ifPresent(fm -> deleteQuietly(fileFor(fm)));
				} else {
					anyChanged = true;
					exported.ifPresent(files::add);
				}
			}
			List<FileMeta> outcomeFiles = new ArrayList<>();
			Optional<FileMeta> outcome = writeOutcomeFile(dir, snapshotId, failures);
			Optional<FileMeta> previousOutcome = reusedFileMeta(previousOutcomes, OUTCOME_TYPE);
			if (sameContent(outcome, previousOutcome)) {
				previousOutcome.ifPresent(outcomeFiles::add);
				outcome.ifPresent(fm -> deleteQuietly(fileFor(fm)));
			} else {
				anyChanged = true;
				outcome.ifPresent(outcomeFiles::add);
			}
			if (!anyChanged && previousMeta != null) {
				return;
			}
			writeMeta(dir, new SnapshotMeta(transactionTime.toString(), files, outcomeFiles));
			swapCurrent(snapshotId);
			published = true;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to publish bulk-publish snapshot " + snapshotId, e);
		} finally {
			if (!published) {
				PathUtils.deleteRecursively(dir);
			}
		}
		prune(snapshotId);
		ourLog.info(
				"Bulk publish snapshot {} created in {} ms",
				snapshotId,
				(System.nanoTime() - startNanos) / 1_000_000);
	}

	/**
	 * The entries this tick publishes: the configured list, or every type the server supports when
	 * that list is empty. The DaoRegistry carries the {@code hapi.fhir.supported_resource_types}
	 * limitation when the server sets one, so the empty configuration follows it; sorting keeps the
	 * manifest order stable from tick to tick. OperationOutcome is left out of either source,
	 * including a configured entry added by hand, because the publisher owns that file name for the
	 * manifest outcome property.
	 */
	List<String> resolveEntries() {
		List<String> configured = publishProps.getResourceTypes();
		List<String> entries = configured.isEmpty()
				? daoRegistry.getRegisteredDaoTypes().stream()
						.filter(daoRegistry::isResourceTypeSupported)
						.sorted()
						.toList()
				: configured;
		return entries.stream()
				.filter(entry -> !OUTCOME_TYPE.equals(baseType(entry)))
				.toList();
	}

	/**
	 * The resource type a configured entry publishes: the part before its first {@code ?}, or the
	 * whole entry when it carries no search filter. This is the type the manifest, the file name and
	 * the digest reuse lookup all use; the filter only narrows what the export reads.
	 */
	static String baseType(String entry) {
		int query = entry.indexOf('?');
		return query < 0 ? entry : entry.substring(0, query);
	}

	/**
	 * True when the previous snapshot published a type that is no longer configured. Removing a type
	 * makes the snapshot stale whether or not a remaining type changed: the new snapshot is built
	 * from the configured entries alone, so publishing it drops the removed type from the manifest.
	 */
	static boolean typesRemoved(SnapshotMeta previousMeta, Collection<String> configuredEntries) {
		Set<String> configured =
				configuredEntries.stream().map(PublishService::baseType).collect(Collectors.toSet());
		return previousMeta.files().stream().anyMatch(file -> !configured.contains(file.type()));
	}

	/** True when both sides are present with equal non-null digests, or both are absent. */
	static boolean sameContent(Optional<FileMeta> exported, Optional<FileMeta> previous) {
		if (exported.isEmpty() && previous.isEmpty()) {
			return true;
		}
		return exported.isPresent()
				&& previous.isPresent()
				&& exported.get().digest() != null
				&& exported.get().digest().equals(previous.get().digest());
	}

	private static MessageDigest newSha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private Path fileFor(FileMeta fileMeta) {
		return publishRoot().resolve(fileMeta.snapshotId()).resolve(fileMeta.type() + ".ndjson.gz");
	}

	/** The prior snapshot's {@link FileMeta} for {@code type}, if it had one; absent stays absent. */
	private static Optional<FileMeta> reusedFileMeta(List<FileMeta> previousFiles, String type) {
		return previousFiles.stream().filter(file -> file.type().equals(type)).findFirst();
	}

	/**
	 * Writes one error OperationOutcome per failed entry as this snapshot's outcome file, and
	 * returns its {@link FileMeta} so it takes part in digest reuse like an exported type. A tick
	 * with no failures writes no file.
	 *
	 * <p>The diagnostics name the entry and the exception class and stop there. They carry no
	 * exception message, no timestamp and no id, for two reasons: the bytes feed the content digest,
	 * so a message bearing an elapsed time or an object hash would republish a snapshot on every
	 * tick, and this file is served unauthenticated, so internal detail belongs in the server log
	 * that {@code publishTick} writes instead.
	 */
	private Optional<FileMeta> writeOutcomeFile(Path dir, String snapshotId, List<String> diagnostics)
			throws IOException {
		if (diagnostics.isEmpty()) {
			return Optional.empty();
		}
		IParser parser = fhirContext.newJsonParser().setPrettyPrint(false);
		MessageDigest digest = newSha256();
		long byteSize = 0;
		try (Writer writer = NdjsonFiles.gzipWriter(dir.resolve(OUTCOME_TYPE + ".ndjson.gz"))) {
			for (String diagnostic : diagnostics) {
				IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(fhirContext);
				OperationOutcomeUtil.addIssue(fhirContext, outcome, "error", diagnostic, null, "processing");
				byteSize += writeResource(writer, parser, digest, outcome);
			}
		}
		return Optional.of(new FileMeta(
				OUTCOME_TYPE,
				diagnostics.size(),
				byteSize,
				snapshotId,
				HexFormat.of().formatHex(digest.digest())));
	}

	/**
	 * A resource updated again mid-export past transactionTime drops out of the remaining pages;
	 * the next publish catches it.
	 */
	@SuppressWarnings({"rawtypes"})
	private Optional<FileMeta> exportType(
			Path dir, String snapshotId, String entry, Instant transactionTime, SystemRequestDetails details) {
		String type = baseType(entry);
		IFhirResourceDao dao = daoRegistry.getResourceDao(type);
		IParser parser = fhirContext.newJsonParser().setPrettyPrint(false);
		Path gzFile = dir.resolve(type + ".ndjson.gz");
		int pageSize = publishProps.getExportPageSize();
		MessageDigest digest = newSha256();
		long count = 0;
		long byteSize = 0;
		try {
			try (Writer writer = NdjsonFiles.gzipWriter(gzFile)) {
				Instant watermark = null;
				while (true) {
					SearchParameterMap page =
							pageQuery(filteredQuery(entry, type), watermark, transactionTime, pageSize);
					List<IBaseResource> resources = dao.search(page, details).getResources(0, pageSize);
					if (resources.isEmpty()) {
						break;
					}
					PageBoundary boundary = pageBoundary(lastUpdatedInstants(resources), pageSize);
					for (IBaseResource resource : resources.subList(0, boundary.writeThroughIndex())) {
						byteSize += writeResource(writer, parser, digest, resource);
						count++;
					}
					WriteCount drained = drainInstant(
							dao,
							filteredQuery(entry, type),
							boundary.maxInstant(),
							details,
							parser,
							writer,
							digest,
							pageSize);
					count += drained.count();
					byteSize += drained.byteSize();
					watermark = boundary.maxInstant();
					if (boundary.isFinalPage()) {
						break;
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to export " + type + " for publish", e);
		}
		if (count == 0) {
			deleteQuietly(gzFile);
			return Optional.empty();
		}
		return Optional.of(
				new FileMeta(type, count, byteSize, snapshotId, HexFormat.of().formatHex(digest.digest())));
	}

	/**
	 * The entry's own search criteria as a fresh query map, which both the page read and the drain
	 * start from so a filter narrows every read of the type. An entry with no filter starts from an
	 * empty map that reads the whole type.
	 */
	private SearchParameterMap filteredQuery(String entry, String type) {
		if (entry.indexOf('?') < 0) {
			return new SearchParameterMap();
		}
		return matchUrlService.translateMatchUrl(entry, fhirContext.getResourceDefinition(type));
	}

	/**
	 * Narrows {@code map} to one export page. The paging settings are applied over whatever the
	 * entry's filter produced, so the lastUpdated window, the sort and the page size the exporter
	 * owns always win.
	 */
	private static SearchParameterMap pageQuery(
			SearchParameterMap map, Instant watermark, Instant transactionTime, int pageSize) {
		DateRangeParam range = new DateRangeParam().setUpperBoundInclusive(Date.from(transactionTime));
		if (watermark != null) {
			range.setLowerBoundExclusive(Date.from(watermark));
		}
		map.setSort(new SortSpec(Constants.PARAM_LASTUPDATED).setOrder(SortOrderEnum.ASC))
				.setCount(pageSize)
				.setLoadSynchronousUpTo(pageSize);
		map.setLastUpdated(range);
		return map;
	}

	private static List<Instant> lastUpdatedInstants(List<IBaseResource> resources) {
		return resources.stream()
				.map(resource -> resource.getMeta().getLastUpdated().toInstant())
				.toList();
	}

	/**
	 * One page's write/drain split: rows before {@code writeThroughIndex} are strictly below
	 * {@code maxInstant} and safe to write directly (ascending sort guarantees no later page
	 * revisits them); rows from there on share {@code maxInstant} and are drained separately so an
	 * instant cluster larger than the page is never partially written. {@code isFinalPage} is true
	 * once the page came back shorter than requested, meaning nothing follows past this instant.
	 */
	public record PageBoundary(int writeThroughIndex, Instant maxInstant, boolean isFinalPage) {}

	/**
	 * Pure page-boundary decision over one page's ascending lastUpdated instants, kept separate
	 * from the DAO calls so it is directly testable over fake pages.
	 */
	public static PageBoundary pageBoundary(List<Instant> ascendingLastUpdated, int pageSize) {
		Instant max = ascendingLastUpdated.get(ascendingLastUpdated.size() - 1);
		int writeThroughIndex = 0;
		while (writeThroughIndex < ascendingLastUpdated.size()
				&& ascendingLastUpdated.get(writeThroughIndex).isBefore(max)) {
			writeThroughIndex++;
		}
		return new PageBoundary(writeThroughIndex, max, ascendingLastUpdated.size() < pageSize);
	}

	private record WriteCount(long count, long byteSize) {}

	/**
	 * Drains every row matching {@code map} at one lastUpdated instant via a pinned, non-synchronous
	 * search paged by offset: a synchronous search ignores the offset, and an instant's cluster can
	 * exceed one page.
	 */
	@SuppressWarnings({"rawtypes"})
	private WriteCount drainInstant(
			IFhirResourceDao dao,
			SearchParameterMap map,
			Instant instant,
			SystemRequestDetails details,
			IParser parser,
			Writer writer,
			MessageDigest digest,
			int pageSize)
			throws IOException {
		map.setSort(new SortSpec(Constants.PARAM_LASTUPDATED).setOrder(SortOrderEnum.ASC));
		map.setLastUpdated(new DateRangeParam()
				.setLowerBoundInclusive(Date.from(instant))
				.setUpperBoundInclusive(Date.from(instant)));
		IBundleProvider provider = dao.search(map, details);
		long count = 0;
		long byteSize = 0;
		int offset = 0;
		while (true) {
			List<IBaseResource> batch = provider.getResources(offset, offset + pageSize);
			if (batch.isEmpty()) {
				break;
			}
			for (IBaseResource resource : batch) {
				byteSize += writeResource(writer, parser, digest, resource);
				count++;
			}
			offset += batch.size();
			if (batch.size() < pageSize) {
				break;
			}
		}
		return new WriteCount(count, byteSize);
	}

	private static long writeResource(Writer writer, IParser parser, MessageDigest digest, IBaseResource resource)
			throws IOException {
		String json = parser.encodeResourceToString(resource);
		writer.write(json);
		writer.write("\n");
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		digest.update(bytes);
		digest.update((byte) '\n');
		return bytes.length + 1;
	}

	private void writeMeta(Path dir, SnapshotMeta meta) throws IOException {
		objectMapper.writeValue(dir.resolve(META_FILE).toFile(), meta);
	}

	private void swapCurrent(String snapshotId) throws IOException {
		Path root = publishRoot();
		Path tmp = Files.createTempFile(root, "current", ".tmp");
		Files.writeString(tmp, snapshotId, StandardCharsets.UTF_8);
		Files.move(
				tmp, root.resolve(CURRENT_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private void prune(String currentId) {
		int retention = publishProps.getRetention();
		Path root = publishRoot();
		Map<String, SnapshotMeta> idToMeta = readAllMetas();
		Map<String, Instant> idToTransactionTime = new HashMap<>();
		idToMeta.forEach((id, meta) -> idToTransactionTime.put(id, Instant.parse(meta.transactionTime())));

		Set<String> byCount = idsToDelete(
				idToTransactionTime, currentId, retention, System.currentTimeMillis(), publishProps.getGracePeriodMs());
		Map<String, SnapshotMeta> retainedMetas = new HashMap<>(idToMeta);
		byCount.forEach(retainedMetas::remove);

		for (String id : subtractReferencedIds(byCount, retainedMetas)) {
			PathUtils.deleteRecursively(root.resolve(id));
		}
		deleteOrphanedSnapshotDirs(idToMeta.keySet(), currentId);
	}

	/**
	 * Deletes snapshot directories that carry no readable {@code meta.json} and are older than the
	 * grace period. A tick killed mid-export leaves such a directory behind, and retention works from
	 * the metas, so nothing else reclaims it. The grace period spares a directory a running export is
	 * still filling.
	 */
	private void deleteOrphanedSnapshotDirs(Set<String> idsWithMeta, String currentId) {
		Path root = publishRoot();
		if (!Files.isDirectory(root)) {
			return;
		}
		long floor = System.currentTimeMillis() - publishProps.getGracePeriodMs();
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.filter(Files::isDirectory).toList()) {
				String id = child.getFileName().toString();
				if (idsWithMeta.contains(id)
						|| id.equals(currentId)
						|| !SNAPSHOT_ID.matcher(id).matches()) {
					continue;
				}
				if (Files.getLastModifiedTime(child).toMillis() < floor) {
					PathUtils.deleteRecursively(child);
				}
			}
		} catch (IOException ignored) {
			// best-effort; a listing failure leaves the orphan for a later tick
		}
	}

	/**
	 * Pure retention decision: keep the newest {@code retention} snapshots by transaction time, and
	 * always keep {@code currentId} regardless of its position. {@code retention <= 0} means unlimited.
	 * A snapshot younger than {@code nowMillis - gracePeriodMs} is never nominated for deletion even
	 * if it falls outside the count window, giving in-flight file URLs time to age out of use.
	 */
	public static Set<String> idsToDelete(
			Map<String, Instant> idToTransactionTime,
			String currentId,
			int retention,
			long nowMillis,
			long gracePeriodMs) {
		if (retention <= 0) {
			return Set.of();
		}
		Set<String> keep = idToTransactionTime.entrySet().stream()
				.sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
				.map(Map.Entry::getKey)
				.limit(retention)
				.collect(Collectors.toCollection(HashSet::new));
		if (currentId != null) {
			keep.add(currentId);
		}
		long floor = nowMillis - gracePeriodMs;
		Set<String> toDelete = new HashSet<>();
		idToTransactionTime.forEach((id, transactionTime) -> {
			if (!keep.contains(id) && transactionTime.toEpochMilli() < floor) {
				toDelete.add(id);
			}
		});
		return toDelete;
	}

	/**
	 * Pure composition over {@link #idsToDelete}: a snapshot dir otherwise due for deletion survives
	 * if any RETAINED snapshot's meta (one not itself in {@code candidates}) still references it via
	 * a {@link FileMeta#snapshotId()}, whether from a published file or from an outcome file.
	 */
	public static Set<String> subtractReferencedIds(Set<String> candidates, Map<String, SnapshotMeta> retainedMetas) {
		Set<String> referenced = retainedMetas.values().stream()
				.flatMap(meta -> Stream.concat(meta.files().stream(), meta.outcomeFiles().stream()))
				.map(FileMeta::snapshotId)
				.collect(Collectors.toSet());
		Set<String> result = new HashSet<>(candidates);
		result.removeAll(referenced);
		return result;
	}

	/** The active snapshot's id and metadata, or empty when no snapshot has been published yet. */
	public Optional<CurrentSnapshot> currentSnapshot() {
		return currentSnapshotId().flatMap(id -> readMeta(id).map(meta -> new CurrentSnapshot(id, meta)));
	}

	/** Retained snapshots on disk, newest first; directories with unreadable meta are skipped. */
	public List<SnapshotListing> listSnapshots() {
		String currentId = currentSnapshotId().orElse(null);
		return readAllMetas().entrySet().stream()
				.sorted(Map.Entry.<String, SnapshotMeta>comparingByValue(
								Comparator.comparing(meta -> Instant.parse(meta.transactionTime())))
						.reversed())
				.map(e -> new SnapshotListing(
						e.getKey(),
						e.getValue().transactionTime(),
						e.getKey().equals(currentId),
						e.getValue().files()))
				.toList();
	}

	/** Metadata for every snapshot directory under the publish root; unreadable metas are skipped. */
	private Map<String, SnapshotMeta> readAllMetas() {
		Map<String, SnapshotMeta> idToMeta = new HashMap<>();
		Path root = publishRoot();
		if (!Files.isDirectory(root)) {
			return idToMeta;
		}
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.filter(Files::isDirectory).toList()) {
				String id = child.getFileName().toString();
				readMeta(id).ifPresent(meta -> idToMeta.put(id, meta));
			}
		} catch (IOException ignored) {
			// best-effort; a listing failure yields an empty map
		}
		return idToMeta;
	}

	/**
	 * Render the manifest body for a snapshot; a pure function of its metadata, so it is directly
	 * testable. Each file's URL is built from its OWNING snapshotId, not the manifest's own, so a
	 * type reused unchanged from an earlier snapshot keeps a byte-identical URL.
	 */
	public BulkPublishManifestJson render(SnapshotMeta meta, String baseUrl) {
		String updateCadence = Duration.ofMillis(publishProps.getIntervalMs()).toString();
		List<BulkPublishManifestJson.OutputEntry> output = meta.files().stream()
				.map(file -> new BulkPublishManifestJson.OutputEntry(
						file.type(),
						baseUrl + PublishFileController.BASE_PATH + "/" + file.snapshotId() + "/" + file.type()
								+ ".ndjson",
						file.count(),
						file.fileSize()))
				.toList();
		List<BulkPublishManifestJson.OutcomeEntry> outcome = meta.outcomeFiles().stream()
				.map(file -> new BulkPublishManifestJson.OutcomeEntry(
						baseUrl + PublishFileController.BASE_PATH + "/" + file.snapshotId() + "/" + file.type()
								+ ".ndjson",
						file.count(),
						file.fileSize()))
				.toList();
		return new BulkPublishManifestJson(
				MANIFEST_TYPE, meta.transactionTime(), updateCadence, false, output, outcome);
	}

	private Optional<String> currentSnapshotId() {
		Path pointer = publishRoot().resolve(CURRENT_FILE);
		if (!Files.exists(pointer)) {
			return Optional.empty();
		}
		try {
			String id = Files.readString(pointer, StandardCharsets.UTF_8).trim();
			return id.isBlank() ? Optional.empty() : Optional.of(id);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read publish pointer", e);
		}
	}

	private Optional<SnapshotMeta> readMeta(String snapshotId) {
		Path metaFile = publishRoot().resolve(snapshotId).resolve(META_FILE);
		if (!Files.exists(metaFile)) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(metaFile.toFile(), SnapshotMeta.class));
		} catch (IOException e) {
			// An unreadable meta reads as no snapshot, so the next tick republishes over it and the
			// provider answers 503.
			ourLog.warn("Ignoring unreadable publish snapshot meta {}", snapshotId, e);
			return Optional.empty();
		}
	}

	private Path publishRoot() {
		return Path.of(publishProps.getStoragePath());
	}

	/**
	 * Deletes the published snapshots and the {@code current} pointer, leaving every other entry
	 * under the storage path untouched. The storage path can be a directory the server shares with
	 * other data, so only entries this service creates are removed.
	 */
	void clearPublishRoot() {
		Path root = publishRoot();
		if (!Files.isDirectory(root)) {
			return;
		}
		deleteQuietly(root.resolve(CURRENT_FILE));
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.toList()) {
				if (Files.isDirectory(child)
						&& SNAPSHOT_ID.matcher(child.getFileName().toString()).matches()) {
					PathUtils.deleteRecursively(child);
				}
			}
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}
}
