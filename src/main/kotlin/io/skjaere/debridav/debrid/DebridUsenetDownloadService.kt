package io.skjaere.debridav.debrid

import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.client.DebridUsenetClient
import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetListItem
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.AddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.CachedAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.FailedAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.ServiceErrorAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.SuccessfulAddNzbResponse
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
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
class DebridUsenetDownloadService(
    private val debridUsenetClients: MutableList<out DebridUsenetClient>,
    private val usenetRepository: UsenetRepository,
    private val categoryRepository: CategoryRepository,
    private val debridavConfiguration: DebridavConfiguration
) {
    private val logger = LoggerFactory.getLogger(DebridUsenetDownloadService::class.java)

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
                .catch { e ->
                    run {
                        logger.error("error adding nzb: ${e.javaClass}. Error: ${e.localizedMessage}")
                        emit(ServiceErrorAddNzbResponse(e.message ?: ""))
                    }
                }
                .first()
            logger.info("add nzb result: ${result.javaClass.simpleName}")
            val usenetDownload = saveUsenetDownload(result, nzbFile, category)
            when (result) {
                is SuccessfulAddNzbResponse -> {
                    SuccessfulAddNzbResponse(
                        usenetDownload.id!!,
                        usenetDownload.name!!,
                        usenetDownload.hash!!
                    )
                }

                is CachedAddNzbResponse -> TODO()
                is FailedAddNzbResponse -> {
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    usenetRepository.save(usenetDownload)
                    SuccessfulAddNzbResponse(
                        usenetDownload.id!!,
                        usenetDownload.name!!,
                        usenetDownload.hash!!
                    )
                }

                is ServiceErrorAddNzbResponse -> {
                    usenetDownload.status = UsenetDownloadStatus.FAILED
                    usenetRepository.save(usenetDownload)
                    SuccessfulAddNzbResponse(
                        usenetDownload.id!!,
                        usenetDownload.name!!,
                        usenetDownload.hash!!
                    )
                }
            }
        }

    private fun saveUsenetDownload(
        response: AddNzbResponse,
        nzbFile: MultipartFile,
        category: String
    ): UsenetDownload {
        val usenetDownload = fromCreateUsenetDownloadResponse(
            nzbFile.originalFilename!!.substringBeforeLast("."),
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


    private fun fromCreateUsenetDownloadResponse(
        name: String,
        response: AddNzbResponse,
        categoryName: String
    ): UsenetDownload {
        val category = categoryRepository.findByName(categoryName) ?: run {
            val newCategory = Category()
            newCategory.name = categoryName
            categoryRepository.save(newCategory)
            newCategory
        }
        val usenetDownload = UsenetDownload()
        usenetDownload.name = name
        usenetDownload.created = Date.from(Instant.now())
        usenetDownload.category = category
        usenetDownload.completed = false
        usenetDownload.percentCompleted = 0.0
        usenetDownload.debridProvider = DebridProvider.TORBOX
        usenetDownload.hash = ""

        when (response) {
            is SuccessfulAddNzbResponse -> {
                usenetDownload.name = name
                usenetDownload.debridId = response.downloadId
                usenetDownload.hash = response.hash
                usenetDownload.size = 0
                usenetDownload.status = UsenetDownloadStatus.CREATED
            }

            is FailedAddNzbResponse -> {
                usenetDownload.status = UsenetDownloadStatus.FAILED
            }

            is ServiceErrorAddNzbResponse -> {
                usenetDownload.status = UsenetDownloadStatus.FAILED
            }

            is CachedAddNzbResponse -> TODO()
        }

        return usenetDownload
    }

    data class UsenetDownloadProgressContext(
        val usenetDownload: UsenetDownload,
        val debridDownload: GetUsenetListItem,
        val completedDownloads: List<DebridFsFile>,
        val failures: Int
    ) {
        constructor(
            usenetDownload: UsenetDownload,
            debridDownload: GetUsenetListItem,

            ) : this(usenetDownload, debridDownload, emptyList(), 0)
    }
}
