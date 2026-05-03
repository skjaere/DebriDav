package io.skjaere.debridav.debrid.client

import io.skjaere.debridav.debrid.CachedContentKey
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.torrent.Torrent

interface DebridCachedTorrentClient : DebridCachedContentClient {
    override suspend fun getCachedFiles(key: CachedContentKey, params: Map<String, String>): List<CachedFile> {
        return when (key) {
            is TorrentMagnet -> getCachedFiles(key, params)
            is UsenetRelease -> emptyList()
        }
    }

    override suspend fun isCached(key: CachedContentKey): Boolean {
        return when (key) {
            is TorrentMagnet -> isCached(key)
            is UsenetRelease -> false
        }
    }

    override suspend fun getStreamableLink(key: CachedContentKey, cachedFile: CachedFile): String? {
        return when (key) {
            is TorrentMagnet -> getStreamableLink(key, cachedFile)
            is UsenetRelease -> null
        }
    }

    suspend fun getStreamableLink(key: TorrentMagnet, cachedFile: CachedFile): String?


    suspend fun getCachedFiles(magnet: TorrentMagnet, params: Map<String, String>): List<CachedFile>
    suspend fun isCached(magnet: TorrentMagnet): Boolean

    /**
     * Health-check availability probe. Answers "can this provider currently serve this
     * torrent?" without doing the per-file HEAD probing that the cascade in
     * `DebridLinkService.getFlowOfDebridLinks` triggers during streaming.
     *
     * Default implementation forwards to [isCached]; that's the right answer for providers
     * with a real cache-check API (TorBox, Premiumize). Real-Debrid overrides this to
     * consult its synced local mirror of `/torrents/` instead, since RD doesn't expose a
     * cache-check endpoint and `isCached` returns true unconditionally.
     */
    suspend fun isHealthy(torrent: Torrent): Boolean {
        val magnet = torrent.magnet ?: return false
        return isCached(TorrentMagnet(magnet))
    }

    /**
     * Pre-sweep hook called by manual `triggerFullHealthCheck()` before enqueueing, giving
     * the client a chance to refresh whatever local state [isHealthy] depends on. Default
     * is a no-op — fine for providers whose [isHealthy] hits the API directly. Real-Debrid
     * overrides this to sync `/torrents/` into the local mirror so a manual sweep isn't
     * answering from stale data older than `real-debrid.sync-poll-rate`.
     */
    suspend fun prepareHealthCheck() {
        // default: no-op
    }
}
