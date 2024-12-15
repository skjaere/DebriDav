package io.skjaere.debridav.debrid

import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.client.DebridUsenetClient
import io.skjaere.debridav.debrid.client.torbox.TorBoxUsenetClient
import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetListItem
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.AddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.ServiceErrorAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.SuccessfulAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadInfo
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadNotFound
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.SuccessfulDownloadInfo
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.fs.DebridUsenetFileContents
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.qbittorrent.Category
import io.skjaere.debridav.repository.CategoryRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.sabnzbd.UsenetDownload
import io.skjaere.debridav.sabnzbd.UsenetDownloadStatus
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.*

private const val DEFAULT_429_WAIT_MS = 2_000L
private const val NUMBER_OF_RETRIES = 2_000L

@Service
@Transactional
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
class DebridUsenetService(
    private val debridUsenetClients: MutableList<out DebridUsenetClient>,
    private val torBoxUsenetClient: TorBoxUsenetClient,
    private val usenetRepository: UsenetRepository,
    private val categoryRepository: CategoryRepository,
    private val fileService: FileService,
    private val debridavConfiguration: DebridavConfiguration
) {
    private val mutex = Mutex()
    private val logger = LoggerFactory.getLogger(DebridUsenetService::class.java)

    init {
        File("${debridavConfiguration.filePath}/nzbs/").toPath().let {
            if (!it.exists()) {
                it.createParentDirectories()
            }
        }
    }

    suspend fun addNzb(nzbFile: MultipartFile, category: String): AddNzbResponse =
        withContext(Dispatchers.IO) {
            val result = flow {
                emit(debridUsenetClients.first().addNzb(nzbFile))
            }.retry(NUMBER_OF_RETRIES) { e ->
                (e is IOException).also {
                    if (it) {
                        logger.info("error adding nzb: ${e.javaClass}. Retrying")
                        delay(DEFAULT_429_WAIT_MS)
                    }
                }
            }
                .catch { e -> emit(ServiceErrorAddNzbResponse(e.message ?: "")) }
                .first()
            if (result is SuccessfulAddNzbResponse) {
                saveUsenetDownload(result, nzbFile, category)
            }
            logger.info("add nzb result: ${result.javaClass.simpleName}")
            logger.info(result.toString())
            result
        }


    private fun saveUsenetDownload(
        response: SuccessfulAddNzbResponse,
        nzbFile: MultipartFile,
        category: String
    ): AddNzbResponse {
        val usenetDownload = fromCreateUsenetDownloadResponse(
            response,
            category
        )

        usenetRepository.save(usenetDownload)

        val savedNzbFile = File("${debridavConfiguration.filePath}/nzbs/${usenetDownload.id}/bin.nzb")
        savedNzbFile.toPath().let { if (!it.exists()) it.createParentDirectories() }
        savedNzbFile.writeBytes(nzbFile.bytes)

        return response
    }

    private suspend fun DebridUsenetClient.addNzb(nzbFile: MultipartFile) =
        this
            .addNzb(
                nzbFile.inputStream,
                nzbFile.originalFilename!!
            )

    @Scheduled(fixedDelay = 5_000)
    @Transactional
    fun updateDownloads() = runBlocking {
        mutex.withLock {
            val allDownloads = usenetRepository.findAll()

            val inProgressDownloadIds = allDownloads
                .asSequence()
                .filter { it.completed == false }
                .filter { it.status != UsenetDownloadStatus.FAILED }
                .map { it.debridId!!.toLong() }
                .toMutableList()

            val debridDownloads = torBoxUsenetClient.getDownloads(inProgressDownloadIds)

            val (completedDebridDownloads, inProgressDebridDownloads) = debridDownloads
                .filter { it.value is SuccessfulDownloadInfo }
                .map { it.value as SuccessfulDownloadInfo }
                .map { it.data }
                .partition { it.downloadPresent }

            handleDownloadsMissingAtProvider(debridDownloads, allDownloads)
            updateInProgressDownloads(inProgressDebridDownloads, allDownloads)
            updateCompletedDownloads(completedDebridDownloads, allDownloads)
        }
    }

    private fun handleDownloadsMissingAtProvider(
        debridDownloads: Map<Long, DownloadInfo>,
        allDownloads: MutableIterable<UsenetDownload>
    ) {
        debridDownloads
            .filter { it.value is DownloadNotFound }
            .forEach { entry ->
                logger.warn("Download with id ${entry.key} not found at provider. Marking it as failed.")
                allDownloads.first { entry.key == it.debridId }.let {
                    it.status = UsenetDownloadStatus.FAILED
                    usenetRepository.save(it)
                }
            }
    }

    private suspend fun updateCompletedDownloads(
        completedDebridDownloads: List<GetUsenetListItem>,
        allDownloads: MutableIterable<UsenetDownload>
    ) {
        completedDebridDownloads.forEach { completedDownload ->
            logger.debug("processing slot")
            logger.debug("{}", completedDownload)
            val debridFiles = completedDownload
                .files
                .map { torBoxUsenetClient.getCachedFilesFromUsenetInfoListItem(it, completedDownload.id) }

            if (debridFiles.all { it is CachedFile }) {
                debridFiles
                    .filterIsInstance<CachedFile>()
                    .forEach { file ->
                        createDebridFileFromCompletedDownload(file, completedDownload)
                    }
                val usenetDownload = allDownloads.first { it.debridId == completedDownload.id.toLong() }
                updateCompletedUsenetDownload(usenetDownload, completedDownload)
            } else {
                logger.error("Failed to generate files from debrid services")
            }
        }
    }

    private fun updateInProgressDownloads(
        inProgressDebridDownloads: List<GetUsenetListItem>,
        allDownloads: MutableIterable<UsenetDownload>
    ) {
        val updatedInProgressDebridDownloads = inProgressDebridDownloads
            .map { inProgressDebridDownload ->
                val usenetDownload =
                    allDownloads.first { it.debridId == inProgressDebridDownload.id.toLong() }
                updateInProgressUsenetDownload(usenetDownload, inProgressDebridDownload)
            }
        usenetRepository.saveAll(updatedInProgressDebridDownloads)
    }

    private fun updateInProgressUsenetDownload(
        usenetDownload: UsenetDownload,
        inProgressDebridDownload: GetUsenetListItem
    ): UsenetDownload {
        usenetDownload.size = inProgressDebridDownload.size
        usenetDownload.percentCompleted = inProgressDebridDownload.progress
        usenetDownload.storagePath = inProgressDebridDownload.name
        usenetDownload.status =
            UsenetDownloadStatus
                .valueFrom(inProgressDebridDownload.downloadState.uppercase(Locale.getDefault()))
        if (usenetDownload.status == UsenetDownloadStatus.FAILED) {
            logger.info("Download ${usenetDownload.name} failed.")
        }
        return usenetDownload
    }

    private fun updateCompletedUsenetDownload(
        usenetDownload: UsenetDownload,
        completedDownload: GetUsenetListItem
    ) {
        usenetDownload.completed = true
        usenetDownload.percentCompleted = 1.0
        usenetDownload.status = UsenetDownloadStatus.COMPLETED
        usenetDownload.storagePath = completedDownload.name
        usenetDownload.size = completedDownload.size

        logger.info("saving download: ${usenetDownload.debridId}")
        usenetRepository.save(usenetDownload)
    }

    private fun createDebridFileFromCompletedDownload(
        file: CachedFile,
        completedDownload: GetUsenetListItem
    ) {
        val debridFileContents = DebridUsenetFileContents(
            originalPath = file.path,
            size = file.size,
            modified = Instant.now().toEpochMilli(),
            debridLinks = mutableListOf(file),
            usenetDownloadId = completedDownload.id,
            nzbFileLocation = "${debridavConfiguration.filePath}/nzbs/${completedDownload.id}/bin.nzb",
            hash = completedDownload.hash,
            id = null,
            mimeType = file.mimeType
        )
        fileService.createDebridFile(
            "${debridavConfiguration.downloadPath}/${file.path}",
            debridFileContents
        )
    }

    private fun fromCreateUsenetDownloadResponse(
        response: SuccessfulAddNzbResponse,
        categoryName: String
    ): UsenetDownload {
        val category = categoryRepository.findByName(categoryName) ?: run {
            val newCategory = Category()
            newCategory.name = categoryName
            categoryRepository.save(newCategory)
            newCategory
        }
        val usenetDownload = UsenetDownload()
        usenetDownload.name = response.name
        usenetDownload.debridId = response.downloadId
        usenetDownload.created = Date.from(Instant.now())
        usenetDownload.category = category
        usenetDownload.completed = false
        usenetDownload.percentCompleted = 0.0
        usenetDownload.debridProvider = DebridProvider.TORBOX
        usenetDownload.hash = response.hash
        usenetDownload.status = UsenetDownloadStatus.CREATED

        return usenetDownload
    }
}
