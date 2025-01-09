package io.skjaere.debridav.debrid.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.milton.http.Range
import io.skjaere.debridav.debrid.model.CachedFile

const val STREAMING_TIMEOUT_MS = 20_000_000L

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
}
