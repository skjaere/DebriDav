package io.skjaere.debridav.usenet

import com.github.kagkarlsson.scheduler.SchedulerClient
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.NzbContents
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.NzbImportTaskConfiguration.Companion.NZB_IMPORT_DESCRIPTOR
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import io.skjaere.debridav.usenet.nzb.NzbFileJson
import io.skjaere.debridav.usenet.nzb.NzbSegmentJson
import io.skjaere.debridav.usenet.nzb.NzbStreamableFileEntity
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*

@Service
@ConditionalOnProperty("nntp.enabled", havingValue = "true")
class NzbImportService(
    private val nzbStreamer: NzbStreamer,
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val usenetRepository: UsenetRepository,
    @Lazy private val schedulerClient: SchedulerClient,
    private val databaseFileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(NzbImportService::class.java)

    fun scheduleImport(nzbBytes: ByteArray, usenetDownload: UsenetDownload) {
        val taskData = NzbImportTaskData(
            nzbBytesBase64 = Base64.getEncoder().encodeToString(nzbBytes),
            usenetDownloadId = usenetDownload.id!!
        )
        schedulerClient.scheduleIfNotExists(
            NZB_IMPORT_DESCRIPTOR
                .instance(UUID.randomUUID().toString())
                .data(taskData).scheduledTo(Instant.now())
        )
    }

    fun executeImport(taskData: NzbImportTaskData) {
        val usenetDownload = usenetRepository.findById(taskData.usenetDownloadId).orElseThrow {
            IllegalStateException("UsenetDownload not found: ${taskData.usenetDownloadId}")
        }
        try {
            val nzbBytes = Base64.getDecoder().decode(taskData.nzbBytesBase64)
            val metadata = runBlocking { nzbStreamer.prepare(nzbBytes) }
            val streamableFiles = nzbStreamer.resolveStreamableFiles(metadata)
            val documentEntity = toDocumentEntity(metadata.orderedArchiveNzb, streamableFiles)
            val savedDocument = nzbDocumentRepository.save(documentEntity)

            usenetDownload.debridFiles = savedDocument.streamableFiles.map { streamableFileEntity ->
                val nzbContents = NzbContents().apply {
                    nzbDocument = savedDocument
                    streamableFile = streamableFileEntity
                    originalPath = streamableFileEntity.path
                    size = streamableFileEntity.totalSize
                    modified = Instant.now().toEpochMilli()
                }
                databaseFileService.createDebridFile(
                    "${debridavConfigurationProperties.downloadPath}/${usenetDownload.name}/${streamableFileEntity.path}",
                    usenetDownload.hash!!,
                    nzbContents
                )
            }.toMutableList()

            usenetDownload.status = UsenetDownloadStatus.COMPLETED
        } catch (e: Exception) {
            logger.error("Failed to import NZB for download '${usenetDownload.name}'", e)
            usenetDownload.status = UsenetDownloadStatus.FAILED
        } finally {
            usenetRepository.save(usenetDownload)
        }
    }

    private fun toDocumentEntity(
        nzbDocument: NzbDocument,
        streamableFiles: List<StreamableFile>
    ): NzbDocumentEntity {
        val entity = NzbDocumentEntity()
        entity.files = nzbDocument.files.map { file ->
            NzbFileJson(
                yencSize = file.yencHeaders!!.size,
                yencPartEnd = file.yencHeaders!!.partEnd,
                segments = file.segments.map { segment ->
                    NzbSegmentJson(
                        articleId = segment.articleId,
                        number = segment.number,
                        bytes = segment.bytes
                    )
                }
            )
        }
        entity.streamableFiles = streamableFiles.map { sf ->
            NzbStreamableFileEntity().also {
                it.document = entity
                it.path = sf.path
                it.totalSize = sf.totalSize
                it.startVolumeIndex = sf.startVolumeIndex
                it.startOffsetInVolume = sf.startOffsetInVolume
                it.continuationHeaderSize = sf.continuationHeaderSize
                it.endOfArchiveSize = sf.endOfArchiveSize
            }
        }.toMutableList()
        return entity
    }
}
