package io.skjaere.debridav.usenet.sabnzbd

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import io.skjaere.debridav.arrs.ArrService
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
import kotlinx.coroutines.runBlocking
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
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

private const val MAX_HISTORY_SLOTS = 500
private const val NZB_EXT_LENGTH = 4 // ".nzb"

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
    private val nzbImportRepository: NzbImportRepository,
    private val arrService: ArrService,
    private val httpClient: HttpClient,
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
                val saved = createCachedUsenetDownload(releaseName, hash, request.cat!!, savedDebridFiles)
                // Nudge the arr to import the new release now rather than wait for
                // its polling tick. Deferred to after-commit so the arr's follow-up
                // SABnzbd history poll sees the COMPLETED slot — firing inline here
                // would race the @Transactional's commit and the arr would still
                // see the pre-commit (empty) state.
                scheduleArrRefreshAfterCommit(request.cat!!)
                saved
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

    /**
     * SABnzbd `mode=addurl` — same as [addNzbFile] but the NZB content is fetched
     * from a URL rather than uploaded inline. Sonarr/Radarr use this when the
     * indexer returns an NZB URL instead of inlining the bytes (the common path
     * for most indexers behind an apikey).
     *
     * Release name resolves in this order:
     *   1. `request.nzbname` if set (Sonarr/Radarr always send this)
     *   2. The NZB XML's `<head><meta type="title">…` value (every indexer
     *      worth using populates this with the actual release name)
     *   3. The URL's last path segment with `.nzb` stripped — only useful for
     *      indexers that put a real filename in the path; most modern indexers
     *      put an opaque ID there with the apikey as a query param, so this is
     *      a last resort
     *   4. A timestamped fallback so we always have *something* to record
     *
     * The Easynews-only setup short-circuits the URL fetch when (1) is
     * available — cache lookup is keyed on release name, not NZB bytes.
     */
    @Transactional
    suspend fun addNzbUrl(request: SabnzbdApiRequest): UsenetDownload {
        val url = request.name as? String
            ?: error("addurl: 'name' parameter must be a URL string, got ${request.name?.javaClass?.simpleName}")
        val category = request.cat
            ?: error("addurl: 'cat' parameter is required")
        val nzbname = request.nzbname?.takeIf { it.isNotBlank() }
            ?.removeSuffix(".nzb")?.removeSuffix(".NZB")

        // Fast path: explicit nzbname + Easynews-only setup → no URL fetch needed.
        if (nzbname != null && isEasynewsOnlySetup()) {
            return runEasynewsCachedFlow(nzbname, category)
        }

        val nzbBytes = fetchNzbBytes(url, nzbname ?: "<unknown>")
            ?: return createFailedUsenetDownload(
                releaseName = nzbname ?: deriveReleaseNameFromUrl(url),
                hash = DigestUtils.md5Hex(url),
                categoryName = category,
            )

        val releaseName = nzbname
            ?: extractNzbTitle(nzbBytes)
            ?: deriveReleaseNameFromUrl(url)

        if (isEasynewsOnlySetup()) {
            return runEasynewsCachedFlow(releaseName, category)
        }

        val hash = nzbBytes.inputStream().md5()
        val usenetDownload = createQueuedUsenetDownload(releaseName, hash, category)
        val importRecord = NzbImportRecord().apply {
            usenetDownloadId = usenetDownload.id
            name = releaseName
            this.category = category
            status = NzbImportStatus.QUEUED
        }
        val savedRecord = nzbImportRepository.save(importRecord)
        nzbImportService.scheduleImport(nzbBytes, usenetDownload, savedRecord.id!!)
        return usenetDownload
    }

    private suspend fun runEasynewsCachedFlow(releaseName: String, category: String): UsenetDownload {
        val debridFiles = cachedContentService.addContent(UsenetRelease(releaseName))
        // Easynews path doesn't need NZB bytes — release-name lookup is enough.
        // Use a stable hash derived from release name + category so duplicates
        // surface as identical hashes (matches the addfile path's
        // byte-content-derived hash semantics: same release → same hash).
        val hash = DigestUtils.md5Hex("$releaseName|$category")
        return if (debridFiles.isNotEmpty()) {
            val savedDebridFiles = createDebridFilesFromDebridResponse(debridFiles, hash, releaseName)
            val saved = createCachedUsenetDownload(releaseName, hash, category, savedDebridFiles)
            scheduleArrRefreshAfterCommit(category)
            saved
        } else {
            logger.debug("$releaseName is not cached in any available debrid services")
            createFailedUsenetDownload(releaseName, hash, category)
        }
    }

    private suspend fun fetchNzbBytes(url: String, releaseName: String): ByteArray? {
        @Suppress("TooGenericExceptionCaught")
        return try {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) {
                logger.warn(
                    "addurl: fetch of NZB at '{}' for '{}' returned {}",
                    url, releaseName, response.status,
                )
                return null
            }
            response.bodyAsBytes()
        } catch (e: Exception) {
            logger.warn("addurl: fetch of NZB at '{}' for '{}' failed: {}", url, releaseName, e.message)
            null
        }
    }

    /**
     * Pull the release name out of the NZB XML's `<head>` block:
     * `<head><meta type="title">Some.Release.Name</meta></head>`. This is the
     * value indexers populate as the canonical release name and is what gets
     * stored on the indexer side, so it's a much better source than guessing
     * from the URL path.
     *
     * Returns null if the NZB has no title meta or if parsing fails — caller
     * falls back to the URL-derived name.
     */
    private fun extractNzbTitle(nzbBytes: ByteArray): String? {
        @Suppress("TooGenericExceptionCaught")
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // XXE protection — NZBs come from arbitrary URLs so we treat them as
                // untrusted. The strictest single-flag approach (`disallow-doctype-decl`)
                // would reject all DOCTYPE declarations, but real-world NZBs typically
                // ship one (`<!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" ...>`)
                // and our parser would silently fail and the caller would fall through
                // to the URL-derived name. Instead we allow DOCTYPE but block all
                // external-entity / external-DTD resolution, which is the actual XXE
                // attack vector.
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
            }
            val doc = factory.newDocumentBuilder().parse(nzbBytes.inputStream())
            // `*` for the namespace URI matches `<meta>` regardless of whether the
            // NZB used the default newzbin namespace or none at all — both occur
            // in the wild.
            val metas = doc.getElementsByTagNameNS("*", "meta")
            for (i in 0 until metas.length) {
                val meta = metas.item(i) as? org.w3c.dom.Element ?: continue
                if (meta.getAttribute("type") == "title") {
                    val title = meta.textContent?.trim()?.takeIf { it.isNotBlank() }
                    if (title != null) return title
                }
            }
            null
        } catch (e: Exception) {
            logger.debug("addurl: could not parse <meta type=\"title\"> from NZB: {}", e.message)
            null
        }
    }

    private fun deriveReleaseNameFromUrl(url: String): String {
        val pathOnly = url.substringBefore('?').substringBefore('#').substringBefore('&')
        val lastSegment = pathOnly.substringAfterLast('/')
        return when {
            lastSegment.isBlank() -> "addurl-${System.currentTimeMillis()}"
            lastSegment.endsWith(".nzb", ignoreCase = true) && lastSegment.length > NZB_EXT_LENGTH ->
                lastSegment.dropLast(NZB_EXT_LENGTH)
            else -> lastSegment
        }
    }

    fun InputStream.md5(): String = this.use { inputStream ->
        DigestUtils.md5Hex(inputStream)
    }


    fun history(request: SabnzbdApiRequest): SabnzbdHistoryResponse {
        if (request.name is String && request.name == "delete") {
            // Soft-delete (matches the queue-mode delete path which already does this). Hard
            // deleting here was orphaning every nzb_contents row whose download had been
            // imported and then deleted by Sonarr/Radarr — same pattern as the torrent side.
            usenetRepository.findById(request.value!!.toLong()).orElse(null)?.let { usenetDownload ->
                usenetRepository.markUsenetDownloadAsDeleted(usenetDownload)
            }
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

    /**
     * Register an after-commit hook that pings the arr matching [category] to
     * refresh its monitored downloads. The hook fires on the JDBC commit thread
     * after the @Transactional method has committed — by that time the SABnzbd
     * history endpoint will surface the new COMPLETED slot, so the arr's response
     * to the refresh will see it.
     */
    private fun scheduleArrRefreshAfterCommit(category: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No active transaction (shouldn't happen given @Transactional on the
            // caller, but guard regardless) — fire inline as best-effort.
            runBlocking { arrService.refreshMonitoredDownloads(category) }
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                runBlocking {
                    runCatching { arrService.refreshMonitoredDownloads(category) }
                        .onFailure { logger.warn("Arr refresh after-commit failed: {}", it.message) }
                }
            }
        })
    }
}
