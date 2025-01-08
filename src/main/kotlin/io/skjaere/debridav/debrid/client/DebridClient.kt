package io.skjaere.debridav.debrid.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.milton.http.Range
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import java.net.HttpURLConnection
import java.net.URI

const val STREAMING_TIMEOUT_MS = 20_000_000L

interface DebridClient {
    val httpClient: HttpClient
    fun getProvider(): DebridProvider
    fun getMsToWaitFrom429Response(httpResponse: HttpResponse): Long
    fun getCookiesForStreaming(): Map<String, String> = emptyMap()

    suspend fun prepareStreamUrl(
        debridLink: CachedFile,
        range: Range?
    ): HttpStatement {
        return httpClient.prepareGet(debridLink.link) {
            headers {
                range?.let { range ->
                    getByteRange(range, debridLink.size)?.let { byteRange ->
                        append(HttpHeaders.Range, byteRange)
                    }
                }
            }
            timeout {
                requestTimeoutMillis = STREAMING_TIMEOUT_MS
            }
        }
    }

    suspend fun isLinkAlive(
        debridLink: CachedFile
    ): Boolean = flow {
        emit(httpClient.head(debridLink.link).status.isSuccess())
    }.retry(3)
        .first()


    fun getByteRange(range: Range, fileSize: Long): String? {
        val start = range.start ?: 0
        val finish = range.finish ?: (fileSize - 1)
        return if (start == 0L && finish == (fileSize - 1)) {
            null
        } else "bytes=$start-$finish"
    }

    fun openConnection(link: String): HttpURLConnection {
        return URI(link).toURL().openConnection() as HttpURLConnection
    }
}
