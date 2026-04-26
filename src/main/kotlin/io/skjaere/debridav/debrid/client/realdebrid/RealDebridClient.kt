package io.skjaere.debridav.debrid.client.realdebrid

import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.skjaere.debridav.config.ConfigurationTester
import io.skjaere.debridav.config.TestResult
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.client.DebridCachedTorrentClient
import io.skjaere.debridav.debrid.client.DefaultStreamableLinkPreparer
import io.skjaere.debridav.debrid.client.StreamableLinkPreparable
import io.skjaere.debridav.debrid.client.realdebrid.model.HostedFile
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridDownload
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridDownloadEntity
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentEntity
import io.skjaere.debridav.debrid.client.realdebrid.model.Torrent
import io.skjaere.debridav.debrid.client.realdebrid.model.TorrentsInfo
import io.skjaere.debridav.debrid.client.realdebrid.model.response.AddMagnetResponse
import io.skjaere.debridav.debrid.client.realdebrid.model.response.FailedAddMagnetResponse
import io.skjaere.debridav.debrid.client.realdebrid.model.response.RealDebridErrorMessage
import io.skjaere.debridav.debrid.client.realdebrid.model.response.SuccessfulAddMagnetResponse
import io.skjaere.debridav.debrid.client.realdebrid.support.RealDebridDownloadService
import io.skjaere.debridav.debrid.client.realdebrid.support.RealDebridTorrentService
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.torrent.TorrentService
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private const val CREATED = 204
private const val NOT_FOUND = 404
private const val LINK_ID_MAP_KEY = "linkId"
private const val TORRENT_ID_MAP_KEY = "torrentId"

