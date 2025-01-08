package io.skjaere.debridav.debrid.client

import io.skjaere.debridav.debrid.CachedContentKey
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.debrid.model.CachedFile

interface DebridCachedUsenetClient : DebridCachedContentClient {

    override suspend fun getCachedFiles(key: CachedContentKey, params: Map<String, String>): List<CachedFile> {
        return when (key) {
            is TorrentMagnet -> emptyList()
            is UsenetRelease -> getCachedFiles(key.releaseName, params)
        }
    }

    override suspend fun isCached(key: CachedContentKey): Boolean {
        return when (key) {
            is TorrentMagnet -> false
            is UsenetRelease -> isCached(key.releaseName)
        }
    }

    override suspend fun getStreamableLink(key: CachedContentKey, cachedFile: CachedFile): String? {
        return when (key) {
            is TorrentMagnet -> null
            is UsenetRelease -> getStreamableLink(key.releaseName, cachedFile)
        }
    }

    suspend fun getCachedFiles(releaseName: String, params: Map<String, String>): List<CachedFile>
    suspend fun isCached(releaseName: String): Boolean
    suspend fun getStreamableLink(releaseName: String, cachedFile: CachedFile): String?
}