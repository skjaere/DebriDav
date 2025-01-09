package io.skjaere.debridav.debrid

import io.ktor.utils.io.errors.IOException
import io.skjaere.debridav.debrid.client.StreamableLinkPreparable
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import org.springframework.stereotype.Component

@Component
interface DebridClient : StreamableLinkPreparable {
    @Throws(IOException::class)
    suspend fun isCached(key: CachedContentKey): Boolean

    @Throws(IOException::class)
    suspend fun getCachedFiles(key: CachedContentKey): List<CachedFile> = getCachedFiles(key, emptyMap())

    @Throws(IOException::class)
    suspend fun getCachedFiles(key: CachedContentKey, params: Map<String, String>): List<CachedFile>

    suspend fun getStreamableLink(key: CachedContentKey, cachedFile: CachedFile): String?


    fun getProvider(): DebridProvider

}
