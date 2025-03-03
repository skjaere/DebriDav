package io.skjaere.debridav.usenet.sabnzbd

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.category.CategoryRepository
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.UsenetDownloadStatus
import io.skjaere.debridav.usenet.sabnzbd.model.HistorySlot
import io.skjaere.debridav.usenet.sabnzbd.model.ListResponseDownloadSlot
import io.skjaere.debridav.usenet.sabnzbd.model.Queue
import io.skjaere.debridav.usenet.sabnzbd.model.SabNzbdQueueDeleteResponse
import io.skjaere.debridav.usenet.sabnzbd.model.SabNzbdQueueResponse
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdFullListResponse
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdHistory
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdHistoryResponse
import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.LoggerFactory
import org.springframework.core.convert.ConversionService
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

@Service
class SabNzbdService(
    private val cachedContentService: DebridCachedContentService,
    private val fileService: DatabaseFileService,
    private val debridavConfiguration: DebridavConfiguration,
    private val usenetRepository: UsenetRepository,
    private val usenetConversionService: ConversionService,
    private val categoryRepository: CategoryRepository
) {
    private val logger = LoggerFactory.getLogger(SabNzbdService::class.java)

    @Transactional
    suspend fun addNzbFile(request: SabnzbdApiRequest): UsenetDownload {
        val releaseName = (request.name as MultipartFile).originalFilename!!.substringBeforeLast(".")
        val hash = request.name.inputStream.md5()

        val debridFiles = cachedContentService.addContent(UsenetRelease(releaseName))

        return if (debridFiles.isNotEmpty()) {
            val savedDebridFiles = createDebridFilesFromDebridResponse(debridFiles, hash, releaseName)
            createCachedUsenetDownload(releaseName, hash, request.cat!!, savedDebridFiles)
        } else {
            logger.info("$releaseName is not cached in any available debrid services")
            createFailedUsenetDownload(releaseName, hash, request.cat!!)
        }
    }

    fun InputStream.md5(): String = this.use { inputStream ->
        DigestUtils.md5Hex(inputStream)
    }


    fun history(): SabnzbdHistoryResponse {
        val slots = usenetRepository
            .findAll()
            .filter { it.status?.isCompleted() == true }
            /** @see io.skjaere.debridav.usenet.sabnzbd.converter.UsenetDownloadToHistoryResponseSlotConverter */
            .map { usenetConversionService.convert(it, HistorySlot::class.java)!! }
        return SabnzbdHistoryResponse(
            SabnzbdHistory(
                slots
            )
        )
    }

    @Transactional
    suspend fun queue(request: SabnzbdApiRequest): SabNzbdQueueResponse = withContext(Dispatchers.IO) {
        if (request.name is String && request.name == "delete") {
            usenetRepository.findById(request.value!!.toLong()).get().let { usenetDownload ->
                usenetRepository.markUsenetDownloadAsDeleted(usenetDownload)
            }
            SabNzbdQueueDeleteResponse(true, listOf(request.value))
        } else {
            val queueSlots = emptyList<ListResponseDownloadSlot>()
            val queue = Queue(
                status = "Downloading",
                speedLimit = "0",
                speedLimitAbs = "0",
                paused = false,
                noofSlots = 0,
                noofSlotsTotal = 0,
                limit = 0,
                start = 0,
                timeLeft = "0:10:0",
                speed = "1 M",
                kbPerSec = "100.0",
                size = "0",
                sizeLeft = "0",
                mb = "0",
                mbLeft = "0",
                slots = queueSlots
            )
            SabnzbdFullListResponse(queue)
        }
    }

    private suspend fun createDebridFilesFromDebridResponse(
        debridFiles: List<DebridFileContents>,
        hash: String,
        releaseName: String
    ): List<RemotelyCachedEntity> =
        debridFiles.map { file ->
            fileService.createDebridFile(
                "${debridavConfiguration.downloadPath}/${releaseName}/${file.originalPath}",
                hash,
                file
            )
        }


    private suspend fun createFailedUsenetDownload(
        releaseName: String,
        hash: String,
        categoryName: String
    ): UsenetDownload {
        val usenetDownload = UsenetDownload()
        usenetDownload.status = UsenetDownloadStatus.FAILED
        usenetDownload.name = releaseName
        usenetDownload.hash = hash
        usenetDownload.category = getOrCreateCategory(categoryName)
        usenetDownload.storagePath =
            "${debridavConfiguration.mountPath}${debridavConfiguration.downloadPath}/$releaseName"
        usenetDownload.percentCompleted = 0.0
        usenetDownload.size = 0
        val savedUsenetDownload = usenetRepository.save(usenetDownload)
        return savedUsenetDownload
    }

    private suspend fun createCachedUsenetDownload(
        releaseName: String,
        hash: String,
        category: String,
        createdFiles: List<RemotelyCachedEntity>
    ): UsenetDownload = withContext(Dispatchers.IO) {
        val category = getOrCreateCategory(category)
        val usenetDownload = UsenetDownload()
        usenetDownload.status = UsenetDownloadStatus.COMPLETED
        usenetDownload.name = releaseName
        usenetDownload.hash = hash
        usenetDownload.category = category
        usenetDownload.storagePath =
            "${debridavConfiguration.mountPath}${debridavConfiguration.downloadPath}/$releaseName"
        usenetDownload.percentCompleted = 1.0
        usenetDownload.size = createdFiles.first().size
        usenetDownload.debridFiles.addAll(createdFiles)

        usenetRepository.save(usenetDownload)
    }

    private suspend fun getOrCreateCategory(categoryName: String): Category {
        return categoryRepository.findByName(categoryName) ?: kotlin.run {
            val newCategory = Category()
            newCategory.name = categoryName
            categoryRepository.save(newCategory)
        }
    }
}
