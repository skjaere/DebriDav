package io.skjaere.debridav

import com.google.common.io.CountingInputStream
import com.google.common.io.CountingOutputStream
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.cache.DefaultStreamCacher
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.cache.FileChunkCachingService.ByteRangeInfo
import io.skjaere.debridav.cache.StreamCacher
import io.skjaere.debridav.cache.StreamPlanningService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.catalina.connector.ClientAbortException
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue


@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    private val fileChunkCachingService: FileChunkCachingService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val streamPlanningService: StreamPlanningService,
    transactionManager: PlatformTransactionManager,
    prometheusRegistry: PrometheusRegistry
) : StreamCacher by DefaultStreamCacher(
    debridavConfigurationProperties,
    TransactionTemplate(transactionManager),
    fileChunkCachingService
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)
    private val outputGauge = Gauge.builder()
        .name("debridav.output.stream.bitrate")
        .labelNames("provider", "file")
        .labelNames("provider", "file")
        .register(prometheusRegistry)

    private val inputGauge = Gauge.builder()
        .name("debridav.input.stream.bitrate")
        .labelNames("provider", "file")
        .register(prometheusRegistry)

    private val activeOutputStream = ConcurrentLinkedQueue<OutputStreamingContext>()
    private val activeInputStreams = ConcurrentLinkedQueue<InputStreamingContext>()

    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_UNCOMMITTED
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamContents(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ): Result = coroutineScope {
        logger.info("begin streaming ${debridLink.path}")
        try {
            val appliedRange = Range(range?.start ?: 0, range?.finish ?: (debridLink.size!! - 1))
            streamBytes(remotelyCachedEntity, appliedRange, debridLink, outputStream)
            logger.info("done streaming ${debridLink.path}: OK")
            Result.OK
        } catch (_: CancellationException) {
            Result.OK
        } catch (e: Exception) {
            logger.error("An error occurred during streaming ${debridLink.path}", e)
            Result.ERROR
        }
    }

    private suspend fun streamBytes(
        remotelyCachedEntity: RemotelyCachedEntity,
        range: Range,
        debridLink: CachedFile,
        outputStream: OutputStream
    ) = coroutineScope {
        launch {
            sendContent(
                getByteArrays(
                    getSources(
                        streamPlanningService.generatePlan(
                            fileChunkCachingService.getAllCachedChunksForEntity(remotelyCachedEntity),
                            LongRange(range.start, range.finish),
                            debridLink
                        )
                    )
                ),
                outputStream,
                remotelyCachedEntity,
                range
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getSources(streamPlan: StreamPlanningService.StreamPlan)
            : ReceiveChannel<StreamPlanningService.StreamSource> = this.produce(this.coroutineContext, 2) {
        streamPlan.sources.takeWhile {
            try {
                send(it)
                true
            } catch (_: CancellationException) {
                false
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getByteArrays(streamPlan: ReceiveChannel<StreamPlanningService.StreamSource>)
            : ReceiveChannel<ByteArrayContext> = this.produce(this.coroutineContext, 2) {
        streamPlan.consumeEach { sourceContext ->
            when (sourceContext) {
                is StreamPlanningService.StreamSource.Cached -> sendCachedBytes(
                    sourceContext,
                    sourceContext.fileChunk.startByte!!
                )

                is StreamPlanningService.StreamSource.Remote -> sendBytesFromHttp(
                    sourceContext,
                    sourceContext.cachedFile.provider
                )
            }
        }
    }


    private suspend fun ProducerScope<ByteArrayContext>.sendBytesFromHttp(
        source: StreamPlanningService.StreamSource.Remote,
        provider: DebridProvider?
    ) {
        val debridClient = debridClients.first { it.getProvider() == source.cachedFile.provider }
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range,
            source.cachedFile.size!!
        )
        val prepared: HttpStatement = debridClient.prepareStreamUrl(source.cachedFile, range)
        prepared.execute { resp ->
            if (!resp.status.isSuccess()) {
                logger.error(
                    "Got response: ${resp.status.value} from ${source.cachedFile} with body: ${
                        resp.body<String>()
                    }"
                )
                throw LinkNotFoundException()
            }
            resp.bodyAsChannel().toInputStream().use { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream),
                    source.cachedFile.provider!!,
                    source.cachedFile.path!!
                )
                try {
                    activeInputStreams.add(streamingContext)
                    var remaining = byteRangeInfo!!.length()
                    while (remaining > 0) {
                        val size = listOf(remaining, 128384L).min()
                        try {
                            val bytes = streamingContext.inputStream.readNBytes(size.toInt())
                            send(
                                ByteArrayContext(
                                    bytes,
                                    Range(source.range.start, source.range.last),
                                    ByteArraySource.REMOTE,
                                    provider!!,
                                )
                            )
                            remaining -= bytes.size
                        } catch (_: CancellationException) {
                            cancel()
                            break;
                        }
                    }
                    activeInputStreams.remove(streamingContext)
                } catch (e: Exception) {
                    logger.error("An error occurred during streaming", e)
                    activeInputStreams.remove(streamingContext)
                }
            }
        }
    }

    private fun ProducerScope<ByteArrayContext>.sendCachedBytes(
        source: StreamPlanningService.StreamSource.Cached,
        startByte: Long
    ) {
        val fromDbChannel = Channel<ByteArray>()
        readFromDatabase(source, fromDbChannel, startByte)
        //writeToOutputChannel(fromDbChannel, source)

        //coroutineContext.cancelChildren()

        logger.info("sending cached bytes complete. closed transaction.")
    }

    private fun ProducerScope<ByteArrayContext>.writeToOutputChannel(
        fromDbChannel: Channel<ByteArray>,
        source: StreamPlanningService.StreamSource.Cached
    ): Job = launch {
        fromDbChannel.consumeEach { bytes ->
            try {
                send(
                    ByteArrayContext(
                        bytes,
                        Range(source.range.start, source.range.last),
                        ByteArraySource.CACHED
                    )
                )
            } catch (_: CancellationException) {
                fromDbChannel.cancel()
                cancel()
            }
        }
        //cancel()
    }

    private fun ProducerScope<ByteArrayContext>.readFromDatabase(
        source: StreamPlanningService.StreamSource.Cached,
        fromDbChannel: Channel<ByteArray>,
        startByte: Long
    ) {
        val bytes = transactionTemplate.execute {
            val stream = source.fileChunk.blob!!.localContents!!.binaryStream
            if (source.range.start != source.fileChunk.startByte!!) {
                val skipBytes = source.range.start - startByte
                logger.info("skipping $skipBytes bytes")
                stream.skipNBytes(skipBytes)
            }
            val bytes = stream.readAllBytes()
            stream.close()
            bytes
        }
        runBlocking {
            send(
                ByteArrayContext(
                    bytes!!,
                    Range(source.range.start, source.range.last),
                    ByteArraySource.CACHED
                )
            )
        }
        /*launch {
            withContext(Dispatchers.IO) {
                var remaining = source.range.last - source.range.start + 1
                try {
                    transactionTemplate.execute {
                        val inputStream = source.fileChunk.blob!!.localContents!!.binaryStream
                        try {
                            if (source.range.start != source.fileChunk.startByte!!) {
                                val skipBytes = source.range.start - startByte
                                logger.info("skipping $skipBytes bytes")
                                inputStream.skipNBytes(skipBytes)
                            }
                            while (remaining > 0) {
                                val size = listOf(remaining, 32384L).min()
                                try {
                                    val bytes = inputStream.readNBytes(size.toInt())
                                    runBlocking { fromDbChannel.send(bytes) }
                                } catch (_: CancellationException) {
                                    //inputStream.close()
                                    cancel()
                                    break;
                                }

                                remaining -= size
                            }
                        } finally {
                            //inputStream.close()
                        }
                    }
                } catch (e: Exception) {
                    logger.error("something went wrong: $remaining", e)
                }
            }
            fromDbChannel.close()
            logger.info("done reading from cache")
        }*/
    }

    data class ByteArrayContext(
        val byteArray: ByteArray,
        val range: Range,
        val source: ByteArraySource,
        val debridProvider: DebridProvider? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ByteArrayContext

            if (!byteArray.contentEquals(other.byteArray)) return false
            if (range != other.range) return false

            return true
        }

        override fun hashCode(): Int {
            var result = byteArray.contentHashCode()
            result = 31 * result + range.hashCode()
            return result
        }
    }

    enum class ByteArraySource {
        CACHED, REMOTE
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.sendContent(
        byteArrayChannel: ReceiveChannel<ByteArrayContext>,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        requestedRange: Range
    ) {
        var shouldCache =
            requestedRange.length <= debridavConfigurationProperties.chunkCachingRequestedRangeSizeThreshold
        var hasFreshBytes = false
        var bytesToCache = mutableListOf<ByteArray>()
        var bytesSent = 0L
        outputStream.use { usableOutputStream ->
            val gaugeContext = OutputStreamingContext(
                ResettableCountingOutputStream(usableOutputStream),
                DebridProvider.REAL_DEBRID,
                remotelyCachedEntity.name!!
            )
            activeOutputStream.add(gaugeContext)
            byteArrayChannel.consumeEach { context ->
                if (context.source == ByteArraySource.REMOTE) hasFreshBytes = true
                try {
                    if (shouldCache) {
                        if (bytesToCache
                                .sumOf { it.size }
                                .plus(bytesToCache.size) > debridavConfigurationProperties.chunkCachingSizeThreshold
                        ) {
                            shouldCache = false
                            bytesToCache = mutableListOf()
                        } else bytesToCache.add(context.byteArray)
                    }
                    gaugeContext.outputStream.write(context.byteArray)
                    bytesSent += context.byteArray.size
                } catch (_: ClientAbortException) {
                    if (bytesToCache.isNotEmpty() && hasFreshBytes) {
                        cacheBytes(
                            remotelyCachedEntity,
                            Range(
                                context.range.start,
                                context.range.start + bytesToCache.sumOf { it.size }.toLong() - 1
                            ),
                            bytesToCache
                        )
                    }
                    cancel()
                } catch (e: Exception) {
                    logger.error("An error occurred during streaming", e)
                }
            }
            activeOutputStream.remove(gaugeContext)
            logger.info("sent: $bytesSent bytes to client")
            if (bytesToCache.isNotEmpty() && hasFreshBytes) {
                cacheBytes(
                    remotelyCachedEntity,
                    Range(requestedRange.start, bytesToCache.sumOf { it.size }.toLong() + requestedRange.start - 1),
                    bytesToCache
                )
            }
        }
    }

    data class BytesToCache(
        val bytes: ByteArray,
        val startByte: Long,
        var endByte: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as BytesToCache

            if (startByte != other.startByte) return false
            if (endByte != other.endByte) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = startByte.hashCode()
            result = 31 * result + endByte.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    fun cacheBytes(
        remotelyCachedEntity: RemotelyCachedEntity,
        range: Range,
        bytes: List<ByteArray>
    ) {
        val totalSize = bytes.sumOf { it.size }
        val byteArrayToCache = bytes
            .fold(ByteArray(0)) { acc, bytes -> acc.plus(bytes) }
        transactionTemplate.execute {
            fileChunkCachingService.cacheChunk(
                byteArrayToCache,
                remotelyCachedEntity,
                range.start,
                range.finish
            )
        }
        logger.info("done saving chunk: ${range.start}-${range.finish} of $totalSize bytes to cache")
    }


    @Suppress("SwallowedException", "MagicNumber")
    suspend fun streamDebridLink(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ): Result {
        val debridClient = debridClients.first { it.getProvider() == debridLink.provider }
        val byteRangeInfo =
            range?.let {
                fileChunkCachingService.getByteRange(
                    it,
                    remotelyCachedEntity.contents!!.size!!
                )
            }
        val streamingContext = OutputStreamingContext(
            ResettableCountingOutputStream(outputStream),
            debridLink.provider!!,
            remotelyCachedEntity.name!!
        )
        activeOutputStream.add(streamingContext)
        val streamingResult: Result = runWithLockIfNeeded(remotelyCachedEntity.id!!, byteRangeInfo) {
            flow {
                serveCachedContentIfAvailable(
                    byteRangeInfo,
                    debridLink,
                    outputStream,
                    remotelyCachedEntity
                )
                    ?.let {
                        logger.info("served cached content for ${debridLink.path} from ${debridLink.provider} with result $it")
                        emit(it)
                    }
                    ?: run {
                        try {
                            val prepared: HttpStatement = debridClient.prepareStreamUrl(debridLink, range)
                            prepared.execute(
                                tryPipeResponse(
                                    debridLink,
                                    streamingContext.outputStream,
                                    remotelyCachedEntity
                                )
                            )
                        } catch (_: ClientAbortException) {
                            emit(Result.OK)
                        }
                    }
            }.retryWhen { cause, attempt ->
                if (attempt <= 2 && shouldRetryStreaming(cause)) {
                    logger.info("retry attempt $attempt of ${debridLink.path} because $cause")
                    delay(5_000 * attempt)
                    true
                } else false
            }.catch {
                outputStream.close()
                emit(mapExceptionToResult(it))
            }.onEach {
                logger.info("Streaming of {} complete with result {}", debridLink.path!!.split("/").last(), it)
            }.first()
        }
        activeOutputStream.removeStream(streamingContext)
        return streamingResult
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
            logger.info("caching chunk of size: ${FileUtils.byteCountToDisplaySize(byteRangeInfo!!.length())}")
            cacheChunkAndRespond(resp, outputStream, debridLink, byteRangeInfo, remotelyCachedEntity)
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

    private fun getByteRangeInfo(
        resp: HttpResponse,
        fileSize: Long
    ): ByteRangeInfo? {
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

    @Suppress("MagicNumber")
    private suspend fun shouldRetryStreaming(e: Throwable) = when (e) {
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

    @Scheduled(fixedRate = 5_000)
    fun recordMetrics() {
        registerBitrate()
    }

    fun registerBitrate() {
        activeOutputStream.forEach {
            outputGauge
                .labelValues(it.provider.toString(), it.file)
                .set(it.outputStream.countAndReset().toDouble())
        }
        activeInputStreams.forEach {
            inputGauge
                .labelValues(it.provider.toString(), it.file)
                .set(it.inputStream.countAndReset().toDouble())
        }
    }

    fun ConcurrentLinkedQueue<OutputStreamingContext>.removeStream(ctx: OutputStreamingContext) {
        outputGauge.remove(ctx.provider.toString(), ctx.file)
        this.remove(ctx)
    }

    enum class Result {
        DEAD_LINK, ERROR, OK
    }

    class LinkNotFoundException : Exception()
    data class OutputStreamingContext(
        val outputStream: ResettableCountingOutputStream,
        val provider: DebridProvider,
        val file: String
    )

    data class InputStreamingContext(
        val inputStream: ResettableCountingInputStream,
        val provider: DebridProvider,
        val file: String
    )
}

class ResettableCountingOutputStream(private val countingOutputStream: CountingOutputStream) : OutputStream() {
    private var bytesTransferred: Long = 0

    constructor(outputStream: OutputStream) : this(CountingOutputStream(outputStream))

    override fun write(b: Int) {
        countingOutputStream.write(b)
    }

    fun countAndReset(): Long {
        val count = countingOutputStream.count
        val transferredSinceLastCheck = count - bytesTransferred
        bytesTransferred = count
        return transferredSinceLastCheck
    }
}

class ResettableCountingInputStream(private val countingInputStream: CountingInputStream) : InputStream() {
    private var bytesTransferred: Long = 0

    constructor(inputStream: InputStream) : this(CountingInputStream(inputStream))

    fun countAndReset(): Long {
        val count = countingInputStream.count
        val transferredSinceLastCheck = count - bytesTransferred
        bytesTransferred = count
        return transferredSinceLastCheck
    }

    override fun read(): Int {
        return countingInputStream.read()
    }
}

