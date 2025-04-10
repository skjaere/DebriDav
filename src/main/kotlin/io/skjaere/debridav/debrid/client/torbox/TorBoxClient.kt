package io.skjaere.debridav.debrid.client.torbox

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.headers
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.DebridCachedTorrentClient
import io.skjaere.debridav.debrid.client.DefaultStreamableLinkPreparer
import io.skjaere.debridav.debrid.client.StreamableLinkPreparable
import io.skjaere.debridav.debrid.client.realdebrid.MagnetParser.getHashFromMagnet
import io.skjaere.debridav.debrid.client.torbox.model.torrent.CreateTorrentResponse
import io.skjaere.debridav.debrid.client.torbox.model.torrent.DownloadLinkResponse
import io.skjaere.debridav.debrid.client.torbox.model.torrent.IsCachedResponse
import io.skjaere.debridav.debrid.client.torbox.model.torrent.TorrentListItemFile
import io.skjaere.debridav.debrid.client.torbox.model.torrent.TorrentListResponse
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.ratelimiter.TimeWindowRateLimiter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

const val RATE_LIMIT_WINDOW_SIZE_SECONDS = 59L
const val RATE_LIMIT_REQUESTS_IN_WINDOW = 10

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
class TorBoxClient(
    override val httpClient: HttpClient,
    private val torBoxConfiguration: TorBoxConfiguration,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) : DebridCachedTorrentClient, StreamableLinkPreparable by DefaultStreamableLinkPreparer(
    httpClient,
    debridavConfigurationProperties,
    TimeWindowRateLimiter(Duration.ofSeconds(RATE_LIMIT_WINDOW_SIZE_SECONDS), RATE_LIMIT_REQUESTS_IN_WINDOW, "torbox")
) {

    companion object {
        const val TORRENT_ID_KEY = "torrent_id"
        const val TORRENT_FILE_ID_KEY = "file_id"
    }

    private val logger = LoggerFactory.getLogger(TorBoxClient::class.java)

    override suspend fun isCached(magnet: String): Boolean {
        val hash = getHashFromMagnet(magnet)
        val response = httpClient.get("${getBaseUrl()}/api/torrents/checkcached?hash=$hash") {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
        }
        if (response.status.isSuccess()) {
            logger.debug("isCached ${response.body<String>()}")
            return response.body<IsCachedResponse>().data?.isNotEmpty() ?: false
        } else {
            throwDebridProviderException(response)
        }
    }


    override suspend fun getCachedFiles(magnet: String, params: Map<String, String>): List<CachedFile> {
        if (params.containsKey(TORRENT_ID_KEY)) {
            val torrentId = params[TORRENT_ID_KEY]!!
            return getCachedFilesFromTorrentId(torrentId)
        } else {
            logger.info("getting cached files from torbox")
            return getCachedFilesFromTorrentId(addMagnet(magnet))
        }
    }

    override suspend fun getStreamableLink(
        key: String,
        cachedFile: CachedFile
    ): String? {
        return getDownloadLinkFromTorrentAndFile(
            cachedFile.params!![TORRENT_ID_KEY]!!,
            cachedFile.params!![TORRENT_FILE_ID_KEY]!!
        )
    }


    private suspend fun getCachedFilesFromTorrentId(torrentId: String): List<CachedFile> {
        val response = httpClient.get("${getBaseUrl()}/api/torrents/mylist?id=$torrentId") {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
        }
        if (response.status.isSuccess()) {
            val torrent = response.body<TorrentListResponse>().data ?: return emptyList()
            return torrent.files
                ?.map { it.toCachedFile(torrentId) }
                ?: emptyList()
        } else {
            throwDebridProviderException(response)
        }
    }

    private suspend fun TorrentListItemFile.toCachedFile(torrentId: String) = CachedFile(
        path = this.s3Path,
        size = this.size,
        mimeType = this.mimeType,
        provider = getProvider(),
        lastChecked = Instant.now().toEpochMilli(),
        link = getDownloadLinkFromTorrentAndFile(torrentId, this.id),
        params = mapOf(
            TORRENT_ID_KEY to torrentId,
            TORRENT_FILE_ID_KEY to this.id
        )

    )

    private suspend fun getDownloadLinkFromTorrentAndFile(torrentId: String, fileId: String): String {
        val response = httpClient.get(
            "${getBaseUrl()}/api/torrents/requestdl" +
                    "?token=${torBoxConfiguration.apiKey}" +
                    "&torrent_id=$torrentId" +
                    "&file_id=$fileId"
        ) {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
        }
        if (response.status.isSuccess()) {
            return response.body<DownloadLinkResponse>().data
        } else {
            throwDebridProviderException(response)
        }
    }

    private suspend fun addMagnet(magnet: String): String {
        val response = httpClient.submitForm(
            url = "${getBaseUrl()}/api/torrents/createtorrent",
            formParameters = parameters {
                append("magnet", magnet)
                append("seed", "3")
                append("as_queued", "false")
            }
        ) {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
        }
        if (response.status.isSuccess()) {
            return response.body<CreateTorrentResponse>().data.torrentId
        } else {
            throwDebridProviderException(response)
        }
    }

    override fun getProvider(): DebridProvider {
        return DebridProvider.TORBOX
    }

    private fun getBaseUrl(): String = "${torBoxConfiguration.baseUrl}/${torBoxConfiguration.version}"
}
