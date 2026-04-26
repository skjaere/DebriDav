package io.skjaere.debridav.usenet

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.NzbContents
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.NzbImportRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.queue.NzbImportFileJson
import io.skjaere.debridav.usenet.queue.NzbImportRecord
import io.skjaere.debridav.usenet.queue.NzbImportStatus
import io.skjaere.debridav.usenet.nzb.NzbArchiveType
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import io.skjaere.debridav.usenet.nzb.NzbFileJson
import io.skjaere.debridav.usenet.nzb.NzbSegmentJson
import io.skjaere.debridav.usenet.nzb.SplitInfoJson
import io.skjaere.debridav.usenet.nzb.StreamableFileJson
import io.skjaere.debridav.usenet.pgmq.NzbImportMessage
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.*

@Service
class NzbImportService(
    private val nzbStreamer: NzbStreamer,
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val usenetRepository: UsenetRepository,
    private val nzbImportRepository: NzbImportRepository,
    private val pgmqClient: PgmqClient,
    private val databaseFileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    platformTransactionManager: PlatformTransactionManager
) {
    private val transactionTemplate = TransactionTemplate(platformTransactionManager)
    private val logger = LoggerFactory.getLogger(NzbImportService::class.java)

    fun scheduleImport(nzbBytes: ByteArray, usenetDownload: UsenetDownload, nzbImportRecordId: Long) {
        pgmqClient.send(
            "nzb_import",
            NzbImportMessage(
                nzbBytesBase64 = Base64.getEncoder().encodeToString(nzbBytes),
                usenetDownloadId = usenetDownload.id!!,
                nzbImportRecordId = nzbImportRecordId
            )
        )
    }

    @Suppress("LongMethod", "ReturnCount")
    fun executeImport(taskData: NzbImportTaskData) {
        // Phase 1: Load entities and mark as IMPORTING in a short transaction.
        // We do NOT hold this transaction open during the long NNTP I/O below.
        val downloadName = transactionTemplate.execute {
            val usenetDownload = usenetRepository.findById(taskData.usenetDownloadId).orElse(null)
                ?: run {
                    logger.warn(
                        "UsenetDownload ${taskData.usenetDownloadId} not found (may have been deleted), " +
                                "skipping import"
                    )
                    return@execute null
                }
            val importRecord = nzbImportRepository.findById(taskData.nzbImportRecordId).orElseThrow {
                IllegalStateException("NzbImportRecord not found: ${taskData.nzbImportRecordId}")
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
        val nzbBytes = Base64.getDecoder().decode(taskData.nzbBytesBase64)
        var prepareResult: PrepareResult? = null
        var prepareException: Exception? = null
        try {
            prepareResult = runBlocking { nzbStreamer.prepare(nzbBytes) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("Failed to prepare NZB for download '$downloadName'", e)
            prepareException = e
        }

        // Phase 3: Re-fetch entities and persist results in a new short transaction.
        // Re-fetching avoids operating on stale/detached entities and gracefully handles
        // the case where the UsenetDownload was deleted while the I/O was running.
        transactionTemplate.execute {
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
                return@execute
            }

            when {
                prepareException != null -> {
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = prepareException.stackTraceToString()
                }

                prepareResult is PrepareResult.MissingArticles -> {
                    val result = prepareResult as PrepareResult.MissingArticles
                    logger.warn(
                        "Articles missing from Usenet for '{}': {}",
                        usenetDownload.name,
                        result.message
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = result.message
                }

                prepareResult is PrepareResult.Failure -> {
                    val result = prepareResult as PrepareResult.Failure
                    logger.error(
                        "NNTP failure importing '{}': {}",
                        usenetDownload.name,
                        result.message,
                        result.cause
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = result.cause.stackTraceToString()
                }

                prepareResult is PrepareResult.UnsupportedArchive -> {
                    val result = prepareResult as PrepareResult.UnsupportedArchive
                    logger.warn(
                        "Unsupported archive type for '{}': {}",
                        usenetDownload.name,
                        result.message
                    )
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = result.message
                }

                prepareResult is PrepareResult.Success -> {
                    val result = prepareResult as PrepareResult.Success
                    val metadata = result.metadata
                    val streamableFiles = nzbStreamer.resolveStreamableFiles(metadata)
                    val documentEntity = toDocumentEntity(metadata, streamableFiles)
                    documentEntity.category = usenetDownload.category?.name
                    documentEntity.name = usenetDownload.name
                    documentEntity.downloadId = usenetDownload.id.toString()
                    documentEntity.lastVerified = Instant.now()
                    val savedDocument = nzbDocumentRepository.save(documentEntity)
                    usenetDownload.nzbDocument = savedDocument

                    val basePath = "${debridavConfigurationProperties.downloadPath}/${usenetDownload.name}"
                    val downloadHash = usenetDownload.hash!!
                    usenetDownload.debridFiles = databaseFileService.createDebridFiles(
                        savedDocument.streamableFiles.map { sf ->
                            val nzbContents = NzbContents().apply {
                                nzbDocument = savedDocument
                                originalPath = sf.path
                                size = sf.totalSize
                                modified = Instant.now().toEpochMilli()
                            }
                            "$basePath/${sf.path}" to nzbContents
                        },
                        downloadHash,
                    ).toMutableList()

                    usenetDownload.status = UsenetDownloadStatus.COMPLETED
                    importRecord.status = NzbImportStatus.COMPLETED
                    importRecord.size = savedDocument.streamableFiles.sumOf { it.totalSize }
                    importRecord.archiveType = savedDocument.archiveType.name
                    importRecord.files = savedDocument.streamableFiles.map { sf ->
                        NzbImportFileJson(
                            path = "${debridavConfigurationProperties.downloadPath}" +
                                    "/${usenetDownload.name}/${sf.path}",
                            size = sf.totalSize
                        )
                    }
                    logger.info("Imported ${usenetDownload.name}")
                }

                else -> {
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    importRecord.status = NzbImportStatus.FAILED
                    importRecord.errorMessage = "Unknown error during import prepare phase"
                }
            }

            usenetRepository.save(usenetDownload)
            nzbImportRepository.save(importRecord)
        }
    }

    /** Strip null bytes — PostgreSQL JSONB rejects \u0000 */
    private fun String.sanitize(): String = replace("\u0000", "")

    private fun toDocumentEntity(
        metadata: ExtractedMetadata,
        streamableFiles: List<StreamableFile>
    ): NzbDocumentEntity {
        val entity = NzbDocumentEntity()
        entity.archiveType = NzbArchiveType.from(metadata)
        entity.files = metadata.orderedArchiveNzb.files.map { file ->
            NzbFileJson(
                yencSize = file.yencHeaders!!.size,
                yencPartEnd = file.yencHeaders!!.partEnd,
                segments = file.segments.map { segment ->
                    NzbSegmentJson(
                        articleId = segment.articleId.sanitize(),
                        number = segment.number,
                        bytes = segment.bytes
                    )
                }
            )
        }
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
