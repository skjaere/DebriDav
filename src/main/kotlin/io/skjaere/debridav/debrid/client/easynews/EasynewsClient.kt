package io.skjaere.debridav.debrid.client.easynews

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpHeaders.Authorization
import io.ktor.http.isSuccess
import io.milton.http.Range
import io.skjaere.debridav.debrid.CachedContentKey
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.debrid.client.STREAMING_TIMEOUT_MS
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.qbittorrent.CachedContentTorrentService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.*


@Component
class EasynewsClient(
    override val httpClient: HttpClient,
    private val easynewsConfiguration: EasynewsConfigurationProperties
) : DebridCachedContentClient {
    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(EasynewsClient::class.java)
    private val auth = getBasicAuth()

    override suspend fun isCached(key: CachedContentKey): Boolean {
        return when (key) {
            is UsenetRelease -> isCached(key.releaseName)
            is TorrentMagnet -> isCached(
                CachedContentTorrentService.getNameFromMagnet(key.magnet)
            )
        }
    }

    override suspend fun getCachedFiles(key: CachedContentKey, params: Map<String, String>): List<CachedFile> {
        return when (key) {
            is UsenetRelease -> getCachedFiles(key.releaseName, mapOf())
            is TorrentMagnet -> getCachedFiles(
                CachedContentTorrentService.getNameFromMagnet(key.magnet),
                mapOf()
            )
        }
    }

    override suspend fun getStreamableLink(key: CachedContentKey, cachedFile: CachedFile): String? {
        return when (key) {
            is UsenetRelease -> getStreamableLink(key.releaseName, cachedFile)
            is TorrentMagnet -> getStreamableLink(
                CachedContentTorrentService.getNameFromMagnet(key.magnet),
                cachedFile
            )
        }
    }

    suspend fun isCached(key: String): Boolean {
        return search(key)?.let { searchResult ->
            if (searchResult.data.isNotEmpty()) {
                val link = getDownloadLinkFromSearchResult(searchResult)
                checkLink(link)
            } else false
        } ?: false
    }

    override suspend fun isLinkAlive(debridLink: CachedFile): Boolean {
        return checkLink(debridLink.link)
    }

    private fun getBasicAuth(): String =
        "${easynewsConfiguration.username}:${easynewsConfiguration.password}".let {
            "Basic ${Base64.getEncoder().encodeToString(it.toByteArray())}"
        }

    private suspend fun checkLink(link: String): Boolean {
        val response = flow {
            emit(httpClient.head(link) {
                headers {
                    append(Authorization, auth)
                }
                timeout {
                    socketTimeoutMillis = 20_000
                    connectTimeoutMillis = 20_000
                    requestTimeoutMillis = 20_000
                }
            }
            )
        }.retry(3)
            .first()
        logger.debug("checking $link")
        return response.status.isSuccess()
    }

    suspend fun getCachedFiles(releaseName: String, params: Map<String, String>): List<CachedFile> {
        return search(releaseName)?.let { result ->
            val link = getDownloadLinkFromSearchResult(result)
            val headers = getMetaDataFromLink(link)
            logger.info("got headers: $headers")
            listOf(
                CachedFile(
                    path = "${result.data.first().releaseName}${result.data.first().ext}",
                    size = result.data.first().rawSize,
                    mimeType = headers["Content-Type"]?.first() ?: "application/unknown",
                    lastChecked = Instant.now().toEpochMilli(),
                    params = mapOf(),
                    link = link,
                    provider = DebridProvider.EASYNEWS
                )
            )

        } ?: emptyList()
    }

    override suspend fun prepareStreamUrl(
        debridLink: CachedFile,
        range: Range?
    ): HttpStatement {
        return httpClient.prepareGet(debridLink.link) {
            headers {
                append(Authorization, auth)
                range?.let { range ->
                    getByteRange(range, debridLink.size)?.let { byteRange ->
                        logger.info("applying range: $byteRange")
                        append(HttpHeaders.Range, byteRange)
                    }

                }
            }
            timeout {
                requestTimeoutMillis = STREAMING_TIMEOUT_MS
            }
        }
    }

    override fun getByteRange(range: Range, fileSize: Long): String? { //TODO: use interface and delegation
        val start = range.start ?: 0
        val finish = range.finish ?: (fileSize - 1)
        return if (start == 0L && finish == fileSize) {
            null
        } else "bytes=$start-$finish"
    }

    private suspend fun search(releaseName: String): SearchResults? {
        val body = flow {
            val response = httpClient.get(
                "https://members.easynews.com/2.0/search/solr-search/"
            ) {
                url {
                    parameters.append("fly", "2")
                    parameters.append("YEAAAAAAAAAAAAH", "NO")
                    parameters.append("SelectOther", "ARCHIVE")
                    parameters.append("safeO", "0")
                    parameters.append("pby", "50")
                    parameters.append("u", "1")
                    parameters.append("sS", "3")
                    parameters.append("vv", "1")
                    parameters.append("fty[]", "VIDEO")
                    parameters.append("safe", "0")
                    parameters.append("sb", "1")
                    parameters.append("pno", "1")
                    parameters.append("chxu", "1")
                    parameters.append("pby", "50")
                    parameters.append("u", "1")
                    parameters.append("chxgx", "1")
                    parameters.append("st", "basic")
                    parameters.append("s", "1")
                    parameters.append("s1", "dtime")
                    parameters.append("s1d", "-")
                    parameters.append("s3", "3")
                    parameters.append("gps", releaseName)
                }
                headers {
                    append(Authorization, auth)
                    accept(ContentType.Application.Json)
                }
                timeout {
                    requestTimeoutMillis = 20_000
                    connectTimeoutMillis = 20_000
                }
            }

            emit(response.body<String>())
        }.retry(3)
            .first()

        val parsed: SearchResults = jsonParser.decodeFromString(body)
        val filtered = parsed.data.filter {
            it.runtime > 120L && it.releaseName == releaseName
        }
        return if (filtered.isNotEmpty()) {
            parsed.copy(data = filtered)
        } else null
    }

    private suspend fun getDownloadLinkFromSearchResult(results: SearchResults): String {
        val largestVideoInRelease = results.data.maxByOrNull { it.size }!!
        return generateDownloadLink(
            "https://members.easynews.com/dl",
            results.dlFarm,
            results.dlPort,
            largestVideoInRelease.hash,
            largestVideoInRelease.ext,
            largestVideoInRelease.releaseName,
            largestVideoInRelease.id,
            results.sid,
            0,
            largestVideoInRelease.sig
        )
    }

    private suspend fun getMetaDataFromLink(link: String): Map<String, List<String>> {
        return flow {
            emit(
                httpClient.head(link) {
                    headers {
                        append(Authorization, auth)
                    }
                    timeout {
                        requestTimeoutMillis = 20_000
                        connectTimeoutMillis = 20_000
                        socketTimeoutMillis = 20_000
                    }
                }.headers.entries()
                    .associate { headerEntry ->
                        headerEntry.key to headerEntry.value
                    }
            )
        }.retry(3)
            .first()
    }

    private fun generateDownloadLink(
        base: String,
        farm: String?,
        port: Int,
        hash: String,
        ext: String,
        filename: String,
        id: String?,
        sid: String?,
        num: Int?,
        sig: String
    ): String {
        var dlFarm = ""
        if (farm != null) {
            dlFarm = "/$farm/$port"
        }

        var dlId = ""
        if (id != null) {
            dlId = id
        }

        var dlNum = 0
        if (num != null) {
            dlNum = num
        }

        val newHash = "$hash$dlId"
        val hashExt = URLEncoder.encode("$newHash$ext", StandardCharsets.UTF_8)
        val filenameExt = URLEncoder.encode("$filename$ext", StandardCharsets.UTF_8)

        var downloadUrl = "$base$dlFarm/$hashExt/$filenameExt"
        if (sid != null) {
            downloadUrl += "?sid=$sid:$dlNum"
        }
        if (sig.isNotEmpty()) {
            downloadUrl += "&sig=$sig"
        }

        return downloadUrl
    }


    suspend fun getStreamableLink(releaseName: String, cachedFile: CachedFile): String? {
        return search(releaseName)?.let {
            getDownloadLinkFromSearchResult(it)
        }
    }

    override fun getProvider(): DebridProvider {
        return DebridProvider.EASYNEWS
    }

    override fun getMsToWaitFrom429Response(httpResponse: HttpResponse): Long {
        TODO("Not yet implemented")
    }
}