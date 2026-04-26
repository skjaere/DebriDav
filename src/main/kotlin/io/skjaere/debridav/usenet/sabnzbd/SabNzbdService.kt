package io.skjaere.debridav.usenet.sabnzbd

import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.NzbImportRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.NntpConfigurationProperties
import io.skjaere.debridav.usenet.NzbImportService
import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.UsenetDownloadStatus
import io.skjaere.debridav.usenet.queue.NzbImportRecord
import io.skjaere.debridav.usenet.queue.NzbImportStatus
import io.skjaere.debridav.usenet.sabnzbd.model.HistorySlot
import io.skjaere.debridav.usenet.sabnzbd.model.ListResponseDownloadSlot
import io.skjaere.debridav.usenet.sabnzbd.model.Queue
import io.skjaere.debridav.usenet.sabnzbd.model.SabNzbdHistoryDeleteResponse
import io.skjaere.debridav.usenet.sabnzbd.model.SabNzbdQueueDeleteResponse
import io.skjaere.debridav.usenet.sabnzbd.model.SabNzbdQueueResponse
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdFullHistoryResponse
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdFullListResponse
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdHistory
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdHistoryResponse
import jakarta.transaction.Transactional
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.LoggerFactory
import org.springframework.core.convert.ConversionService
import org.springframework.core.io.ResourceLoader
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

private const val MAX_HISTORY_SLOTS = 500

