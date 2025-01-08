package io.skjaere.debridav.sabnzbd

import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.DebridUsenetDownloadService
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.CachedAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.FailedAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.ServiceErrorAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.SuccessfulAddNzbResponse
import io.skjaere.debridav.fs.DebridFileType
import io.skjaere.debridav.fs.DebridFsFile
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.qbittorrent.Category
import io.skjaere.debridav.repository.CategoryRepository
import io.skjaere.debridav.repository.UsenetRepository
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.convert.ConversionService
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

private const val VERSION_PAYLOAD = """{"version": "4.4.0"}"""

@RestController
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('easynews')}")
class SabnzbdApiController(
    private val resourceLoader: ResourceLoader,
    private val usenetRepository: UsenetRepository,
    private val usenetConversionService: ConversionService,
    private val fileService: FileService,
    private val debridavConfiguration: DebridavConfiguration,
    private val cachedContentService: DebridCachedContentService,
    private val categoryRepository: CategoryRepository,
    @Autowired(required = false) val debridUsenetDownloadService: DebridUsenetDownloadService?
) {
    private val logger = LoggerFactory.getLogger(SabnzbdApiController::class.java)

    @RequestMapping(
        path = ["/api"],
        method = [RequestMethod.GET, RequestMethod.POST],
    )
    fun addNzb(
        request: SabnzbdApiRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<String> = runBlocking {
        logger.debug("Adding Nzb API ${httpRequest.requestURI}")
        val json = when (request.mode) {
            "version" -> version()
            "get_config" -> config()
            "fullstatus" -> fullStatus()
            "addfile" -> addNzbFile(request)
            "queue" -> queue(request)
            "history" -> history()

            else -> {
                logger.error("unknown mode ${request.mode}")
                "else"
            }
        }
        ResponseEntity.ok(json)
    }

    private fun history(): String {
        val slots = usenetRepository
            .findAll()
            .filter { it.status?.isCompleted() ?: false }
            /** @see io.skjaere.debridav.sabnzbd.converter.UsenetDownloadToHistoryResponseSlotConverter */
            .map { usenetConversionService.convert(it, HistorySlot::class.java)!! }
        return Json.encodeToString(
            SabnzbdHistoryResponse(
                SabnzbdHistory(
                    slots
                )
            )
        )
    }

    private suspend fun addNzbFile(request: SabnzbdApiRequest): String {
        val releaseName = (request.name as MultipartFile).originalFilename.substringBeforeLast(".")

        val debridFiles = cachedContentService.addContent(UsenetRelease(releaseName))

        if (debridFiles.isNotEmpty()) {
            val createdFiles = debridFiles.map { file ->
                fileService.createDebridFile(
                    "${debridavConfiguration.downloadPath}/${releaseName}/${file.originalPath}",
                    file,
                    DebridFileType.CACHED_USENET
                )
            }
            val savedUsenetDownload = createCachedUsenetDownload(releaseName, request, createdFiles)
            return Json.encodeToString(
                AddNzbResponse(true, listOf(savedUsenetDownload.id.toString()))
            )

        } else if (debridUsenetDownloadService != null) {
            logger.info("No cached files for $releaseName found. Starting download")
            val response = when (val result = debridUsenetDownloadService!!.addNzb(request.name, request.cat!!)) {
                is CachedAddNzbResponse -> TODO()
                is FailedAddNzbResponse -> throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY)
                is ServiceErrorAddNzbResponse -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
                is SuccessfulAddNzbResponse -> AddNzbResponse(
                    true,
                    listOf(result.downloadId.toString())
                )
            }
            return Json.encodeToString(
                response
            )
        } else throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    private suspend fun createCachedUsenetDownload(
        releaseName: String,
        request: SabnzbdApiRequest,
        createdFiles: List<DebridFsFile>
    ): UsenetDownload = withContext(Dispatchers.IO) {
        val category = categoryRepository.findByName(request.cat!!) ?: kotlin.run {
            val newCategory = Category()
            newCategory.name = request.cat
            categoryRepository.save(newCategory)
        }
        val usenetDownload = UsenetDownload()
        usenetDownload.status = UsenetDownloadStatus.COMPLETED
        usenetDownload.name = releaseName
        usenetDownload.category = category
        usenetDownload.storagePath =
            "${debridavConfiguration.mountPath}${debridavConfiguration.downloadPath}/$releaseName"
        usenetDownload.percentCompleted = 1.0
        usenetDownload.size = createdFiles.first().size
        usenetDownload.hash = ""
        val savedUsenetDownload = usenetRepository.save(usenetDownload)
        savedUsenetDownload
    }

    private suspend fun queue(request: SabnzbdApiRequest): String = withContext(Dispatchers.IO) {
        if (request.name is String && request.name == "delete") {
            usenetRepository.findById(request.value!!.toLong()).get().let { usenetDownload ->
                usenetRepository.delete(usenetDownload)
                if (request.delFiles == 1) {
                    fileService.deleteFilesWithHash(usenetDownload.hash!!)
                }
            }
            """
                {
                    "status": true,
                    "nzo_ids": [
                        "${request.value}"
                    ]
                }
            """.trimIndent()
        } else {
            val queueSlots = getDownloads()
            logger.info("Queue Slots: $queueSlots")
            val queue = Queue(
                status = "Downloading",
                speedLimit = "0",
                speedLimitAbs = "0",
                paused = false,
                noofSlots = queueSlots.size,
                noofSlotsTotal = queueSlots.size,
                limit = 0,
                start = 0,
                timeLeft = "0:10:0",
                speed = "1 M",
                kbPerSec = "100.0",
                size = "0",//${(queueSlots.sumOf { it.mb.toInt() } / 1000)} GB",
                sizeLeft = "0",//"${(queueSlots.sumOf { it.mbLeft.toInt() } / 1000)} GB",
                mb = "0",//queueSlots.sumOf { it.mb.toInt() }.toString(),
                mbLeft = "0",//queueSlots.sumOf { it.mbLeft.toLong() }.toString(),
                slots = queueSlots
            )
            Json.encodeToString(SabnzbdFullListResponse(queue))
        }
    }

    private suspend fun getDownloads(): List<ListResponseDownloadSlot> = withContext(Dispatchers.IO) {
        usenetRepository.findAll()
    }.map { usenetConversionService.convert(it, ListResponseDownloadSlot::class.java)!! }


    private fun fullStatus(): String =
        resourceLoader.getResource("classpath:sabnzbd_fullstatus.json").getContentAsString(Charsets.UTF_8)

    private fun config(): String =
        resourceLoader.getResource("classpath:sabnzbd_get_config_response.json").getContentAsString(Charsets.UTF_8)

    private fun version() = VERSION_PAYLOAD
}
