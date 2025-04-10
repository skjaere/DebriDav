package io.skjaere.debridav.cache

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.skjaere.debridav.StreamingService
import io.skjaere.debridav.cache.FileChunkCachingService.ByteRangeInfo
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap

class DefaultStreamCacher(
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val transactionTemplate: TransactionTemplate,
    private val fileChunkCachingService: FileChunkCachingService,
) : StreamCacher {
    private val logger = LoggerFactory.getLogger(DefaultStreamCacher::class.java)
    private val locks = ConcurrentHashMap<CacheLockKey, Mutex>()

    override fun getLock(entityId: Long, startByte: Long, endByte: Long): Mutex {
        locks.putIfAbsent(CacheLockKey(entityId, startByte, endByte), Mutex())
        return locks.getValue(CacheLockKey(entityId, startByte, endByte))
    }

    override suspend fun <T> runWithLockIfNeeded(entityId: Long, range: ByteRangeInfo?, block: suspend () -> T): T {
        return if (range != null && responseShouldBeCached(range)) {
            getLock(entityId, range.start, range.finish).withLock {
                val result = block.invoke()
                result
            }
        } else block.invoke()
    }

    override fun responseShouldBeCached(range: ByteRangeInfo): Boolean =
        range.length() <= debridavConfigurationProperties.chunkCachingSizeThreshold

    override fun responseShouldBeCached(resp: HttpResponse, byteRangeInfo: ByteRangeInfo?): Boolean {
        if (resp.headers.contains("content-range")) {
            return byteRangeInfo?.let {
                it.length() <= debridavConfigurationProperties.chunkCachingSizeThreshold
            } == true
        }
        return false
    }

    override suspend fun FlowCollector<StreamingService.Result>.serveCachedContentIfAvailable(
        range: ByteRangeInfo?,
        debridLink: CachedFile,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ): StreamingService.Result? {
        return if (range != null && responseShouldBeCached(range)) {
            transactionTemplate.execute {
                fileChunkCachingService.getCachedChunk(
                    remotelyCachedEntity,
                    debridLink.size!!,
                    debridLink.provider!!,
                    range
                )?.use { tempBlobInputStream ->
                    outputStream.use { usableOutputStream ->
                        tempBlobInputStream.transferTo(usableOutputStream)
                    }
                    StreamingService.Result.OK
                }
            }
        } else null
    }

    override suspend fun FlowCollector<StreamingService.Result>.cacheChunkAndRespond(
        resp: HttpResponse,
        outputStream: OutputStream,
        debridLink: CachedFile,
        byteRangeInfo: ByteRangeInfo,
        remotelyCachedEntity: RemotelyCachedEntity
    ) {
        resp.headers["content-range"]?.let { contentRange ->
            resp.bodyAsChannel().toInputStream().use { httpInputStream ->
                val blobInputStream = PipedInputStream()
                val blobOutputStream = PipedOutputStream(blobInputStream)
                logger.debug("begin streaming chunk to database")

                saveStreamToDatabase(
                    blobInputStream,
                    remotelyCachedEntity,
                    byteRangeInfo,
                    debridLink,
                    blobOutputStream,
                    httpInputStream
                )
                logger.debug("begin streaming chunk to client")
                streamChunkFromDatabase(remotelyCachedEntity, byteRangeInfo, debridLink, outputStream)
            }
        }
        emit(StreamingService.Result.OK)
    }

    private fun streamChunkFromDatabase(
        remotelyCachedEntity: RemotelyCachedEntity,
        byteRangeInfo: ByteRangeInfo,
        debridLink: CachedFile,
        outputStream: OutputStream
    ): Long? = transactionTemplate.execute {
        fileChunkCachingService.getCachedChunk(
            remotelyCachedEntity,
            byteRangeInfo.length(),
            debridLink.provider!!,
            byteRangeInfo

        )?.use { tempBlobInputStream ->
            outputStream.use { usableOutputStream ->
                tempBlobInputStream.transferTo(usableOutputStream)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun saveStreamToDatabase(
        blobInputStream: PipedInputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        byteRangeInfo: ByteRangeInfo,
        debridLink: CachedFile,
        blobOutputStream: PipedOutputStream,
        httpInputStream: InputStream
    ) {
        runBlocking {
            launch {
                transactionTemplate.execute { transaction ->
                    fileChunkCachingService.cacheChunk(
                        blobInputStream,
                        remotelyCachedEntity,
                        byteRangeInfo.start,
                        byteRangeInfo.finish,
                        debridLink.provider!!,
                    )
                }
            }
            withContext(Dispatchers.IO) {
                blobOutputStream.use { usableChunkOutputStream ->
                    httpInputStream.transferTo(usableChunkOutputStream)
                    logger.debug("done reading chunk from debrid")
                }
            }
        }
    }

    data class CacheLockKey(
        val entityId: Long,
        val startByte: Long,
        val endByte: Long,
    )
}
