package io.skjaere.debridav

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.cache.FileChunkCachingService.ByteRangeInfo
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val READ_BUFFER_SIZE = 256

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    private val fileChunkCachingService: FileChunkCachingService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)

    @Suppress("SwallowedException", "MagicNumber")
    @Transactional
    suspend fun streamDebridLink(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ): Result {
        val debridClient = debridClients.first { it.getProvider() == debridLink.provider }
        return flow {
            serveCachedContentIfAvailiable(range, debridLink, outputStream, remotelyCachedEntity)
                ?.let { emit(it) }
                ?: run {
                    try {
                        val prepared: HttpStatement = debridClient.prepareStreamUrl(debridLink, range)
                        prepared.execute(tryPipeResponse(debridLink, outputStream, remotelyCachedEntity))
                    } catch (_: ClientAbortException) {
                        emit(Result.OK)
                    }
                }


        }.retryWhen { cause, attempt ->
            if (attempt <= 5 && shouldRetryStreaming(cause)) {
                logger.info("retry attempt $attempt of ${debridLink.path} because $cause")
                delay(5_000 * attempt)
                true
            } else false
        }.catch {
            outputStream.close()
            emit(mapExceptionToResult(it))
        }.onEach {
            logger.info("Streaming of {} complete", debridLink.path!!.split("/").last())
            logger.info("Streaming result of {} was {}", debridLink.path, it)
        }.first()
    }

    private suspend fun FlowCollector<Result>.serveCachedContentIfAvailiable(
        range: Range?,
        debridLink: CachedFile,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ): Result? {
        return if (range != null) {
            fileChunkCachingService.getCachedChunk(
                remotelyCachedEntity,
                debridLink.size!!,
                debridLink.provider!!,
                range
            )?.let { cachedChunk ->
                logger.info("serving cached chunk")
                outputStream.use { usableOutputStream ->
                    cachedChunk.transferTo(usableOutputStream)
                }
                Result.OK
            }
        } else null
    }

    private fun FlowCollector<Result>.tryPipeResponse(
        debridLink: CachedFile,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ): suspend (response: HttpResponse) -> Unit = { resp ->
        if (!resp.status.isSuccess()) {
            logger.error(
                "Got response: ${resp.status.value} from $debridLink with body: ${
                    resp.body<String>()
                }"
            )
            throw LinkNotFoundException()
        }
        val byteRangeInfo = getByteRangeInfo(resp, debridLink.size!!)
        if (responseShouldBeCached(resp, byteRangeInfo)) {
            cacheChunkAndRespond(resp, outputStream, debridLink, byteRangeInfo!!, remotelyCachedEntity)
        } else {
            resp.bodyAsChannel().toInputStream().use { inputStream ->
                outputStream.use { usableOutputStream ->
                    logger.info("Begin streaming of ${debridLink.path} from ${debridLink.provider}")
                    withContext(Dispatchers.IO) {
                        inputStream.transferTo(usableOutputStream)
                    }
                    logger.info("Done streaming of ${debridLink.path} from ${debridLink.provider}")
                    emit(Result.OK)
                }
            }
        }
    }

    private fun responseShouldBeCached(resp: HttpResponse, byteRangeInfo: ByteRangeInfo?): Boolean {
        if (resp.headers.contains("content-range")) {
            return byteRangeInfo?.let {
                it.length() <= debridavConfigurationProperties.fileChunkCachingSizeThreshold
            } == true
        }
        return false
    }

    private fun getByteRangeInfo(
        resp: HttpResponse,
        fileSize: Long
    ): FileChunkCachingService.ByteRangeInfo? {
        if (!resp.headers.contains("content-range")) {
            return null
        }
        val range = resp.headers["content-range"]!!
            .substringAfterLast("bytes ")
            .substringBeforeLast("/")
            .split("-")

        val start = range.first().toLong()
        val end = range.last().toLong()
        val byteRangeInfo = fileChunkCachingService.getByteRange(start, end, fileSize)
        return byteRangeInfo
    }

    private suspend fun FlowCollector<Result>.cacheChunkAndRespond(
        resp: HttpResponse,
        outputStream: OutputStream,
        debridLink: CachedFile,
        byteRangeInfo: FileChunkCachingService.ByteRangeInfo,
        remotelyCachedEntity: RemotelyCachedEntity
    ) {
        resp.headers["content-range"]?.let { contentRange ->
            resp.bodyAsChannel().toInputStream().use {
                outputStream.use { usableOutputStream ->
                    val blobInputStream = PipedInputStream()
                    val blobOutputStream = PipedOutputStream(blobInputStream)
                    coroutineScope {
                        launch {
                            fileChunkCachingService.cacheChunk(
                                blobInputStream,
                                remotelyCachedEntity,
                                byteRangeInfo.start,
                                byteRangeInfo.finish,
                                debridLink.provider!!,
                            )
                        }
                        withContext(Dispatchers.IO) {
                            do {
                                val bytes = it.readNBytes(READ_BUFFER_SIZE)
                                usableOutputStream.write(bytes)
                                blobOutputStream.write(bytes)
                            } while (it.available() > 0)
                            usableOutputStream.flush()
                            blobOutputStream.flush()
                        }
                    }
                }
                emit(Result.OK)
            }
        }
    }

    @Suppress("MagicNumber")
    private suspend fun shouldRetryStreaming(e: Throwable) = when (e) {
        is LinkNotFoundException -> true

        is ClientAbortException -> {
            false
        }

        is IOException -> {
            logger.warn("Encountered an IO exception", e)
            delay(10_000)
            true
        }

        else -> false
    }

    private fun mapExceptionToResult(e: Throwable): Result {
        return when (e) {
            is ClientAbortException -> {
                logger.debug("Client aborted the stream", e)
                Result.OK
            }

            is FileNotFoundException -> Result.DEAD_LINK
            else -> {
                logger.error("Error encountered while streaming", e)
                Result.ERROR
            }
        }
    }

    enum class Result {
        DEAD_LINK, ERROR, OK
    }

    class LinkNotFoundException : Exception()
}
