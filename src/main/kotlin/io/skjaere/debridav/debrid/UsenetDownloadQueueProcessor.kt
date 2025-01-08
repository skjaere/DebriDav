package io.skjaere.debridav.debrid

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.head
import io.ktor.http.isSuccess
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridUsenetDownloadService.UsenetDownloadProgressContext
import io.skjaere.debridav.debrid.client.DebridUsenetClient
import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetListItem
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadInfo
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadNotFound
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.SuccessfulDownloadInfo
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.ffmpeg.VideMetaData
import io.skjaere.debridav.ffmpeg.VideoFileMetaDataService
import io.skjaere.debridav.fs.DebridFileType
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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration
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
    private val videoFileMetaDataService: VideoFileMetaDataService,
    private val httpClient: HttpClient,
    private val debridUsenetClients: MutableList<out DebridUsenetClient>,
) {
    private val logger = LoggerFactory.getLogger(UsenetDownloadQueueProcessor::class.java)

    private val inProgressChannel = Channel<UsenetDownloadProgressContext>()
    private val linkValidationChannel = Channel<UsenetDownloadProgressContext>()
    private val videoValidationChannel = Channel<UsenetDownloadProgressContext>()
    private val completedChannel = Channel<UsenetDownloadProgressContext>()
    private final val queueProcessorScope = CoroutineScope(Dispatchers.IO)

    private val knownVideoContainers = listOf(".mkv", ".mp4", ".mpeg", ".mpg", ".avi")
    private val inProgressUsenetDownloadStatuses = listOf(
        UsenetDownloadStatus.DOWNLOADING,
        UsenetDownloadStatus.QUEUED,
        UsenetDownloadStatus.CREATED,
        UsenetDownloadStatus.VERIFYING,
        UsenetDownloadStatus.EXTRACTING,
        UsenetDownloadStatus.REPAIRING
    )


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
            launch { startVideValidationQueueProcessor() }
        }
    }

    @Scheduled(fixedDelayString = "\${debridav.usenet.poll-interval-ms}")
    fun updateDownloads() = runBlocking {
        usenetRepository
            .findAllByStatusIn(inProgressUsenetDownloadStatuses)
            .associateWith {
                async { debridUsenetClients.first().getDownloadInfo(it.debridId!!) }
            }
            .mapValues { it.value.await() }
            .mapValues { getUsenetListItemFromDownloadInfo(it) }
            .filter { it.value != null }
            .forEach { (usenetDownload, debridDownload) ->
                inProgressChannel.send(
                    UsenetDownloadProgressContext(usenetDownload, debridDownload!!)
                )
            }
    }

    private fun getUsenetListItemFromDownloadInfo(it: Map.Entry<UsenetDownload, DownloadInfo>) =
        when (it.value) {
            is DownloadNotFound -> {
                handleDownloadsMissingAtProvider(it.key)
                null
            }

            is SuccessfulDownloadInfo -> (it.value as SuccessfulDownloadInfo).data
            else -> null
        }

    private fun handleDownloadsMissingAtProvider(
        usenetDownload: UsenetDownload
    ) {
        usenetDownload.status = UsenetDownloadStatus.FAILED
        usenetRepository.save(usenetDownload)
    }

    private suspend fun startInProgressUsenetQueueProcessor() {
        repeat(QUEUE_PROCESSORS) {
            inProgressUsenetQueueProcessor()
        }
    }

    private suspend fun inProgressUsenetQueueProcessor() {
        inProgressChannel.consumeEach { validationQueueItem ->
            logger.info("channel: inProgress got message: {}", validationQueueItem)
            if (validationQueueItem.usenetDownload.status == UsenetDownloadStatus.POST_PROCESSING) {
                return@consumeEach
            }
            val updatedDownload = updateInProgressUsenetDownload(validationQueueItem)
            if (validationQueueItem.debridDownload.downloadPresent) {
                processFinishedDownload(updatedDownload, validationQueueItem)
            } else {
                processInProgressDownload(updatedDownload, validationQueueItem)
            }
        }
    }

    private suspend fun processFinishedDownload(
        updatedDownload: UsenetDownload,
        validationQueueItem: UsenetDownloadProgressContext
    ) {
        updatedDownload.status = UsenetDownloadStatus.POST_PROCESSING
        usenetRepository.save(updatedDownload)
        completedChannel.send(validationQueueItem.copy(usenetDownload = updatedDownload))
    }

    private suspend fun UsenetDownloadQueueProcessor.processInProgressDownload(
        updatedDownload: UsenetDownload,
        validationQueueItem: UsenetDownloadProgressContext
    ) {
        if (
            updatedDownload.created.toInstant()
                .plus(Duration.ofHours(4))
                .isBefore(Instant.now()) && updatedDownload.percentCompleted == 0.0
        ) {
            //delete stale download
            markUsenetDownloadAsFailed(validationQueueItem)

        } else usenetRepository.save(updatedDownload)
    }

    private suspend fun startValidationQueueProcessor() = coroutineScope {
        repeat(QUEUE_PROCESSORS) {
            launch {
                linkValidationQueueProcessor()
            }
        }
    }

    private suspend fun linkValidationQueueProcessor() {
        linkValidationChannel.consumeEach { validationQueueItem ->
            logger.info("channel: linkValidation got message: {}", validationQueueItem)

            // Torbox sometimes says downloads are available before they are
            val downloadLinksArePresent = try {
                downloadLinksArePresent(validationQueueItem)
            } catch (e: LinkCheckException) {
                false
            }
            if (!downloadLinksArePresent) {
                if (validationQueueItem.failures <= 10) {
                    delay(10_000L)
                    linkValidationChannel.send(validationQueueItem.copy(failures = validationQueueItem.failures + 1))
                } else markUsenetDownloadAsFailed(validationQueueItem)
            } else videoValidationChannel.send(validationQueueItem.copy(failures = 0))
        }
    }

    private suspend fun startVideValidationQueueProcessor() = coroutineScope {
        repeat(QUEUE_PROCESSORS) {
            launch { videoValidationQueueProcessor() }
        }
    }

    private suspend fun videoValidationQueueProcessor() {

        videoValidationChannel.consumeEach { usenetDownloadProgressContext ->
            logger.info("channel: videoValidation got message: {}", usenetDownloadProgressContext)
            updateCompletedUsenetDownload(
                usenetDownloadProgressContext.usenetDownload
            )
            /*if (usenetDownloadProgressContext.completedDownloads.all { fileIsValid(it.path) } && usenetDownloadProgressContext.failures <= 10) {
                updateCompletedUsenetDownload(
                    usenetDownloadProgressContext.usenetDownload
                )
            } else if (usenetDownloadProgressContext.failures >= 10) {
                videoValidationChannel.send(usenetDownloadProgressContext.copy(failures = usenetDownloadProgressContext.failures + 1))
            } else {
                moveInvalidFiles(usenetDownloadProgressContext)
                markUsenetDownloadAsFailed(usenetDownloadProgressContext)
            }
        }*/
        }
    }

    private suspend fun markUsenetDownloadAsFailed(usenetDownloadProgressContext: UsenetDownloadProgressContext) =
        withContext(Dispatchers.IO) {
            logger.info("Download failed: ${usenetDownloadProgressContext.usenetDownload.name}")
            usenetDownloadProgressContext.usenetDownload.status = UsenetDownloadStatus.FAILED
            usenetRepository.save(usenetDownloadProgressContext.usenetDownload)
            debridUsenetClient.deleteDownload(usenetDownloadProgressContext.usenetDownload.id.toString())
        }

    private suspend fun downloadLinksArePresent(usenetDownloadProgressContext: UsenetDownloadProgressContext): Boolean {
        return usenetDownloadProgressContext.completedDownloads.all { completedDownload ->
            val cachedFile = completedDownload.contents.debridLinks.map { it as CachedFile }.first()
            flow {
                httpClient.head(cachedFile.link) {
                    timeout {
                        requestTimeoutMillis = 20_000
                        connectTimeoutMillis = 20_000
                    }
                }.status.isSuccess().let {
                    if (it) emit(Present) else emit(NotPresent)
                }
            }.retry(5)
                .catch { throw LinkCheckException() }
                .first() == Present
        }
    }

    sealed interface LinkIsPresentResult
    data object Present : LinkIsPresentResult
    data object NotPresent : LinkIsPresentResult
    class LinkCheckException : RuntimeException()

    private suspend fun startCompletedUsenetDownloadQueueProcessor() {
        repeat(QUEUE_PROCESSORS) {
            completedUsenetDownloadQueueProcessor()
        }
    }

    private suspend fun completedUsenetDownloadQueueProcessor() {
        completedChannel.consumeEach { validationQueueItem ->
            logger.info("channel: completed got message: {}", validationQueueItem)
            val debridFiles = validationQueueItem.debridDownload.files.map {
                debridUsenetClient.getCachedFilesFromUsenetInfoListItem(
                    it, validationQueueItem.debridDownload.id
                )
            }.filterIsInstance<CachedFile>().map { file ->
                createDebridFileFromCompletedDownload(
                    file, validationQueueItem.debridDownload, validationQueueItem.usenetDownload
                )
            }
            linkValidationChannel.send(validationQueueItem.copy(completedDownloads = debridFiles))
        }
    }

    /*private suspend fun moveInvalidFiles(validationQueueItem: UsenetDownloadProgressContext) {
        withContext(Dispatchers.IO) {
            fileService.moveResource(
                "/downloads/${validationQueueItem.usenetDownload.name}",
                "/invalidDownloads",
                validationQueueItem.usenetDownload.name!!
            )
            //fileService.deleteFilesWithHash(validationQueueItem.debridDownload.hash)
            *//* validationQueueItem.usenetDownload.status = UsenetDownloadStatus.FAILED
             validationQueueItem.usenetDownload.completed = true
             usenetRepository.save(validationQueueItem.usenetDownload)*//*
        }
    }*/

    private fun updateInProgressUsenetDownload(
        usenetDownloadProgressContext: UsenetDownloadProgressContext
    ): UsenetDownload {

        usenetDownloadProgressContext.usenetDownload.size = usenetDownloadProgressContext.debridDownload.size
        usenetDownloadProgressContext.usenetDownload.percentCompleted =
            usenetDownloadProgressContext.debridDownload.progress
        //usenetDownloadProgressContext.usenetDownload.storagePath = usenetDownloadProgressContext.usenetDownload.name

        val updatedStatus =
            UsenetDownloadStatus.valueFrom(usenetDownloadProgressContext.debridDownload.downloadState.uppercase(Locale.getDefault()))
                .let { updatedStatus ->
                    // Do not tell the arrs the download is complete until we are done processing it
                    if (listOf(
                            UsenetDownloadStatus.COMPLETED, UsenetDownloadStatus.CACHED
                        ).contains(updatedStatus)
                    ) UsenetDownloadStatus.VERIFYING
                    else updatedStatus
                }

        usenetDownloadProgressContext.usenetDownload.status = updatedStatus


        return usenetDownloadProgressContext.usenetDownload
    }

    private fun updateCompletedUsenetDownload(
        usenetDownload: UsenetDownload
    ) {
        usenetDownload.completed = true
        usenetDownload.percentCompleted = 1.0
        usenetDownload.status = UsenetDownloadStatus.COMPLETED
        usenetDownload.storagePath =
            "${debridavConfiguration.mountPath}${debridavConfiguration.downloadPath}/${usenetDownload.name}"

        logger.info("Usenet download complete: ${usenetDownload.name}")
        usenetRepository.save(usenetDownload)
    }

    fun createDebridFileFromCompletedDownload(
        file: CachedFile, completedDownload: GetUsenetListItem, usenetDownload: UsenetDownload
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
            "${debridavConfiguration.downloadPath}/$path", debridFileContents, DebridFileType.USENET_DOWNLOAD
        )
    }

    /*    @Suppress("SwallowedException")
        private suspend fun fileIsValid(path: String): Boolean {
            return if (".${path.substringAfterLast(".")}" in knownVideoContainers) {
                videoIsValid(path)
            } else true
        }*/

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
