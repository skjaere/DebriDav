package io.skjaere.debridav.cache

import io.ktor.client.statement.HttpResponse
import io.skjaere.debridav.StreamingService
import io.skjaere.debridav.cache.FileChunkCachingService.ByteRangeInfo
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex
import java.io.OutputStream

interface StreamCacher {
    fun getLock(entityId: Long, startByte: Long, endByte: Long): Mutex
    suspend fun <T> runWithLockIfNeeded(entityId: Long, range: ByteRangeInfo?, block: suspend () -> T): T
    fun responseShouldBeCached(range: ByteRangeInfo): Boolean
    fun responseShouldBeCached(resp: HttpResponse, byteRangeInfo: ByteRangeInfo?): Boolean
    suspend fun FlowCollector<StreamingService.Result>.serveCachedContentIfAvailable(
        range: ByteRangeInfo?,
        debridLink: CachedFile,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ): StreamingService.Result?

    suspend fun FlowCollector<StreamingService.Result>.cacheChunkAndRespond(
        resp: HttpResponse,
        outputStream: OutputStream,
        debridLink: CachedFile,
        byteRangeInfo: ByteRangeInfo,
        remotelyCachedEntity: RemotelyCachedEntity
    )
}
