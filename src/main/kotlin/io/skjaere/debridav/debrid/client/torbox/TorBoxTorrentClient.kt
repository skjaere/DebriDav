package io.skjaere.debridav.debrid.client.torbox

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.headers
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.skjaere.debridav.debrid.client.DebridTorrentClient
import io.skjaere.debridav.debrid.client.realdebrid.MagnetParser.getHashFromMagnet
import io.skjaere.debridav.debrid.client.torbox.model.torrent.CreateTorrentResponse
import io.skjaere.debridav.debrid.client.torbox.model.torrent.DownloadLinkResponse
import io.skjaere.debridav.debrid.client.torbox.model.torrent.IsCachedResponse
import io.skjaere.debridav.debrid.client.torbox.model.torrent.TorrentListItemFile
import io.skjaere.debridav.debrid.client.torbox.model.torrent.TorrentListResponse
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.fs.DebridProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
class TorBoxTorrentClient(
    private val httpClient: HttpClient, private val torBoxConfiguration: TorBoxConfiguration
) : DebridTorrentClient {

    companion object {
        const val API_TIMEOUT_MS = 20_000L
        const val TORRENT_ID_KEY = "torrent_id"
        const val TORRENT_FILE_ID_KEY = "file_id"
    }

    private val logger = LoggerFactory.getLogger(TorBoxTorrentClient::class.java)

    override suspend fun isCached(magnet: String): Boolean {
        val hash = getHashFromMagnet(magnet)
        val response = httpClient.get("${torBoxConfiguration.apiUrl}/api/torrents/checkcached?hash=$hash") {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
            timeout {
                requestTimeoutMillis = API_TIMEOUT_MS
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
            logger.debug("getting cached files from torbox")
            return getCachedFilesFromTorrentId(
                addMagnet(magnet)
            )
        }
    }

    override suspend fun getStreamableLink(magnet: String, cachedFile: CachedFile): String? {
        return getDownloadLinkFromTorrentAndFile(
            cachedFile.params[TORRENT_ID_KEY]!!, cachedFile.params[TORRENT_FILE_ID_KEY]!!
        )
    }

    private suspend fun getCachedFilesFromTorrentId(torrentId: String): List<CachedFile> = coroutineScope {
        val response = httpClient.get("${torBoxConfiguration.apiUrl}/api/torrents/mylist?id=$torrentId") {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
            timeout {
                requestTimeoutMillis = API_TIMEOUT_MS
            }
        }
        if (response.status.isSuccess()) {
            response.body<TorrentListResponse>().data
                ?.files?.map {
                    async {
                        it.toCachedFile(torrentId)
                    }
                }?.awaitAll() ?: emptyList()
        } else {
            throwDebridProviderException(response)
        }
    }

    private suspend fun TorrentListItemFile.toCachedFile(torrentId: String) = CachedFile(
        path = this.name,
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
            "${torBoxConfiguration.apiUrl}/api/torrents/requestdl"
                    + "?token=${torBoxConfiguration.apiKey}"
                    + "&torrent_id=$torrentId" + "&file_id=$fileId"
        ) {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
            timeout {
                requestTimeoutMillis = API_TIMEOUT_MS
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
            url = "${torBoxConfiguration.apiUrl}/api/torrents/createtorrent", formParameters = parameters {
                append("magnet", magnet)
                append("seed", "3")
                append("as_queued", "false")
            }) {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
            timeout {
                requestTimeoutMillis = API_TIMEOUT_MS
            }
        }
        if (!response.status.isSuccess()) {
            throwDebridProviderException(response)
        }
        val parsedResponse = response.body<CreateTorrentResponse>()
        if (parsedResponse.success) {
            return parsedResponse.data.torrentId
        } else {
            logger.error("Error adding magnet to TorBox: ${parsedResponse.error}")
            throw DebridProviderError(
                parsedResponse.details ?: "",
                response.status.value,
                "/api/torrents/createtorrent"
            )
        }
    }

    override fun getProvider(): DebridProvider {
        return DebridProvider.TORBOX
    }

    @Suppress("MagicNumber")
    override fun getMsToWaitFrom429Response(httpResponse: HttpResponse): Long {
        return (httpResponse.headers["x-ratelimit-after"]?.toLong() ?: 2).let {
            if (it == 0L) 1 else it
        } * 1000
    }
}
