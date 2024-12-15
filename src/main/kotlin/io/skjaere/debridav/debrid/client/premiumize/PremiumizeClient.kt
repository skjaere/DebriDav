package io.skjaere.debridav.debrid.client.premiumize

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.skjaere.debridav.debrid.client.DebridTorrentClient
import io.skjaere.debridav.debrid.client.premiumize.model.CacheCheckResponse
import io.skjaere.debridav.debrid.client.premiumize.model.SuccessfulDirectDownloadResponse
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('premiumize')}")
class PremiumizeClient(
    private val premiumizeConfiguration: PremiumizeConfiguration,
    private val httpClient: HttpClient,
    private val clock: Clock
) : DebridTorrentClient {
    private val logger = LoggerFactory.getLogger(DebridTorrentClient::class.java)

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

    @Suppress("MaxLineLength")
    override suspend fun getCachedFiles(magnet: String, params: Map<String, String>): List<CachedFile> {
        val resp = getDirectDlResponse(magnet)

        return getCachedFilesFromResponse(resp)
    }

    override suspend fun getStreamableLink(magnet: String, cachedFile: CachedFile): String? =
        getDirectDlResponse(magnet)
            .content
            .firstOrNull { it.path == cachedFile.path }
            ?.link


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
                it.path,
                it.size,
                "video/mp4",
                it.link,
                getProvider(),
                Instant.now(clock).toEpochMilli()
            )
        }

    override fun getProvider(): DebridProvider = DebridProvider.PREMIUMIZE
    override fun getMsToWaitFrom429Response(httpResponse: HttpResponse): Long {
        TODO("Not yet implemented")
    }
}