@Component
@Suppress("TooManyFunctions")
class RealDebridClient(
    private val realDebridConfigurationProperties: RealDebridConfigurationProperties,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    override val httpClient: HttpClient,
    private val realDebridTorrentService: RealDebridTorrentService,
    private val realDebridDownloadService: RealDebridDownloadService,
    private val realDebridRateLimiter: RateLimiter
) : DebridCachedTorrentClient, ConfigurationTester, StreamableLinkPreparable by DefaultStreamableLinkPreparer(
    httpClient,
    debridavConfigurationProperties,
    realDebridRateLimiter
) {
    private val logger = LoggerFactory.getLogger(RealDebridClient::class.java)
    private val knownVideoExtensions = listOf(".mp4", ".mkv", ".avi", ".ts")

    var torrentImportEnabled = realDebridConfigurationProperties.syncEnabled

    private fun isRealDebridConfigured(): Boolean =
        DebridProvider.REAL_DEBRID in debridavConfigurationProperties.debridClients &&
            realDebridConfigurationProperties.apiKey.isNotBlank()

    @Scheduled(
        initialDelay = 0, fixedRateString = "\${real-debrid.sync-poll-rate}"
    )
    fun syncTorrentsTask() {
        if (!isRealDebridConfigured()) return
        if (torrentImportEnabled) {
            runBlocking {
                launch {
                    realDebridTorrentService.syncTorrentListToDatabase()
                }
                launch {
                    realDebridDownloadService.syncDownloadsToDatabase()
                }
            }
        }
    }

    override suspend fun isCached(magnet: TorrentMagnet): Boolean = coroutineScope {
        true
    }

    @Transactional
    override suspend fun getCachedFiles(magnet: TorrentMagnet, params: Map<String, String>): List<CachedFile> {
        logger.info("getting cached files from real debrid")
        return realDebridTorrentService.getTorrentsByHash(magnet.getHash()!!)
            .firstOrNull()?.let { entity ->
                getCachedFilesFromTorrent(entity)
            } ?: run {
            when (val response = addMagnet(magnet)) {
                is SuccessfulAddMagnetResponse -> {
                    val torrentInfo = getTorrentInfo(response.id)
                    val realDebridTorrentEntity = realDebridTorrentService.saveTorrent(torrentInfo)
                    getCachedFilesFromTorrent(realDebridTorrentEntity)
                }

                is FailedAddMagnetResponse -> {
                    logger.info(
                        "Real Debrid refused to add torrent: " + "${TorrentService.getNameFromMagnet(magnet)} "
                                + "because ${response.reason}"
                    )
                    emptyList()
                }
            }
        }
    }


    private suspend fun getCachedFilesFromTorrent(torrent: RealDebridTorrentEntity): List<CachedFile> {
        return getCachedFilesFromTorrent(
            getAllFilesInTorrent(torrent.torrentId!!), torrent.torrentId!!
        )
    }

    private suspend fun getCachedFilesFromTorrent(
        torrentInfo: Torrent, torrentId: String
    ): List<CachedFile> = coroutineScope {
        val filmFileIds = getIdsToSelect(torrentInfo.files)
        if (filmFileIds.isNotEmpty()) selectFilesFromTorrent(torrentId, filmFileIds)

        getTorrentInfoSelected(torrentId).let { selectedHostedFiles ->
            val existingDownloads: Set<RealDebridDownloadEntity> = realDebridDownloadService.getAllDownloadsForLinks(
                selectedHostedFiles.map { it.link!! }.toSet()
            )
            val existingLinks = existingDownloads.map { it.link!! }.toSet()
            val urestricted = selectedHostedFiles.filter { !existingLinks.contains(it.link!!) }
                .map { async { unrestrictLink(it.link!!) } }
                .awaitAll()
            urestricted
                .filterIsInstance<SuccessfulUnrestrictLinkResponse>()
                .map { unrestrictedLink ->
                    mapUnrestrictedLinkToCachedFile(torrentInfo, unrestrictedLink.realDebridDownloadEntity, torrentId)
                }
                .union(
                    existingDownloads.map {
                        mapExistingDownloadToCachedFile(torrentInfo, it, torrentId)
                    }
                ).toList()
        }
    }

    private fun mapExistingDownloadToCachedFile(
        torrentInfo: Torrent, entity: RealDebridDownloadEntity, torrentId: String
    ): CachedFile = CachedFile(
        path = "${torrentInfo.name}/${entity.filename!!}",
        size = entity.fileSize!!,
        mimeType = entity.mimeType!!,
        link = entity.download!!,
        provider = DebridProvider.REAL_DEBRID,
        lastChecked = Instant.now().toEpochMilli(),
        params = mapOf(
            TORRENT_ID_MAP_KEY to torrentId, LINK_ID_MAP_KEY to entity.downloadId!!
        )
    )

    private fun mapUnrestrictedLinkToCachedFile(
        torrentInfo: Torrent, unrestrictedLink: RealDebridDownloadEntity, torrentId: String
    ): CachedFile = CachedFile(
        path = "${torrentInfo.name}/${unrestrictedLink.filename}",
        size = unrestrictedLink.fileSize!!,
        mimeType = unrestrictedLink.mimeType!!,
        link = unrestrictedLink.download!!,
        provider = DebridProvider.REAL_DEBRID,
        lastChecked = Instant.now().toEpochMilli(),
        params = mapOf(
            TORRENT_ID_MAP_KEY to torrentId, LINK_ID_MAP_KEY to unrestrictedLink.downloadId!!
        )
    )

    private fun getIdsToSelect(
        files: List<HostedFile>
    ): List<String> {
        //if (files.size == 1) return listOf(files.first().fileId)
        return files
            .filter { file ->
                knownVideoExtensions
                    .any { extension -> file.fileName.endsWith(extension) }
            }.filter { !it.selected }
            .map { it.fileId }
    }

    override fun getProvider(): DebridProvider = DebridProvider.REAL_DEBRID

    override fun logger(): Logger {
        return logger
    }

    private suspend fun addMagnet(magnet: TorrentMagnet): AddMagnetResponse {
        val payload = "magnet=${magnet.magnet}"
        val response = httpClient.post("${realDebridConfigurationProperties.baseUrl}/torrents/addMagnet") {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(realDebridConfigurationProperties.apiKey)
                contentType(ContentType.Application.FormUrlEncoded)
            }
            setBody(FormDataContent(Parameters.build {
                append("magnet", magnet.magnet)
            }))
        }
        when (response.status) {
            HttpStatusCode.Created -> {
                return response.body<SuccessfulAddMagnetResponse>()
            }

            else -> {
                try {
                    val errorMessage = Json.decodeFromString(RealDebridErrorMessage.serializer(), response.body())
                    return FailedAddMagnetResponse(errorMessage.error)
                } catch (_: Exception) {
                    throwDebridProviderException(response, "/torrents/addMagnet", payload)
                }
            }
        }
    }


    private suspend fun getTorrentInfo(id: String): TorrentsInfo = realDebridRateLimiter.executeSuspendFunction {
        val resp = httpClient.get("${realDebridConfigurationProperties.baseUrl}/torrents/info/$id") {
            headers {
                set(HttpHeaders.Accept, "application/json")
                bearerAuth(realDebridConfigurationProperties.apiKey)
            }
        }
        if (!resp.status.isSuccess()) {
            // Don't try to deserialize the error body as TorrentsInfo — kotlinx.serialization
            // would throw a SerializationException with no useful message and the real HTTP
            // failure would never reach the caller. Funnel through the standard exception path.
            throwDebridProviderException(resp, "/torrents/info/$id")
        }
        resp.body<TorrentsInfo>()
    }


    private suspend fun getAllFilesInTorrent(id: String): Torrent {
        return getTorrentInfo(id).let {
            Torrent(
                it.id, it.filename, it.files.map { file ->
                    HostedFile(
                        file.id.toString(), file.path, file.bytes, null, file.selected == 1
                    )
                })
        }
    }

    private suspend fun getTorrentInfoSelected(id: String): List<HostedFile> {
        return getTorrentInfo(id).let { torrentInfo ->
            if (torrentInfo.links.isEmpty()) {
                // Torrent is not instantly available
                deleteTorrent(id)
                return emptyList()
            }
            torrentInfo.files.filter { file -> file.selected == 1 }.mapIndexed { idx, file ->
                HostedFile(
                    file.id.toString(),
                    file.path,
                    file.bytes,
                    torrentInfo.links[idx],
                    selected = file.selected == 1
                )
            }
        }
    }

    private suspend fun deleteTorrent(torrentId: String) {
        val resp = realDebridRateLimiter.executeSuspendFunction {
            httpClient.delete("${realDebridConfigurationProperties.baseUrl}/torrents/delete/$torrentId") {
                accept(ContentType.Application.Json)
                bearerAuth(realDebridConfigurationProperties.apiKey)
            }
        }

        if (resp.status != HttpStatusCode.NoContent) {
            throwDebridProviderException(resp, "/torrents/delete/$torrentId")
        }
        realDebridTorrentService.deleteTorrentFromDb(torrentId)
    }

    @Suppress("MagicNumber")
    private suspend fun selectFilesFromTorrent(torrentId: String, fileIds: List<String>) {
        val resp = realDebridRateLimiter.executeSuspendFunction {
            httpClient.post("${realDebridConfigurationProperties.baseUrl}/torrents/selectFiles/$torrentId") {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(realDebridConfigurationProperties.apiKey)
                }
                setBody(FormDataContent(Parameters.build {
                    append("files", fileIds.joinToString(","))
                }))
            }
        }
        if (!resp.status.isSuccess()) {
            throwDebridProviderException(resp, "/torrents/selectFiles/$torrentId", fileIds.joinToString(","))
        }
    }

    private suspend fun unrestrictLink(link: String): UnrestrictLinkResult = coroutineScope {
        val response = realDebridRateLimiter.executeSuspendFunction {
            httpClient.post("${realDebridConfigurationProperties.baseUrl}/unrestrict/link") {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(realDebridConfigurationProperties.apiKey)
                }
                setBody(FormDataContent(Parameters.build {
                    append("link", link)
                }))
            }
        }
        logger.debug("unrestricted link: ${response.body<String>()}")
        if (response.status.isSuccess()) {
            response.body<RealDebridDownload>().let {
                val entity = realDebridDownloadService.saveDownload(it)
                SuccessfulUnrestrictLinkResponse(entity)
            }
        } else {
            val responseBody = runCatching { response.body<Map<String, String>>() }.getOrDefault(emptyMap())
            logger.warn(
                "could not unrestrict link {}: HTTP {} error={} error_code={}",
                link, response.status.value, responseBody["error"], responseBody["error_code"]
            )
            ErrorUnrestrictLinkResponse(responseBody["error"])
        }
    }

    sealed interface UnrestrictLinkResult
    data class SuccessfulUnrestrictLinkResponse(val realDebridDownloadEntity: RealDebridDownloadEntity) :
        UnrestrictLinkResult

    data class ErrorUnrestrictLinkResponse(val error: String?) : UnrestrictLinkResult

    private suspend fun deleteDownload(downloadId: String) {
        val response = realDebridRateLimiter.executeSuspendFunction {
            httpClient.delete("${realDebridConfigurationProperties.baseUrl}/downloads/delete/$downloadId") {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(realDebridConfigurationProperties.apiKey)
                }
            }
        }
        if (!listOf(CREATED, NOT_FOUND).contains(response.status.value)) {
            throwDebridProviderException(response, "/downloads/delete/$downloadId")
        }
    }


    override suspend fun getStreamableLink(key: TorrentMagnet, cachedFile: CachedFile): String? {
        val existing = realDebridDownloadService.getDownloadByHashAndFilenameAndSize(
            cachedFile.path!!,
            cachedFile.size!!,
            key.getHash()!!
        )
        if (existing?.download != null && isDownloadUrlAlive(existing.download!!)) {
            return existing.download
        }
        if (existing != null) {
            logger.info(
                "RD download {} (link={}) is no longer alive — deleting and refreshing",
                existing.downloadId, existing.link
            )
            existing.downloadId?.let { id -> runCatching { deleteDownload(id) } }
            realDebridDownloadService.deleteDownload(existing)
        }
        // Reuse the cached share link when we have one — it's the same value
        // getFreshRealDebridLink would resolve from the torrent, but skipping
        // that lookup means one less round-trip.
        val shareLink = existing?.link
            ?: getFreshRealDebridLink(key, cachedFile.path!!, cachedFile.size!!)
        return shareLink?.let {
            when (val result = unrestrictLink(it)) {
                is SuccessfulUnrestrictLinkResponse -> result.realDebridDownloadEntity.download
                else -> null
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun isDownloadUrlAlive(url: String): Boolean = try {
        realDebridRateLimiter.executeSuspendFunction {
            httpClient.head(url).status.isSuccess()
        }
    } catch (e: Exception) {
        logger.debug("HEAD on RD download URL failed; treating as dead: {}", e.message)
        false
    }

    suspend fun getFreshRealDebridLink(magnet: TorrentMagnet, filename: String, filesize: Long): String? {
        val torrents = realDebridTorrentService.getTorrentsByHash(magnet.getHash()!!)
        if (torrents.size > 1) {
            logger.warn("Found multiple torrents with hash: ${magnet.getHash()}")
        }
        val x = torrents.firstOrNull()?.let { torrent: RealDebridTorrentEntity ->
            val selectedFiles = getTorrentInfoSelected(torrent.torrentId!!)
            selectedFiles
                .filter { it.fileName == filename }
                .firstOrNull { it.fileSize == filesize }
                ?.link

        }
        return x
    }

    override val configurationClass: KClass<*> = RealDebridConfigurationProperties::class
    override val label: String = "Real-Debrid"

    @Suppress("TooGenericExceptionCaught")
    override suspend fun test(overrides: Map<String, String>): TestResult = try {
        val baseUrl = overrides["real-debrid.base-url"] ?: realDebridConfigurationProperties.baseUrl
        val apiKey = overrides["real-debrid.api-key"] ?: realDebridConfigurationProperties.apiKey

        val response = httpClient.get("$baseUrl/user") {
            accept(ContentType.Application.Json)
            bearerAuth(apiKey)
        }
        if (response.status.isSuccess()) {
            TestResult(success = true, message = "Connected successfully")
        } else {
            TestResult(success = false, message = "HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
    } catch (e: Exception) {
        TestResult(success = false, message = e.message ?: "Unknown error")
    }
}
