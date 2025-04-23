package io.skjaere.debridav.debrid.client

import io.skjaere.debridav.debrid.CachedContentKey
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.fs.CachedFile

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
}
