package io.skjaere.debridav.debrid

import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridUsenetService.UsenetDownloadProgressContext
import io.skjaere.debridav.debrid.client.DebridUsenetClient
import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetListItem
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.ffmpeg.VideMetaData
import io.skjaere.debridav.ffmpeg.VideoFileMetaDataService
import io.skjaere.debridav.fs.DebridFsFile
import io.skjaere.debridav.fs.DebridUsenetFileContents
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.sabnzbd.UsenetDownload
import io.skjaere.debridav.sabnzbd.UsenetDownloadStatus
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant
import java.util.*

private const val QUEUE_PROCESSORS = 5

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
class UsenetDownloadQueueProcessor(
    private val debridavConfiguration: DebridavConfiguration,
    private val fileService: FileService,
    private val usenetRepository: UsenetRepository,
    private val debridUsenetClient: DebridUsenetClient,
    private val videoFileMetaDataService: VideoFileMetaDataService
) {
    val inProgressChannel = Channel<UsenetDownloadProgressContext>()

    private val validationChannel = Channel<UsenetDownloadProgressContext>()
    private val completedChannel = Channel<UsenetDownloadProgressContext>()
    private final val queueProcessorScope = CoroutineScope(Dispatchers.IO)

    private val knownVideoContainers = listOf(".mkv", ".mp4", ".mpeg", ".mpg", ".avi")
    private val logger = LoggerFactory.getLogger(UsenetDownloadQueueProcessor::class.java)

    init {
        File("${debridavConfiguration.filePath}/nzbs/").toPath().let {
            if (!it.exists()) {
                it.createParentDirectories()
            }
        }

        queueProcessorScope.launch {
            launch { startInProgressUsenetQueueProcessor() }
            launch { startCompletedUsenetDownloadQueueProcessor() }
            launch { startValidationQueueProcessor() }
        }
    }

    private suspend fun startInProgressUsenetQueueProcessor() {
        repeat(QUEUE_PROCESSORS) {
            inProgressUsenetQueueProcessor()
        }
    }

    private suspend fun inProgressUsenetQueueProcessor() {
        inProgressChannel.consumeEach { validationQueueItem ->
            val updatedDownload = updateInProgressUsenetDownload(validationQueueItem)
            if (validationQueueItem.debridDownload.downloadPresent
                && validationQueueItem.usenetDownload.status != UsenetDownloadStatus.POST_PROCESSING
            ) {
                updatedDownload.status = UsenetDownloadStatus.POST_PROCESSING
                usenetRepository.save(updatedDownload)
                completedChannel.send(validationQueueItem.copy(usenetDownload = updatedDownload))
            } else {
                usenetRepository.save(updatedDownload)
            }
        }
    }

    private suspend fun startValidationQueueProcessor() = coroutineScope {
        repeat(QUEUE_PROCESSORS) {
            launch {
                validationQueueProcessor()
            }
        }
    }

    private suspend fun validationQueueProcessor() {
        validationChannel.consumeEach { validationQueueItem ->
            validateDownload(validationQueueItem)
        }
    }

    private suspend fun validateDownload(usenetDownloadProgressContext: UsenetDownloadProgressContext) {
        logger.info("validation download: ${usenetDownloadProgressContext.usenetDownload}")
        if (usenetDownloadProgressContext.completedDownloads.all { fileIsValid(it.path) }) {
            updateCompletedUsenetDownload(
                usenetDownloadProgressContext.usenetDownload
            )
        } else {
            deleteInvalidFiles(usenetDownloadProgressContext)
        }
    }

    private suspend fun startCompletedUsenetDownloadQueueProcessor() {
        repeat(QUEUE_PROCESSORS) {
            completedUsenetDownloadQueueProcessor()
        }
    }

    private suspend fun completedUsenetDownloadQueueProcessor() {
        completedChannel.consumeEach { validationQueueItem ->
            val debridFiles = validationQueueItem.debridDownload
                .files
                .map {
                    debridUsenetClient
                        .getCachedFilesFromUsenetInfoListItem(
                            it,
                            validationQueueItem.debridDownload.id
                        )
                }.filterIsInstance<CachedFile>()
                .map { file ->
                    createDebridFileFromCompletedDownload(
                        file,
                        validationQueueItem.debridDownload,
                        validationQueueItem.usenetDownload
                    )
                }
            validationChannel.send(validationQueueItem.copy(completedDownloads = debridFiles))
        }
    }

    private suspend fun deleteInvalidFiles(validationQueueItem: UsenetDownloadProgressContext) {
        withContext(Dispatchers.IO) {
            fileService.deleteFilesWithHash(validationQueueItem.debridDownload.hash)
            validationQueueItem.usenetDownload.status = UsenetDownloadStatus.FAILED
            validationQueueItem.usenetDownload.completed = true
            usenetRepository.save(validationQueueItem.usenetDownload)
        }
    }

    private fun updateInProgressUsenetDownload(
        usenetDownloadProgressContext: UsenetDownloadProgressContext
    ): UsenetDownload {

        usenetDownloadProgressContext.usenetDownload.size = usenetDownloadProgressContext.debridDownload.size
        usenetDownloadProgressContext.usenetDownload.percentCompleted =
            usenetDownloadProgressContext.debridDownload.progress
        usenetDownloadProgressContext.usenetDownload.storagePath = usenetDownloadProgressContext.usenetDownload.name
        usenetDownloadProgressContext.usenetDownload.status =
            UsenetDownloadStatus
                .valueFrom(usenetDownloadProgressContext.debridDownload.downloadState.uppercase(Locale.getDefault()))

        return usenetDownloadProgressContext.usenetDownload
    }

    private fun updateCompletedUsenetDownload(
        usenetDownload: UsenetDownload
    ) {
        usenetDownload.completed = true
        usenetDownload.percentCompleted = 1.0
        usenetDownload.status = UsenetDownloadStatus.COMPLETED
        usenetDownload.storagePath = usenetDownload.name

        logger.debug("updating usenet download: ${usenetDownload.debridId}")
        usenetRepository.save(usenetDownload)
    }

    fun createDebridFileFromCompletedDownload(
        file: CachedFile,
        completedDownload: GetUsenetListItem,
        usenetDownload: UsenetDownload
    ): DebridFsFile {
        val debridFileContents = DebridUsenetFileContents(
            originalPath = file.path,
            size = file.size,
            modified = Instant.now().toEpochMilli(),
            debridLinks = mutableListOf(file),
            debridDownloadId = completedDownload.id,
            nzbFileLocation = "${debridavConfiguration.filePath}/nzbs/${completedDownload.id}/bin.nzb",
            hash = completedDownload.hash,
            id = null,
            mimeType = file.mimeType
        )
        val path = replaceRootDirectoryOfFilePathWithReleaseName(file.path, usenetDownload.name!!)
        logger.debug("saving debrid file: $path")

        return fileService.createDebridFile(
            "${debridavConfiguration.downloadPath}/$path",
            debridFileContents
        )
    }

    @Suppress("SwallowedException")
    private suspend fun fileIsValid(path: String): Boolean {
        return if (".${path.substringAfterLast(".")}" in knownVideoContainers) {
            videoIsValid(path)
        } else true
    }

    private suspend fun videoIsValid(path: String): Boolean {
        return when (val videoMetaData = videoFileMetaDataService.getMetadataFromUrl(path)) {
            is VideMetaData.Success -> true
            is VideMetaData.Error -> {
                logger.warn("Unable to read metadata from $path. Message: ${videoMetaData.errorMessage}")
                false
            }
        }
    }

    private fun replaceRootDirectoryOfFilePathWithReleaseName(path: String, releaseName: String): String {
        val parts = path.split("/").toMutableList()
        if (parts.size == 1) {
            return "$releaseName/$path"
        }
        parts[0] = releaseName
        return parts.joinToString("/")
    }
}