@Service
@Suppress("LongParameterList")
class SabNzbdService(
    private val cachedContentService: DebridCachedContentService,
    private val fileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val nntpConfigurationProperties: NntpConfigurationProperties,
    private val usenetRepository: UsenetRepository,
    private val usenetConversionService: ConversionService,
    private val categoryService: CategoryService,
    private val resourceLoader: ResourceLoader,
    private val nzbImportService: NzbImportService,
    private val nzbImportRepository: NzbImportRepository
) {
    private val logger = LoggerFactory.getLogger(SabNzbdService::class.java)

    private fun isEasynewsOnlySetup(): Boolean =
        DebridProvider.EASYNEWS in debridavConfigurationProperties.debridClients
                && nntpConfigurationProperties.pools.none { it.host.isNotBlank() }

    @Transactional
    suspend fun addNzbFile(request: SabnzbdApiRequest): UsenetDownload {
        val multipartFile = request.name as MultipartFile
        val releaseName = multipartFile.originalFilename!!.substringBeforeLast(".")
        val nzbBytes = multipartFile.bytes
        val hash = nzbBytes.inputStream().md5()

        if (isEasynewsOnlySetup()) {
            val debridFiles = cachedContentService.addContent(UsenetRelease(releaseName))
            return if (debridFiles.isNotEmpty()) {
                val savedDebridFiles = createDebridFilesFromDebridResponse(debridFiles, hash, releaseName)
                createCachedUsenetDownload(releaseName, hash, request.cat!!, savedDebridFiles)
            } else {
                logger.debug("$releaseName is not cached in any available debrid services")
                createFailedUsenetDownload(releaseName, hash, request.cat!!)
            }
        }

        val usenetDownload = createQueuedUsenetDownload(releaseName, hash, request.cat!!)
        val importRecord = NzbImportRecord().apply {
            usenetDownloadId = usenetDownload.id
            name = releaseName
            category = request.cat
            status = NzbImportStatus.QUEUED
        }
        val savedRecord = nzbImportRepository.save(importRecord)
        nzbImportService.scheduleImport(nzbBytes, usenetDownload, savedRecord.id!!)
        return usenetDownload
    }

    fun InputStream.md5(): String = this.use { inputStream ->
        DigestUtils.md5Hex(inputStream)
    }


    fun history(request: SabnzbdApiRequest): SabnzbdHistoryResponse {
        if (request.name is String && request.name == "delete") {
            usenetRepository.deleteUsenetDownloadById(request.value!!.toLong())
            return SabNzbdHistoryDeleteResponse(true, listOf(request.value))
        } else {
            // Sonarr/Radarr poll history but only need recent entries to match against
            // their pending grabs. Cap at MAX_HISTORY_SLOTS (newest first) so the
            // table doesn't OOM us when it grows over time.
            val pageable = PageRequest.of(0, MAX_HISTORY_SLOTS)
            val slots = request.cat?.let {
                usenetRepository.findRecentByCategoryName(it, pageable)
            } ?: usenetRepository.findRecent(pageable)

            val filteredSlots = slots
                .filter { it.status?.isCompleted() == true }
                /** @see io.skjaere.debridav.usenet.sabnzbd.converter.UsenetDownloadToHistoryResponseSlotConverter */
                .map { usenetConversionService.convert(it, HistorySlot::class.java)!! }

            return SabnzbdFullHistoryResponse(
                SabnzbdHistory(
                    filteredSlots
                )
            )
        }
    }

    @Transactional
    suspend fun queue(request: SabnzbdApiRequest): SabNzbdQueueResponse {
        if (request.name is String && request.name == "delete") {
            usenetRepository.findById(request.value!!.toLong()).get().let { usenetDownload ->
                usenetRepository.markUsenetDownloadAsDeleted(usenetDownload)
            }
            return SabNzbdQueueDeleteResponse(true, listOf(request.value))
        }
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
        return SabnzbdFullListResponse(queue)
    }

    fun config(): String {
        val categories = categoryService.getAllCategories().mapIndexed { index, category ->
            val map = mapOf<String, JsonPrimitive>(
                "order" to JsonPrimitive(index),
                "name" to JsonPrimitive(category.name!!),
                "pp" to JsonPrimitive(""),
                "script" to JsonPrimitive("None"),
                "dir" to
                        JsonPrimitive(
                            debridavConfigurationProperties.mountPath +
                                    debridavConfigurationProperties.downloadPath
                        ),
                "newzbin" to JsonPrimitive(""),
                "priority" to JsonPrimitive(0)
            )
            JsonObject(map)
        }
        val parsed = Json.decodeFromString<JsonObject>(
            resourceLoader.getResource("classpath:sabnzbd_get_config_response.json")
                .getContentAsString(Charsets.UTF_8)
                .replace("%MOUNT_PATH%", debridavConfigurationProperties.mountPath)
                .replace("%DOWNLOAD_PATH%", debridavConfigurationProperties.downloadPath)
        ).toMutableMap().apply {
            this["config"] = JsonObject(
                this["config"]!!.jsonObject.toMutableMap().apply {
                    this["categories"] = JsonArray(categories)
                }
            )

        }
        return Json.encodeToString(parsed)
    }

    private suspend fun createDebridFilesFromDebridResponse(
        debridFiles: List<DebridFileContents>,
        hash: String,
        releaseName: String
    ): List<RemotelyCachedEntity> {
        val basePath = "${debridavConfigurationProperties.downloadPath}/$releaseName"
        return fileService.createDebridFiles(
            debridFiles.map { "$basePath/${it.originalPath}" to it },
            hash,
        )
    }


    private suspend fun createQueuedUsenetDownload(
        releaseName: String,
        hash: String,
        categoryName: String
    ): UsenetDownload {
        val usenetDownload = UsenetDownload()
        usenetDownload.status = UsenetDownloadStatus.QUEUED
        usenetDownload.name = releaseName
        usenetDownload.hash = hash
        usenetDownload.category = categoryService.getOrCreateCategory(categoryName)
        usenetDownload.storagePath =
            "${debridavConfigurationProperties.mountPath}${debridavConfigurationProperties.downloadPath}/$releaseName"
        usenetDownload.percentCompleted = 0.0
        usenetDownload.size = 0
        return usenetRepository.save(usenetDownload)
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
        usenetDownload.category = categoryService.getOrCreateCategory(categoryName)
        usenetDownload.storagePath =
            "${debridavConfigurationProperties.mountPath}${debridavConfigurationProperties.downloadPath}/$releaseName"
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
    ): UsenetDownload {
        val resolvedCategory = categoryService.getOrCreateCategory(category)
        val usenetDownload = UsenetDownload()
        usenetDownload.status = UsenetDownloadStatus.COMPLETED
        usenetDownload.name = releaseName
        usenetDownload.hash = hash
        usenetDownload.category = resolvedCategory
        usenetDownload.storagePath =
            "${debridavConfigurationProperties.mountPath}${debridavConfigurationProperties.downloadPath}/$releaseName"
        usenetDownload.percentCompleted = 1.0
        usenetDownload.size = createdFiles.first().size
        usenetDownload.debridFiles.addAll(createdFiles)

        return usenetRepository.save(usenetDownload)
    }
}
