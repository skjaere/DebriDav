package io.skjaere.debridav.debrid

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.utils.io.errors.IOException
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.UnknownDebridError
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import org.springframework.stereotype.Component

@Component
interface DebridClient {
    @Throws(IOException::class)
    suspend fun isCached(magnet: String): Boolean

    @Throws(IOException::class)
    suspend fun getCachedFiles(magnet: String): List<CachedFile> = getCachedFiles(magnet, emptyMap())

    @Throws(IOException::class)
    suspend fun getCachedFiles(magnet: String, params: Map<String, String>): List<CachedFile>
    fun getProvider(): DebridProvider

    @Suppress("ThrowsCount","MagicNumber")
    suspend fun throwDebridProviderException(resp: HttpResponse): Nothing {
        when (resp.status.value) {
            in 400..499 -> {
                throw DebridClientError(resp.body<String>(), resp.status.value, resp.request.url.encodedPathAndQuery)
            }

            in 500..599 -> {
                throw DebridProviderError(resp.body<String>(), resp.status.value, resp.request.url.encodedPathAndQuery)
            }

            else -> {
                throw UnknownDebridError(resp.body<String>(), resp.status.value, resp.request.url.encodedPathAndQuery)
            }
        }
    }
}
