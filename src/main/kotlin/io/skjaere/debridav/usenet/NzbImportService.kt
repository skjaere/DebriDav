package io.skjaere.debridav.usenet

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.NzbContents
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.NzbImportRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.queue.NzbImportFileJson
import io.skjaere.debridav.usenet.queue.NzbImportRecord
import io.skjaere.debridav.usenet.queue.NzbImportStatus
import io.skjaere.nzbstreamer.nzb.ObfuscationHeuristic
import io.skjaere.debridav.usenet.nzb.NzbArchiveType
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import io.skjaere.debridav.usenet.nzb.NzbFileEntity
import io.skjaere.debridav.usenet.nzb.NzbSegmentEntity
import io.skjaere.debridav.usenet.nzb.SplitInfoJson
import io.skjaere.debridav.usenet.nzb.StreamableFileJson
import io.skjaere.debridav.pgmq.PgmqConfigurationProperties
import io.skjaere.debridav.usenet.pgmq.NzbImportMessage
import io.skjaere.nntp.ArticleNotFoundException
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.*

@Service
@Suppress("LongParameterList")
class NzbImportService(
    private val nzbStreamer: NzbStreamer,
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val usenetRepository: UsenetRepository,
    private val nzbImportRepository: NzbImportRepository,
    private val pgmqClient: PgmqClient,
    private val databaseFileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val pgmqConfigurationProperties: PgmqConfigurationProperties,
    private val poolReadinessGate: NntpPoolReadinessGate,
    private val arrService: ArrService,
    platformTransactionManager: PlatformTransactionManager
) {
    private class ImportTimeoutException(message: String) : Exception(message)

    /** A failure worth retrying (outage, timeout, unexpected error); the consumer retries the message. */
    class RetryableImportException(message: String, cause: Throwable) : RuntimeException(message, cause)

    // Walks `cause` looking for the requested type. Guards against cause-chain cycles
    // (rare but possible — e.g. an exception with itself as its cause) by capping
    // traversal depth.
    private inline fun <reified T : Throwable> Throwable.firstCauseOfType(): T? {
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private val transactionTemplate = TransactionTemplate(platformTransactionManager)
    private val logger = LoggerFactory.getLogger(NzbImportService::class.java)

    companion object {
        // Trailing extensions we'll strip off a release name before appending the
        // streamable file's own extension. Limited to recognised video containers
        // so we don't mistake '.2026' (year) or '-WEB-DL' (encoder tag) for an ext.
        private val VIDEO_CONTAINER_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "ts", "mov", "m4v", "webm",
            "mpg", "mpeg", "vob", "wmv", "flv", "m2ts", "mts",
        )
    }

    /**
     * Queues the import once the caller's transaction commits. pgmq sends on its own connection,
     * which commits at once: sent inside the transaction that saves the download and import rows,
     * a worker could read the message before those rows were visible, skip the import as
     * "deleted" and leave the download QUEUED forever.
     */
    fun scheduleImport(nzbBytes: ByteArray, usenetDownload: UsenetDownload, nzbImportRecordId: Long) {
        val message = NzbImportMessage(
            nzbBytesBase64 = Base64.getEncoder().encodeToString(nzbBytes),
            usenetDownloadId = usenetDownload.id!!,
            nzbImportRecordId = nzbImportRecordId
        )
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    pgmqClient.send("nzb_import", message)
                }
            })
        } else {
            pgmqClient.send("nzb_import", message)
        }
    }

    /**
     * Fails an import whose message ran out of attempts, so Sonarr/Radarr see the failure and
     * search again instead of waiting on an import that shows "processing" forever.
     */
    fun markFailedAfterRetries(taskData: NzbImportTaskData, cause: Throwable) {
        transactionTemplate.executeWithoutResult {
            val reason = "Gave up after ${pgmqConfigurationProperties.maxReadCount} attempts: " +
                (cause.message ?: cause.javaClass.simpleName)
            logger.warn("NZB import {} failed: {}", taskData.nzbImportRecordId, reason)
            usenetRepository.findById(taskData.usenetDownloadId).ifPresent {
                it.status = UsenetDownloadStatus.FAILED
                usenetRepository.save(it)
            }
            nzbImportRepository.findById(taskData.nzbImportRecordId).ifPresent {
                it.status = NzbImportStatus.FAILED
                it.errorMessage = reason
                nzbImportRepository.save(it)
            }
        }
    }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    fun executeImport(taskData: NzbImportTaskData) {
        // Phase 1: Load entities and mark as IMPORTING in a short transaction.
        // We do NOT hold this transaction open during the long NNTP I/O below.
        val downloadName = transactionTemplate.execute {
            // The import record is saved in the transaction that queues this message. If it isn't
            // visible, that transaction hasn't committed (yet): throw, so the consumer retries
            // rather than archiving the message.
            val importRecord = nzbImportRepository.findById(taskData.nzbImportRecordId).orElseThrow {
                IllegalStateException(
                    "NzbImportRecord ${taskData.nzbImportRecordId} not found; retrying in case it isn't committed yet"
                )
            }
            val usenetDownload = usenetRepository.findById(taskData.usenetDownloadId).orElse(null)
                ?: run {
                    logger.warn(
                        "UsenetDownload ${taskData.usenetDownloadId} not found (may have been deleted), " +
                                "skipping import"
                    )
                    return@execute null
                }
            logger.info("Importing ${usenetDownload.name}")
            importRecord.status = NzbImportStatus.IMPORTING
            nzbImportRepository.save(importRecord)
            usenetDownload.name
        } ?: return

        // Phase 2: Perform long-running NNTP I/O outside any database transaction.
        // This prevents the transaction from being held open while waiting for the
        // network, which would cause ObjectOptimisticLockingFailureException if the
        // UsenetDownload row is deleted by another thread during the I/O.
        // Wait for the NNTP pool to settle into its post-startup config before
        // touching it — see NntpPoolReadinessGate for context.
        val nzbBytes = Base64.getDecoder().decode(taskData.nzbBytesBase64)
        val prepared = prepare(nzbBytes, downloadName)
        val prepareResult = prepared.result
        val prepareException = prepared.articleNotFound

        // Phase 3: Re-fetch entities and persist results in a new short transaction.
        // Re-fetching avoids operating on stale/detached entities and gracefully handles
        // the case where the UsenetDownload was deleted while the I/O was running.
        // Returns the arr category to nudge with RefreshMonitoredDownloads on success
        // (post-commit, so the arr's follow-up SABnzbd history poll sees the
        // COMPLETED slot); null on failure or when the import was for a non-arr
        // category.
        val refreshCategory: String? = transactionTemplate.execute {
            val usenetDownload = usenetRepository.findById(taskData.usenetDownloadId).orElse(null)
            val importRecord = nzbImportRepository.findById(taskData.nzbImportRecordId).orElseThrow {
                IllegalStateException("NzbImportRecord not found: ${taskData.nzbImportRecordId}")
            }

            if (usenetDownload == null) {
                logger.warn(
                    "UsenetDownload ${taskData.usenetDownloadId} ('$downloadName') was deleted " +
                            "during import; aborting result persistence"
                )
                importRecord.status = NzbImportStatus.FAILED
                importRecord.errorMessage = "Download was deleted during import"
                nzbImportRepository.save(importRecord)
                return@execute null
            }

            when {
                prepareException != null -> {
                    val reason = prepareException.message ?: prepareException.javaClass.simpleName
                    logger.info("NZB import failed for '{}': {}", usenetDownload.name, reason)
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = prepareException.stackTraceToString()
                }

                prepareResult is PrepareResult.MissingArticles -> {
                    val result = prepareResult as PrepareResult.MissingArticles
                    logger.info("NZB import failed for '{}': missing articles ({})", usenetDownload.name, result.message)
                    logger.warn(
                        "Articles missing from Usenet for '{}': {}",
                        usenetDownload.name,
                        result.message
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = result.message
                }

                prepareResult is PrepareResult.UnsupportedArchive -> {
                    val result = prepareResult as PrepareResult.UnsupportedArchive
                    logger.info(
                        "NZB import failed for '{}': unsupported archive ({})", usenetDownload.name, result.message
                    )
                    logger.warn(
                        "Unsupported archive type for '{}': {}",
                        usenetDownload.name,
                        result.message
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = result.message
                }

                prepareResult is PrepareResult.Encrypted -> {
                    val result = prepareResult as PrepareResult.Encrypted
                    // Expected state, not an error — log at info, no stack trace,
                    // doesn't go to GlitchTip. When Phase 3 lands and a password is
                    // available, this branch will become the retry-with-password
                    // entry point; until then it's just "we know this archive is
                    // encrypted and we can't open it yet".
                    logger.info(
                        "NZB import for '{}' aborted — archive is password-protected ({})",
                        usenetDownload.name, result.message
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = result.message
                }

                prepareResult is PrepareResult.Success -> {
                    val result = prepareResult as PrepareResult.Success
                    val metadata = result.metadata
                    val streamableFiles = nzbStreamer.resolveStreamableFiles(metadata)
                    if (streamableFiles.isEmpty()) {
                        // Defence-in-depth: prepare() returned Success but resolution yielded
                        // zero streamable files. Persisting an empty NzbDocumentEntity here
                        // would surface as the prod symptom (silent COMPLETED with no files,
                        // empty doc later deleted by EmptyNzbDocumentCleanupTask). Mark the
                        // import FAILED so Sonarr/Radarr can re-search.
                        val reason = "Prepare succeeded but produced no streamable files " +
                            "(metadata=${metadata::class.simpleName})"
                        logger.error("NZB import failed for '{}': {}", usenetDownload.name, reason)
                        usenetDownload.status = UsenetDownloadStatus.FAILED
                        importRecord.status = NzbImportStatus.FAILED
                        importRecord.errorMessage = reason
                    } else {
                        // Promote a clean per-file name to release-name when the upload-time
                        // name is obscured. Some uploaders obfuscate the indexer-side download
                        // filename (Prowlarr passes through a hash-named NZB) while leaving
                        // the *inside-the-NZB* filename clean — so the canonical release name
                        // is sitting one layer deeper than addfile/addurl can see.
                        if (streamableFiles.size == 1 &&
                            ObfuscationHeuristic.isProbablyObfuscated(usenetDownload.name!!)
                        ) {
                            val inner = streamableFiles.first().path.substringAfterLast('/')
                            if (!ObfuscationHeuristic.isProbablyObfuscated(inner)) {
                                val promoted = inner.substringBeforeLast('.', missingDelimiterValue = inner)
                                logger.info(
                                    "Promoting release name '{}' → '{}' (single-file NZB had a clean inner name)",
                                    usenetDownload.name, promoted,
                                )
                                usenetDownload.name = promoted
                                // SABnzbd history reports storagePath as `storage`, the
                                // directory the arr imports from; the files land under the
                                // promoted name, so the path has to follow it.
                                usenetDownload.storagePath = "${debridavConfigurationProperties.mountPath}" +
                                    "${debridavConfigurationProperties.downloadPath}/$promoted"
                                // The import history view reads `nzb_import.name` directly —
                                // mirror the promotion onto the import record so Sportarr (and
                                // debridav's own /api/v1/queue UI) show the clean name too.
                                importRecord.name = promoted
                            }
                        }
                        val documentEntity = toDocumentEntity(metadata, streamableFiles)
                        documentEntity.category = usenetDownload.category?.name
                        documentEntity.name = usenetDownload.name
                        documentEntity.downloadId = usenetDownload.id.toString()
                        documentEntity.lastVerified = Instant.now()
                        val savedDocument = nzbDocumentRepository.save(documentEntity)
                        usenetDownload.nzbDocument = savedDocument

                        val basePath = "${debridavConfigurationProperties.downloadPath}/${usenetDownload.name}"
                        val downloadHash = usenetDownload.hash!!
                        // Display filename derivation: when the NZB resolves to exactly one
                        // streamable file, surface it under the release name + original
                        // extension so *arr file-naming sees something parseable instead of
                        // the per-segment yenc/rar obfuscation. originalPath stays as the
                        // raw sf.path because that's the key used by StreamableResourceFactory
                        // to look the file back up inside the NzbDocument.
                        val isSingleFile = savedDocument.streamableFiles.size == 1
                        usenetDownload.debridFiles = databaseFileService.createDebridFiles(
                            savedDocument.streamableFiles.map { sf ->
                                val nzbContents = NzbContents().apply {
                                    nzbDocument = savedDocument
                                    originalPath = sf.path
                                    size = sf.totalSize
                                    modified = Instant.now().toEpochMilli()
                                }
                                val displayName = displayFileNameFor(sf.path, usenetDownload.name!!, isSingleFile)
                                "$basePath/$displayName" to nzbContents
                            },
                            downloadHash,
                        ).toMutableList()

                        usenetDownload.status = UsenetDownloadStatus.COMPLETED
                        importRecord.status = NzbImportStatus.COMPLETED
                        importRecord.size = savedDocument.streamableFiles.sumOf { it.totalSize }
                        importRecord.archiveType = savedDocument.archiveType.name
                        importRecord.files = savedDocument.streamableFiles.map { sf ->
                            val displayName = displayFileNameFor(sf.path, usenetDownload.name!!, isSingleFile)
                            NzbImportFileJson(
                                path = "${debridavConfigurationProperties.downloadPath}" +
                                        "/${usenetDownload.name}/$displayName",
                                size = sf.totalSize
                            )
                        }
                        logger.info("Imported ${usenetDownload.name}")
                    }
                }

                else -> {
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = "Unknown error during import prepare phase"
                }
            }

            usenetRepository.save(usenetDownload)
            nzbImportRepository.save(importRecord)
            if (usenetDownload.status == UsenetDownloadStatus.COMPLETED) {
                usenetDownload.category?.name
            } else {
                null
            }
        }

        if (refreshCategory != null) {
            // After-commit (transactionTemplate.execute returns post-commit). Fire-and-
            // forget — a refresh failure shouldn't disrupt anything; the arr will pick
            // up the import on its normal polling tick instead.
            runBlocking { arrService.refreshMonitoredDownloads(refreshCategory) }
        }
    }

    /** Phase 2's outcome: prepare's result, or the missing-article failure that ends the import. */
    private class Prepared(val result: PrepareResult?, val articleNotFound: Exception?)

    /**
     * Runs the NNTP side of the import. Only a problem with the release itself ends the import:
     * a missing article comes back as [Prepared.articleNotFound], and the other content outcomes
     * as the result. A shutdown interrupt, an NNTP outage, a timeout or an unexpected error is
     * thrown back to the consumer, which retries the message and fails the import once it runs
     * out of attempts. Failing on the first attempt made the arr blocklist releases that were fine.
     */
    private fun prepare(nzbBytes: ByteArray, downloadName: String?): Prepared {
        val timeout = pgmqConfigurationProperties.importTimeout
        val result = try {
            runBlocking {
                poolReadinessGate.await()
                // withTimeoutOrNull cancels the inner coroutine on expiry (no exception
                // escapes the runBlocking) and returns null; surfaced as [ImportTimeoutException].
                withTimeoutOrNull(timeout.toMillis()) {
                    nzbStreamer.prepare(nzbBytes)
                } ?: throw ImportTimeoutException(
                    "NZB import for '$downloadName' exceeded ${timeout.toMinutes()}m timeout " +
                        "and was cancelled to free the worker slot"
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return Prepared(null, missingArticleOrRethrow(e, downloadName))
        }
        if (result is PrepareResult.Failure) {
            logger.error("NNTP failure importing '{}': {}; it will be retried", downloadName, result.message)
            throw RetryableImportException("NNTP failure importing '$downloadName': ${result.message}", result.cause)
        }
        return Prepared(result, null)
    }

    /**
     * [e] when it is a missing article, which ends the import; otherwise throws, so the consumer
     * retries. An interrupt or a foreign cancellation is rethrown as it is.
     *
     * The cause chain is walked rather than checking `e is ArticleNotFoundException`: a missing
     * article thrown inside a Ktor writer coroutine surfaces here wrapped in
     * `ClosedByteChannelException`. A missing article and our own timeout are logged at INFO
     * without a stack trace (expected conditions, nothing for GlitchTip to act on); anything
     * else goes through the ERROR path that sentry-logback reports.
     */
    private fun missingArticleOrRethrow(e: Exception, downloadName: String?): Exception {
        val articleNotFound = e.firstCauseOfType<ArticleNotFoundException>()
        val importTimeout = e.firstCauseOfType<ImportTimeoutException>()
        val interrupted = e.firstCauseOfType<InterruptedException>() != null ||
            e.firstCauseOfType<CancellationException>() != null && importTimeout == null
        when {
            interrupted -> throw e
            articleNotFound != null -> logger.info(
                "NZB import for '{}' aborted — article not found: {}", downloadName, articleNotFound.message
            )
            importTimeout != null -> logger.info(
                "NZB import for '{}' aborted — {}; it will be retried", downloadName, importTimeout.message
            )
            else -> logger.error("Failed to prepare NZB for download '$downloadName'; it will be retried", e)
        }
        return articleNotFound?.let { e }
            ?: throw RetryableImportException("Importing '$downloadName' failed; it will be retried", e)
    }

    /** Strip null bytes — PostgreSQL JSONB rejects \u0000 */
    private fun String.sanitize(): String = replace("\u0000", "")

    /**
     * Pick the filename surfaced on the WebDAV mount for a streamable file inside an NZB.
     *
     * For single-file NZBs (typical of movies / UFC events), return `<releaseName>.<ext>`
     * so file managers and *arr renamers see something parseable instead of the per-rar/
     * yenc-obfuscated name. The original obscured `sf.path` is still stored on
     * `NzbContents.originalPath`, which is what the streamer uses internally to locate
     * bytes — only the display path changes.
     *
     * For multi-file NZBs (TV packs etc.), the release name identifies the *bundle*,
     * not individual files; collapsing N files to one name would conflict, so we keep
     * the originals.
     */
    @Suppress("ReturnCount")
    private fun displayFileNameFor(originalPath: String, releaseName: String, isSingleFile: Boolean): String {
        if (!isSingleFile) return originalPath
        // Only override the original name when it's clearly obfuscated (yenc/rar-style
        // hash). Legitimate filenames inside an NZB (e.g. `Movie.Name.2024.mkv` or
        // a debug `testfile.bin`) are kept as-is — renaming them to the release name
        // would drop information the uploader provided. ObfuscationHeuristic is a
        // SABnzbd-derived check, kept in its own object so it can be unit-tested
        // independently of the import flow.
        val originalFileName = originalPath.substringAfterLast('/')
        if (!ObfuscationHeuristic.isProbablyObfuscated(originalFileName)) return originalPath
        val ext = originalFileName.substringAfterLast('.', missingDelimiterValue = "")
        if (ext.isEmpty()) return originalPath
        val safeName = releaseName.replace('/', '_').replace('\\', '_')
        // Release names sometimes carry a trailing container extension from the
        // multipart upload filename (e.g. '<hash>.mkv.nzb' → '<hash>.mkv' after we
        // strip the .nzb). Strip it so the final file isn't 'release.mkv.mkv'
        // (same-ext) or 'release.mkv.mp4' (mixed-ext, where the inner-archive ext
        // differs from the upload-filename's). Only strips recognised video
        // containers so non-extension trailing tokens like '.2026' or '-XYZ' aren't
        // mistaken for extensions.
        val stripped = stripVideoContainerExtension(safeName)
        return "$stripped.$ext"
    }

    private fun stripVideoContainerExtension(name: String): String {
        val trailing = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (trailing in VIDEO_CONTAINER_EXTENSIONS) name.substringBeforeLast('.') else name
    }

    private fun toDocumentEntity(
        metadata: ExtractedMetadata,
        streamableFiles: List<StreamableFile>
    ): NzbDocumentEntity {
        val entity = NzbDocumentEntity()
        entity.archiveType = NzbArchiveType.from(metadata)
        // Build the relational graph; cascade=ALL on the parent's @OneToMany pulls
        // children through on save without us needing to call any segment/file repos.
        // file_index = position in the original ordered list, preserving the order
        // nzb-streamer relied on for archive reconstruction.
        entity.files = metadata.orderedArchiveNzb.files.mapIndexed { index, file ->
            val fileEntity = NzbFileEntity().apply {
                document = entity
                fileIndex = index
                yencSize = file.yencHeaders!!.size
                yencPartEnd = file.yencHeaders!!.partEnd
            }
            fileEntity.segments = file.segments.map { segment ->
                NzbSegmentEntity().apply {
                    this.file = fileEntity
                    segmentNumber = segment.number
                    articleId = segment.articleId.sanitize()
                    bytes = segment.bytes
                }
            }.toMutableList()
            fileEntity
        }.toMutableList()
        entity.streamableFiles = streamableFiles.map { sf ->
            StreamableFileJson(
                path = sf.path.sanitize(),
                totalSize = sf.totalSize,
                startVolumeIndex = sf.startVolumeIndex,
                startOffsetInVolume = sf.startOffsetInVolume,
                continuationHeaderSize = sf.continuationHeaderSize,
                endOfArchiveSize = sf.endOfArchiveSize,
                preComputedSplits = sf.preComputedSplits?.map {
                    SplitInfoJson(
                        volumeIndex = it.volumeIndex,
                        dataStartPosition = it.dataStartPosition,
                        dataSize = it.dataSize
                    )
                }
            )
        }
        return entity
    }
}
