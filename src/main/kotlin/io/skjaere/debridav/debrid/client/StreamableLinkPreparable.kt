package io.skjaere.debridav.debrid.client

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpStatement
import io.milton.http.Range
import io.skjaere.debridav.fs.CachedFile

interface StreamableLinkPreparable {
    val httpClient: HttpClient
    suspend fun prepareStreamUrl(
        debridLink: CachedFile,
        range: Range?
    ): HttpStatement

    fun getStreamParams(
        debridLink: CachedFile,
        range: Range?
    ): StreamHttpParams

    suspend fun isLinkAlive(
        debridLink: CachedFile
    ): Boolean

    fun getByteRange(range: Range, fileSize: Long): ByteRange? {
        val start = range.start ?: 0
        val finish = range.finish ?: (fileSize - 1)
        return if (start == 0L && finish == (fileSize - 1)) {
            null
        } else ByteRange(start, finish)
    }


}

data class ByteRange(
    val start: Long,
    val end: Long
) {
    fun getSize(): Long = end - start
}

data class StreamHttpParams(
    val headers: Map<String, String>,
    val timeouts: Timeouts
) {
    data class Timeouts(
        val requestTimeoutMillis: Long,
        val socketTimeoutMillis: Long,
        val connectTimeoutMillis: Long
    )
}