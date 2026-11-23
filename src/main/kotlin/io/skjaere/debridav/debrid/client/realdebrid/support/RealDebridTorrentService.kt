package io.skjaere.debridav.debrid.client.realdebrid.support

import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.headers
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.TorrentsResponseItem
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentEntity
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentRepository
import io.skjaere.debridav.debrid.client.realdebrid.model.TorrentsInfo
import io.skjaere.debridav.torrent.TorrentHash
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

private const val BULK_SIZE = 100

@Component
class RealDebridTorrentService(
    private val realDebridConfigurationProperties: RealDebridConfigurationProperties,
    private val realDebridTorrentRepository: RealDebridTorrentRepository,
    private val httpClient: HttpClient,
    private val realDebridRateLimiter: RateLimiter,
    platformTransactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(platformTransactionManager)
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
        torrent.hash = torrentInfo.hash
        torrent.status = torrentInfo.status
        return torrent

    }

    suspend fun deleteTorrentFromDb(torrentId: String) {
        realDebridTorrentRepository.deleteByTorrentIdIgnoreCase(torrentId)
    }

    suspend fun getTorrentsByHash(hash: TorrentHash): List<RealDebridTorrentEntity> {
        return realDebridTorrentRepository.findTorrentsByHashIgnoreCase(hash.hash)
    }

    /**
     * Two-phase sync. Phase 1 paginates RD's `/torrents/` over HTTP with no DB tx
     * held (the previous `@Transactional fun = runBlocking { ... }` shape kept a
     * Hikari connection allocated for the entire multi-page traversal — minutes for
     * users with thousands of torrents — and competed with arr-side `add` traffic
     * for the small pool, surfacing as silent stream-startup stalls). Phase 2
     * applies the delete-stale + upsert in a single short tx so the DB-side state
     * change is atomic. Any error during HTTP fetch leaves the DB untouched.
     */
    fun syncTorrentListToDatabase() {
        // Phase 1: HTTP fetch (no DB tx)
        val remoteTorrents = runBlocking { getListOfTorrents() }

        // Phase 2: short DB tx for atomic delete+upsert
        transactionTemplate.execute { _ ->
            val remoteTorrentIds = remoteTorrents.map { it.id }.toSet()

            // Remove locally-stored torrents that no longer exist on RD
            realDebridTorrentRepository.findAll().forEach { local ->
                if (local.torrentId !in remoteTorrentIds) {
                    realDebridTorrentRepository.delete(local)
                }
            }

            // Upsert torrents from RD
            remoteTorrents.forEach { info ->
                val existing = realDebridTorrentRepository.getByTorrentIdIgnoreCase(info.id)
                val entity = existing ?: RealDebridTorrentEntity()
                entity.torrentId = info.id
                entity.name = info.filename
                entity.hash = info.hash
                entity.links = info.links
                entity.status = info.status
                realDebridTorrentRepository.save(entity)
            }
        }
    }

    /**
     * Mirror-only check: does the user's RD library currently hold the torrent for this hash
     * in a state RD can actually serve? Used by the health-check path so we don't have to
     * call `/torrents/info` per torrent during a sweep. Rows are written by the sync (every
     * `real-debrid.sync-poll-rate`) and by the add path once files are selected.
     */
    fun isAvailableInLibrary(hash: TorrentHash): Boolean {
        return realDebridTorrentRepository.findTorrentsByHashIgnoreCase(hash.hash)
            .any { it.links.isNotEmpty() && it.status == DOWNLOADED_STATUS }
    }

    /**
     * Torrent ids of the mirror rows for [hash] that the mirror cannot vouch for either way,
     * because they were never recorded in a settled state: rows without a status (V26 added
     * the column as NULL and only a sync fills it in) and rows without links (saved before
     * files were selected). The health check asks RD about these instead of treating them as
     * gone.
     */
    fun findUnconfirmedTorrentIds(hash: TorrentHash): List<String> =
        realDebridTorrentRepository.findTorrentsByHashIgnoreCase(hash.hash)
            .filter { it.status == null || it.links.isEmpty() }
            .mapNotNull { it.torrentId }

    /**
     * Drop every mirror row matching this hash so [isAvailableInLibrary] stops vouching for
     * it. Called when RD reports the torrent as permanently unavailable (DMCA/infringing /
     * Resource not found / File not allowed) — the next health check then falls through to
     * the live `isCached + getCachedFiles` path, fails the same way, and the torrent gets
     * enqueued for repair instead of being trusted by a stale mirror forever.
     */
    fun evictByHash(hash: TorrentHash) {
        transactionTemplate.execute {
            realDebridTorrentRepository.findTorrentsByHashIgnoreCase(hash.hash).forEach {
                realDebridTorrentRepository.delete(it)
            }
        }
    }

    private fun mapTorrentInfoToRdtEntity(info: TorrentsResponseItem): RealDebridTorrentEntity {
        val rdt = RealDebridTorrentEntity()
        rdt.torrentId = info.id
        rdt.name = info.filename
        rdt.hash = info.hash
        rdt.links = info.links
        rdt.status = info.status
        return rdt
    }


    @Suppress("MagicNumber")
    private suspend fun getListOfTorrents(): List<TorrentsResponseItem> {
        var offset = 0
        val bulkSize = BULK_SIZE
        val torrents = mutableListOf<TorrentsResponseItem>()
        var bulk: List<TorrentsResponseItem>
        do {
            bulk = getListOfTorrentsWithOffset(offset, bulkSize)
            torrents.addAll(bulk)
            offset += bulkSize
        } while (bulk.size == bulkSize)

        return torrents
    }

    @Suppress("MagicNumber")
    private suspend fun getListOfTorrentsWithOffset(offset: Int, numItems: Int): List<TorrentsResponseItem> {
        val resp = realDebridRateLimiter.executeSuspendFunction {
            httpClient
                .get("${realDebridConfigurationProperties.baseUrl}/torrents/") {
                    headers {
                        accept(ContentType.Application.Json)
                        bearerAuth(realDebridConfigurationProperties.apiKey)
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

    companion object {
        /** RD's status for a torrent it has finished and can serve. */
        const val DOWNLOADED_STATUS = "downloaded"
    }
}
