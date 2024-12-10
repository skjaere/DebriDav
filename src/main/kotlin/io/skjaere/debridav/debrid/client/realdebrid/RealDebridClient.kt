package io.skjaere.debridav.debrid.client.realdebrid

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.skjaere.debridav.debrid.DebridClient
import io.skjaere.debridav.debrid.client.realdebrid.model.HostedFile
import io.skjaere.debridav.debrid.client.realdebrid.model.Torrent
import io.skjaere.debridav.debrid.client.realdebrid.model.TorrentsInfo
import io.skjaere.debridav.debrid.client.realdebrid.model.UnrestrictedLink
import io.skjaere.debridav.debrid.client.realdebrid.model.response.AddMagnetResponse
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('real_debrid')}")
class RealDebridClient(
    private val realDebridConfiguration: RealDebridConfiguration,
    private val httpClient: HttpClient
) : DebridClient {
    private val logger = LoggerFactory.getLogger(RealDebridClient::class.java)

    init {
        require(realDebridConfiguration.apiKey.isNotEmpty()) {
            "Missing API key for Real Debrid"
        }
    }

    override suspend fun isCached(magnet: String): Boolean = coroutineScope {
        true
    }

    override suspend fun getCachedFiles(magnet: String, params: Map<String, String>): List<CachedFile> {
        if (params.containsKey("torrentId")) {
            val torrentId = params["torrentId"]!!
            return getCachedFilesFromTorrentId(torrentId)
        } else {
            logger.info("getting cached files from real debrid")
            return getCachedFilesFromTorrentId(addMagnet(magnet).id)
        }
    }

    private suspend fun getCachedFilesFromTorrentId(torrentId: String): List<CachedFile> {
        val movieExtensions = listOf(".mkv", ".mp4")
        return getCachedFilesFromTorrent(
            getAllFilesInTorrent(torrentId),
            movieExtensions,
            torrentId
        )
    }

    private suspend fun getCachedFilesFromTorrent(
        torrentInfo: Torrent,
        movieExtensions: List<String>,
        torrentId: String
    ): List<CachedFile> = coroutineScope {
        val filmFileIds = torrentInfo.files
            .filter { hostedFile -> movieExtensions.any { extension -> hostedFile.fileName.endsWith(extension) } }
            .map { hostedFile -> hostedFile.fileId }
        selectFilesFromTorrent(torrentId, filmFileIds)
        getTorrentInfoSelected(torrentId).let { selectedHostedFiles ->
            selectedHostedFiles
                .map { async { unrestrictLink(it.link!!) } }
                .awaitAll()
                .map { unrestrictedLink ->
                    logger.info("done getting cached files from real debrid")
                    CachedFile(
                        "${torrentInfo.name}/${unrestrictedLink.filename}",
                        unrestrictedLink.filesize,
                        unrestrictedLink.mimeType,
                        unrestrictedLink.download,
                        DebridProvider.REAL_DEBRID,
                        Instant.now().toEpochMilli(),
                        mapOf(
                            "torrentId" to torrentId,
                            "fileId" to unrestrictedLink.id
                        )
                    )
                }
        }
    }

    override fun getProvider(): DebridProvider = DebridProvider.REAL_DEBRID

    private suspend fun addMagnet(magnet: String): AddMagnetResponse {
        val response = httpClient
            .post("${realDebridConfiguration.baseUrl}/torrents/addMagnet") {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(realDebridConfiguration.apiKey)
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                setBody("magnet=$magnet")
            }
        if (response.status == HttpStatusCode.Created) {
            return response.body<AddMagnetResponse>()
        } else {
            throwDebridProviderException(response)
        }
    }

    private suspend fun getTorrentInfo(id: String): TorrentsInfo {
        return httpClient
            .get("${realDebridConfiguration.baseUrl}/torrents/info/$id") {
                headers {
                    set(HttpHeaders.Accept, "application/json")
                    bearerAuth(realDebridConfiguration.apiKey)
                }
            }.body<TorrentsInfo>()
    }

    private suspend fun getAllFilesInTorrent(id: String): Torrent {
        return getTorrentInfo(id)
            .let {
                Torrent(
                    it.id,
                    it.filename,
                    it.files.map { file ->
                        HostedFile(
                            file.id.toString(),
                            file.path,
                            file.bytes,
                            null
                        )
                    }
                )
            }
    }

    private suspend fun getTorrentInfoSelected(id: String): List<HostedFile> {
        return getTorrentInfo(id)
            .let { torrentInfo ->
                if (torrentInfo.links.isEmpty()) {
                    // Torrent is not instantly available
                    deleteTorrent(id)
                    return emptyList()
                }
                torrentInfo.files
                    .filter { file -> file.selected == 1 }
                    .mapIndexed { idx, file ->
                        torrentInfo.links[idx]?.let { }
                        HostedFile(
                            file.id.toString(),
                            file.path,
                            file.bytes,
                            torrentInfo.links[idx]
                        )
                    }
            }
    }

    private suspend fun deleteTorrent(torrentId: String) {
        val resp = httpClient
            .delete("${realDebridConfiguration.baseUrl}/torrents/delete/$torrentId") {
                accept(ContentType.Application.Json)
                bearerAuth(realDebridConfiguration.apiKey)
            }
        if (resp.status != HttpStatusCode.NoContent) {
            throwDebridProviderException(resp)
        }
    }

    @Suppress("MagicNumber")
    private suspend fun selectFilesFromTorrent(torrentId: String, fileIds: List<String>) {
        val resp = httpClient
            .post("${realDebridConfiguration.baseUrl}/torrents/selectFiles/$torrentId") {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(realDebridConfiguration.apiKey)
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                setBody("files=${fileIds.joinToString(",")}")
            }
        if (resp.status.value !in 200..299) {
            throwDebridProviderException(resp)
        }
    }

    private suspend fun unrestrictLink(link: String): UnrestrictedLink = coroutineScope {
        httpClient
            .post("${realDebridConfiguration.baseUrl}/unrestrict/link") {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(realDebridConfiguration.apiKey)
                    contentType(ContentType.Application.FormUrlEncoded)
                }
                setBody("link=$link")
            }.body<UnrestrictedLink>()
    }
}
