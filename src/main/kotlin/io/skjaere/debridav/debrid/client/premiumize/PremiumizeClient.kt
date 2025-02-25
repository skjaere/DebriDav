package io.skjaere.debridav.debrid.client.premiumize

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.skjaere.debridav.debrid.DebridClient
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.DebridCachedTorrentClient
import io.skjaere.debridav.debrid.client.DefaultStreamableLinkPreparer
import io.skjaere.debridav.debrid.client.StreamableLinkPreparable
import io.skjaere.debridav.debrid.client.premiumize.model.CacheCheckResponse
import io.skjaere.debridav.debrid.client.premiumize.model.SuccessfulDirectDownloadResponse
import io.skjaere.debridav.fs.CachedFile
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('premiumize')}")
class PremiumizeClient(
    private val premiumizeConfiguration: PremiumizeConfiguration,
    override val httpClient: HttpClient,
    private val clock: Clock
) : DebridCachedTorrentClient, StreamableLinkPreparable by DefaultStreamableLinkPreparer(httpClient) {
    private val logger = LoggerFactory.getLogger(DebridClient::class.java)

    init {
        require(premiumizeConfiguration.apiKey.isNotEmpty()) {
            "Missing API key for Premiumize"
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun isCached(magnet: String): Boolean {
        val resp = httpClient
            .get(
                "${premiumizeConfiguration.baseUrl}/cache/check?items[]=$magnet&apikey=${premiumizeConfiguration.apiKey}"
            )
        if (resp.status != HttpStatusCode.OK) {
            throwDebridProviderException(resp)
        }
        return resp
            .body<CacheCheckResponse>()
            .response.first()

    }

    override suspend fun getStreamableLink(magnet: String, cachedFile: CachedFile): String? {
        return if (isCached(magnet)) {
            getDirectDlResponse(magnet)
                .content
                .firstOrNull { it.path == cachedFile.path }
                ?.link
        } else null
    }

    @Suppress("MaxLineLength")
    override suspend fun getCachedFiles(magnet: String, params: Map<String, String>): List<CachedFile> {
        return getCachedFilesFromResponse(
            getDirectDlResponse(magnet)
        )
    }

    private suspend fun getDirectDlResponse(magnet: String): SuccessfulDirectDownloadResponse {
        logger.info("getting cached files from premiumize")
        val resp =
            httpClient.post(
                "${premiumizeConfiguration.baseUrl}/transfer/directdl" +
                        "?apikey=${premiumizeConfiguration.apiKey}" +
                        "&src=$magnet"
            ) {
                headers {
                    set(HttpHeaders.ContentType, "multipart/form-data")
                    set(HttpHeaders.Accept, "application/json")
                }
            }

        if (resp.status != HttpStatusCode.OK) {
            throwDebridProviderException(resp)
        }
        return resp.body<SuccessfulDirectDownloadResponse>()
    }

    private fun getCachedFilesFromResponse(resp: SuccessfulDirectDownloadResponse) =
        resp.content.map {
            CachedFile(
                path = it.path,
                size = it.size,
                mimeType = "video/mp4",
                link = it.link,
                provider = getProvider(),
                lastChecked = Instant.now(clock).toEpochMilli(),
                params = emptyMap()
            )
        }

    override fun getProvider(): DebridProvider = DebridProvider.PREMIUMIZE
}
