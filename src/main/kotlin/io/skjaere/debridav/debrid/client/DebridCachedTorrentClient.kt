package io.skjaere.debridav.debrid.client

import io.skjaere.debridav.debrid.CachedContentKey
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.debrid.model.CachedFile

interface DebridCachedTorrentClient : DebridCachedContentClient {
    override suspend fun getCachedFiles(key: CachedContentKey, params: Map<String, String>): List<CachedFile> {
        return when (key) {
            is TorrentMagnet -> getCachedFiles(key.magnet, params)
            is UsenetRelease -> emptyList()
        }
    }

    override suspend fun isCached(key: CachedContentKey): Boolean {
        return when (key) {
            is TorrentMagnet -> isCached(key.magnet)
            is UsenetRelease -> false
        }
    }

    override suspend fun getStreamableLink(key: CachedContentKey, cachedFile: CachedFile): String? {
        return when (key) {
            is TorrentMagnet -> getStreamableLink(key.magnet, cachedFile)
            is UsenetRelease -> null
        }
    }

    suspend fun getStreamableLink(key: String, cachedFile: CachedFile): String?


    suspend fun getCachedFiles(magnet: String, params: Map<String, String>): List<CachedFile>
    suspend fun isCached(magnet: String): Boolean
}
