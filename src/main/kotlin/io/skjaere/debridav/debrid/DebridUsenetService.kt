package io.skjaere.debridav.debrid

import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.client.DebridUsenetClient
import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetListItem
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.AddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.ServiceErrorAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.SuccessfulAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadInfo
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadNotFound
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.SuccessfulDownloadInfo
import io.skjaere.debridav.fs.DebridFsFile
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.qbittorrent.Category
import io.skjaere.debridav.repository.CategoryRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.sabnzbd.UsenetDownload
import io.skjaere.debridav.sabnzbd.UsenetDownloadStatus
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.*

private const val DEFAULT_429_WAIT_MS = 2_000L
private const val NUMBER_OF_RETRIES = 2_000L

@Service
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
class DebridUsenetService(
    private val debridUsenetClients: MutableList<out DebridUsenetClient>,
    private val usenetRepository: UsenetRepository,
    private val categoryRepository: CategoryRepository,
    private val debridavConfiguration: DebridavConfiguration,
    private val usenetDownloadQueueProcessor: UsenetDownloadQueueProcessor
) {
    private val mutex = Mutex()
    private val logger = LoggerFactory.getLogger(DebridUsenetService::class.java)

    private val inProgressUsenetDownloadStatuses = listOf(
        UsenetDownloadStatus.DOWNLOADING,
        UsenetDownloadStatus.QUEUED,
        UsenetDownloadStatus.CREATED,
        UsenetDownloadStatus.VERIFYING,
        UsenetDownloadStatus.EXTRACTING,
        UsenetDownloadStatus.REPAIRING,
        UsenetDownloadStatus.CACHED
    )

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
                        logger.warn("error adding nzb: ${e.javaClass}. Retrying")
                        delay(DEFAULT_429_WAIT_MS)
                    }
                }
            }
                .catch { e -> emit(ServiceErrorAddNzbResponse(e.message ?: "")) }
                .first()
            logger.info("add nzb result: ${result.javaClass.simpleName}")
            if (result is SuccessfulAddNzbResponse) {
                val usenetDownload = saveUsenetDownload(result, nzbFile, category)
                SuccessfulAddNzbResponse(
                    usenetDownload.id!!,
                    usenetDownload.name!!,
                    usenetDownload.hash!!
                )
            } else result
        }

    @Scheduled(fixedDelayString = "\${debridav.usenet.poll-interval-ms}")
    fun updateDownloads() = runBlocking {
        mutex.withLock {
            usenetRepository
                .findAllByStatusIn(inProgressUsenetDownloadStatuses)
                .associateWith {
                    async { debridUsenetClients.first().getDownloadInfo(it.debridId!!) }
                }
                .mapValues { it.value.await() }
                .mapValues { getUsenetListItemFromDownloadInfo(it) }
                .filter { it.value != null }
                .forEach { (usenetDownload, debridDownload) ->
                    usenetDownloadQueueProcessor.inProgressChannel.send(
                        UsenetDownloadProgressContext(usenetDownload, debridDownload!!, emptyList())
                    )
                }
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

    private fun saveUsenetDownload(
        response: SuccessfulAddNzbResponse,
        nzbFile: MultipartFile,
        category: String
    ): UsenetDownload {
        val usenetDownload = fromCreateUsenetDownloadResponse(
            response,
            category
        )
        usenetRepository.save(usenetDownload)

        val savedNzbFile = File("${debridavConfiguration.filePath}/nzbs/${usenetDownload.id}/bin.nzb")
        savedNzbFile.toPath().let { if (!it.exists()) it.createParentDirectories() }
        savedNzbFile.writeBytes(nzbFile.bytes)

        return usenetDownload
    }

    private suspend fun DebridUsenetClient.addNzb(nzbFile: MultipartFile) =
        this
            .addNzb(
                nzbFile.inputStream,
                nzbFile.originalFilename!!
            )

    private fun handleDownloadsMissingAtProvider(
        usenetDownload: UsenetDownload
    ) {
        usenetDownload.status = UsenetDownloadStatus.FAILED
        usenetRepository.save(usenetDownload)
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
        usenetDownload.size = 0
        usenetDownload.status = UsenetDownloadStatus.CREATED

        return usenetDownload
    }

    data class UsenetDownloadProgressContext(
        val usenetDownload: UsenetDownload,
        val debridDownload: GetUsenetListItem,
        val completedDownloads: List<DebridFsFile>
    )
}
