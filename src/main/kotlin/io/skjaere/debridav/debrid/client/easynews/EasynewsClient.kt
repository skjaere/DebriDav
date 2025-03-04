package io.skjaere.debridav.debrid.client.easynews

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpHeaders.Authorization
import io.ktor.http.isSuccess
import io.milton.http.Range
import io.skjaere.debridav.RateLimitingService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.CachedContentKey
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.torrent.TorrentService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.*

const val TIMEOUT_MS = 5_000L
const val RETRIES = 3L
const val MINIMUM_RUNTIME_SECONDS = 360L
const val MINIMUM_RELEASE_SIZE_MB = 400

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('easynews')}")
@Suppress("UnusedPrivateProperty")
class EasynewsClient(
    override val httpClient: HttpClient,
    private val easynewsConfiguration: EasynewsConfigurationProperties,
    private val easynewsReleaseNameMatchingService: EasynewsReleaseNameMatchingService,
    private val debridavConfiguration: DebridavConfigurationProperties,
    private val rateLimitingService: RateLimitingService
) : DebridCachedContentClient {
    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(EasynewsClient::class.java)
    private val auth = getBasicAuth()

    override suspend fun isCached(key: CachedContentKey): Boolean {
        return when (key) {
            is UsenetRelease -> isCached(key.releaseName)
            is TorrentMagnet -> isTorrentCached(key)
        }
    }

    private suspend fun isTorrentCached(key: TorrentMagnet): Boolean {
        if (!easynewsConfiguration.enabledForTorrents) return false
        return TorrentService
            .getNameFromMagnet(key.magnet)
            ?.let { isCached(it) }
            ?: run { false } // Can't search for content without a release name
    }


    override suspend fun getCachedFiles(key: CachedContentKey, params: Map<String, String>): List<CachedFile> {
        return when (key) {
            is UsenetRelease -> getCachedFiles(key.releaseName, mapOf())
            is TorrentMagnet -> {
                if (!easynewsConfiguration.enabledForTorrents) return emptyList()
                TorrentService.getNameFromMagnet(key.magnet)
                    ?.trim()
                    ?.let {
                        getCachedFiles(
                            it,
                            mapOf()
                        )
                    } ?: emptyList()
            }
        }
    }

    override suspend fun getStreamableLink(key: CachedContentKey, cachedFile: CachedFile): String? {
        return when (key) {
            is UsenetRelease -> getStreamableLink(key.releaseName)
            is TorrentMagnet -> TorrentService
                .getNameFromMagnet(key.magnet)
                ?.let { getStreamableLink(it) }
                ?: run { null } // Can't search for content without a release name
        }
    }

    override suspend fun isLinkAlive(debridLink: CachedFile): Boolean {
        return checkLink(debridLink.link!!)
    }

    @Suppress("MagicNumber")
    override suspend fun prepareStreamUrl(
        debridLink: CachedFile,
        range: Range?
    ): HttpStatement {
        return doWithRateLimit {
            httpClient.prepareGet(debridLink.link!!) {
                headers {
                    append(Authorization, auth)
                    range?.let { range ->
                        getByteRange(range, debridLink.size!!)?.let { byteRange ->
                            logger.info("applying range: $byteRange")
                            append(HttpHeaders.Range, byteRange)
                        }

                    }
                }
                timeout {
                    requestTimeoutMillis = 20_000_000
                    socketTimeoutMillis = 20_000_000
                    connectTimeoutMillis = 20_000_000
                }
            }
        }
    }

    private suspend fun isCached(key: String): Boolean {
        return search(key)?.let { searchResult ->
            if (searchResult.data.isNotEmpty()) {
                val link = getDownloadLinkFromSearchResult(searchResult)
                checkLink(link)
            } else false
        } == true
    }

    private fun getBasicAuth(): String =
        "${easynewsConfiguration.username}:${easynewsConfiguration.password}".let {
            "Basic ${Base64.getEncoder().encodeToString(it.toByteArray())}"
        }

    private suspend fun checkLink(link: String): Boolean {
        val response = flow {
            val result = doWithRateLimit {
                httpClient.head(link) {
                    headers {
                        append(Authorization, auth)
                    }
                    timeout {
                        socketTimeoutMillis = TIMEOUT_MS
                        connectTimeoutMillis = TIMEOUT_MS
                        requestTimeoutMillis = TIMEOUT_MS
                    }
                }
            }
            emit(result)
        }.retry(RETRIES)
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
                    params = params,
                    link = link,
                    provider = DebridProvider.EASYNEWS
                )
            )
        } ?: emptyList()
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
            val response = doWithRateLimit {
                httpClient.get(
                    "${easynewsConfiguration.apiBaseUrl}/2.0/search/solr-search/"
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
                        requestTimeoutMillis = TIMEOUT_MS
                        connectTimeoutMillis = TIMEOUT_MS
                    }
                }
            }
            emit(response.body<String>())
        }.retry(RETRIES)
            .first()

        val parsed: SearchResults = jsonParser.decodeFromString(body)
        val filtered = parsed.data.filter {
            easyNewsSearchResultSatisfiesSizeOrDuration(it) && easynewsReleaseNameMatchingService.matches(
                releaseName,
                it.releaseName
            )
        }
        return if (filtered.isNotEmpty()) {
            parsed.copy(data = filtered)
        } else null
    }

    private suspend fun getDownloadLinkFromSearchResult(results: SearchResults): String {
        val largestVideoInRelease = results.data.maxByOrNull { it.size }!!
        return generateDownloadLink(
            "${easynewsConfiguration.apiBaseUrl}/dl",
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

    @Suppress("MagicNumber")
    private fun easyNewsSearchResultSatisfiesSizeOrDuration(item: SearchResults.Item): Boolean {
        return item.runtime > MINIMUM_RUNTIME_SECONDS || item.size > 1024 * 1024 * MINIMUM_RELEASE_SIZE_MB
    }


    private suspend fun getMetaDataFromLink(link: String): Map<String, List<String>> {
        return flow {
            val result = doWithRateLimit {
                httpClient.head(link) {
                    headers {
                        append(Authorization, auth)
                    }
                    timeout {
                        requestTimeoutMillis = TIMEOUT_MS
                        connectTimeoutMillis = TIMEOUT_MS
                        socketTimeoutMillis = TIMEOUT_MS
                    }
                }.headers.entries()
                    .associate { headerEntry ->
                        headerEntry.key to headerEntry.value
                    }
            }

            emit(result)
        }.retry(RETRIES)
            .first()
    }

    @Suppress("LongParameterList")
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


    suspend fun getStreamableLink(releaseName: String): String? {
        return search(releaseName)?.let {
            getDownloadLinkFromSearchResult(it)
        }
    }

    override fun getProvider(): DebridProvider {
        return DebridProvider.EASYNEWS
    }

    private suspend fun <T> doWithRateLimit(block: suspend () -> T): T {
        return rateLimitingService.doWithRateLimit(
            "easynews",
            easynewsConfiguration.rateLimitWindowDuration,
            easynewsConfiguration.allowedRequestsInWindow
        ) {
            block()
        }
    }
}
