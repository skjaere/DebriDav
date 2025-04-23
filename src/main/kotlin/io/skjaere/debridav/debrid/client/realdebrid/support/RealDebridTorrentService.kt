package io.skjaere.debridav.debrid.client.realdebrid.support

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headers
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.TorrentsResponseItem
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentEntity
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentRepository
import io.skjaere.debridav.debrid.client.realdebrid.model.TorrentsInfo
import io.skjaere.debridav.ratelimiter.TimeWindowRateLimiter
import io.skjaere.debridav.torrent.TorrentHash
import jakarta.transaction.Transactional
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

private const val BULK_SIZE = 100

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('real_debrid')}")
class RealDebridTorrentService(
    private val realDebridConfigurationProperties: RealDebridConfigurationProperties,
    private val realDebridTorrentRepository: RealDebridTorrentRepository,
    private val httpClient: HttpClient,
    private val realDebridRateLimiter: TimeWindowRateLimiter
) {
    suspend fun saveTorrent(torrentInfo: TorrentsInfo): RealDebridTorrentEntity {
        return updateTorrentValues(
            realDebridTorrentRepository.getByTorrentIdIgnoreCase(torrentInfo.id) ?: RealDebridTorrentEntity(),
            torrentInfo
        ).let { realDebridTorrentRepository.save(it) }
    }

    private fun updateTorrentValues(
        torrent: RealDebridTorrentEntity,
        torrentInfo: TorrentsInfo
    ): RealDebridTorrentEntity {
        torrent.torrentId = torrentInfo.id
        torrent.links = torrentInfo.links
        torrent.name = torrentInfo.filename
        return torrent

    }

    suspend fun deleteTorrentFromDb(torrentId: String) {
        realDebridTorrentRepository.deleteByTorrentIdIgnoreCase(torrentId)
    }

    suspend fun getTorrentsByHash(hash: TorrentHash): List<RealDebridTorrentEntity> {
        return realDebridTorrentRepository.findTorrentsByHashIgnoreCase(hash.hash)
    }

    @Transactional
    fun syncTorrentListToDatabase(): Unit = runBlocking {
        realDebridTorrentRepository.deleteAll()
        getListOfTorrents().asSequence()
            .map { mapTorrentInfoToRdtEntity(it) }
            .toList()
            .let { realDebridTorrentRepository.saveAll(it) }
    }

    private fun mapTorrentInfoToRdtEntity(info: TorrentsResponseItem): RealDebridTorrentEntity {
        val rdt = RealDebridTorrentEntity()
        rdt.torrentId = info.id
        rdt.name = info.filename
        rdt.hash = info.hash
        rdt.links = info.links
        return rdt
    }


    @Suppress("MagicNumber")
    private suspend fun getListOfTorrents(): List<TorrentsResponseItem> {
        var offset = 0
        val bulkSize = BULK_SIZE
        val torrents = mutableListOf<TorrentsResponseItem>()
        var bulk: List<TorrentsResponseItem> = emptyList()
        do {
            bulk = getListOfTorrentsWithOffset(offset, bulkSize)
            torrents.addAll(bulk)
            offset += bulkSize
        } while (bulk.size == bulkSize)

        return torrents
    }

    @Suppress("MagicNumber")
    private suspend fun getListOfTorrentsWithOffset(offset: Int, numItems: Int): List<TorrentsResponseItem> {
        val resp = realDebridRateLimiter.doWithRateLimit {
            httpClient
                .get("${realDebridConfigurationProperties.baseUrl}/torrents/") {
                    headers {
                        accept(ContentType.Application.Json)
                        bearerAuth(realDebridConfigurationProperties.apiKey)
                        contentType(ContentType.Application.FormUrlEncoded)
                    }
                    url {
                        parameters.append("limit", numItems.toString())
                        if (offset > 0) parameters.append("offset", offset.toString())
                    }
                }
        }

        if (resp.status.value == 204) return emptyList()
        return resp.body()
    }
}
