package io.skjaere.debridav.debrid.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.milton.http.Range
import io.skjaere.debridav.debrid.model.CachedFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry

const val STREAMING_TIMEOUT_MS = 20_000_000L
const val RETRIES = 3L

class DefaultStreamableLinkPreparer(override val httpClient: HttpClient) : StreamableLinkPreparable {

    override suspend fun prepareStreamUrl(debridLink: CachedFile, range: Range?): HttpStatement {
        return httpClient.prepareGet(debridLink.link!!) {
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

    override suspend fun isLinkAlive(debridLink: CachedFile): Boolean = flow {
        emit(httpClient.head(debridLink.link!!).status.isSuccess())
    }.retry(RETRIES)
        .first()
}
