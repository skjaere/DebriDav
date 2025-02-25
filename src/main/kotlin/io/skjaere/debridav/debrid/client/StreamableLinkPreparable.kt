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

    suspend fun isLinkAlive(
        debridLink: CachedFile
    ): Boolean

    fun getByteRange(range: Range, fileSize: Long): String? {
        val start = range.start ?: 0
        val finish = range.finish ?: (fileSize - 1)
        return if (start == 0L && finish == (fileSize - 1)) {
            null
        } else "bytes=$start-$finish"
    }
}
