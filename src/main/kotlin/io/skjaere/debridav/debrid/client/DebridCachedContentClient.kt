package io.skjaere.debridav.debrid.client

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.utils.io.errors.IOException
import io.skjaere.debridav.debrid.CachedContentKey
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.UnknownDebridError
import io.skjaere.debridav.fs.CachedFile
import org.slf4j.Logger
import org.springframework.stereotype.Component

@Component
interface DebridCachedContentClient : StreamableLinkPreparable {
    @Throws(IOException::class)
    suspend fun isCached(key: CachedContentKey): Boolean

    suspend fun getCachedFiles(key: CachedContentKey, params: Map<String, String>): List<CachedFile>

    suspend fun getCachedFiles(key: CachedContentKey): List<CachedFile> = getCachedFiles(key, emptyMap())

    suspend fun getStreamableLink(key: CachedContentKey, cachedFile: CachedFile): String?

    @Suppress("ThrowsCount", "MagicNumber")
    suspend fun throwDebridProviderException(resp: HttpResponse): Nothing {
        when (resp.status.value) {
            in 400..499 -> {
                logger().error("${getProvider()} error: ${resp.body<String>()}")
                throw DebridClientError(resp.body<String>(), resp.status.value, resp.request.url.encodedPathAndQuery)
            }

            in 500..599 -> {
                logger().error(
                    "${getProvider()} error: ${resp.body<String>()} -" +
                            " ${resp.request.url.encodedPathAndQuery} - ${resp.status.value}"
                )
                throw DebridProviderError(
                    resp.body<String>(),
                    resp.status.value,
                    resp.request.url.encodedPathAndQuery
                )
            }

            else -> {
                throw UnknownDebridError(resp.body<String>(), resp.status.value, resp.request.url.encodedPathAndQuery)
            }
        }
    }

    fun getProvider(): DebridProvider

    fun logger(): Logger
}
