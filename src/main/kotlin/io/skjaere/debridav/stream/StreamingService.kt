package io.skjaere.debridav.stream

import io.ktor.client.call.body
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.milton.http.Range
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.EOFException
import kotlinx.io.IOException
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


private const val DEFAULT_BUFFER_SIZE = 256 * 1024 //256kb
private const val READ_AHEAD_CHUNKS = 200 // 50Mb
private const val STREAMING_METRICS_POLLING_RATE_S = 5L //5 seconds
private const val STALL_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)
    private val outputGauge = MultiGauge.builder("debridav.output.stream.bitrate")
        .register(meterRegistry)
    private val inputGauge = MultiGauge.builder("debridav.input.stream.bitrate")
        .register(meterRegistry)
    private val activeOutputStream = ConcurrentLinkedQueue<OutputStreamingContext>()
    private val activeInputStreams = ConcurrentLinkedQueue<InputStreamingContext>()


    suspend fun streamContents(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        client: String,
    ): StreamResult = coroutineScope {
        val result = try {
            runStreamingPipeline(debridLink, range, outputStream, remotelyCachedEntity, client)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            mapToStreamResult(e, debridLink)
        } finally {
            this.coroutineContext.cancelChildren()
        }
        logger.info("done streaming ${debridLink.path}: $result")
        result
    }

    @Suppress("LongParameterList")
    private suspend fun runStreamingPipeline(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        client: String,
    ): StreamResult = coroutineScope {
        val appliedRange = Range(range?.start ?: 0, range?.finish ?: (debridLink.size!! - 1))
        val inputCounter = ByteCounter()
        val outputCounter = ByteCounter()
        val inputCtx = InputStreamingContext(inputCounter, debridLink.provider!!, debridLink.path!!, client)
        val outputCtx = OutputStreamingContext(outputCounter, remotelyCachedEntity.name!!, client)
        activeInputStreams.add(inputCtx)
        activeOutputStream.add(outputCtx)
        val started = Instant.now()
        var ttfbRecorded = false
        val sawActivity = AtomicBoolean(true)
        val watchdog = launch {
            while (isActive) {
                delay(STALL_TIMEOUT_MS)
                if (!sawActivity.getAndSet(false)) {
                    logger.warn(
                        "Stream '{}' stalled (>{}ms without bytes); closing output to unblock write",
                        debridLink.path, STALL_TIMEOUT_MS
                    )
                    runCatching { outputStream.close() }
                    break
                }
            }
        }
        try {
            sendBytesFromHttpStream(debridLink, appliedRange, outputStream) { bytes ->
                if (!ttfbRecorded) {
                    ttfbRecorded = true
                    Timer.builder("debridav.streaming.time.to.first.byte")
                        .description("Time duration between sending request and receiving first byte")
                        .tag("provider", debridLink.provider.toString())
                        .tag("client", client)
                        .register(meterRegistry)
                        .record(Duration.between(started, Instant.now()))
                }
                inputCounter.add(bytes.toLong())
                outputCounter.add(bytes.toLong())
                sawActivity.set(true)
            }
        } finally {
            watchdog.cancel()
            activeOutputStream.removeStream(outputCtx)
            activeInputStreams.removeStream(inputCtx)
        }
        StreamResult.OK
    }

    private fun mapToStreamResult(e: Exception, debridLink: CachedFile): StreamResult = when (e) {
        is LinkNotFoundException -> StreamResult.DEAD_LINK
        is DebridProviderException -> StreamResult.PROVIDER_ERROR
        is StreamToClientException -> StreamResult.IO_ERROR
        is ReadFromHttpStreamException -> StreamResult.IO_ERROR
        is ClientErrorException -> StreamResult.CLIENT_ERROR
        is ClientAbortException -> StreamResult.OK
        is AsyncRequestNotUsableException -> StreamResult.OK
        is IOException -> {
            logger.error("IOError occurred during streaming", e)
            StreamResult.IO_ERROR
        }
        else -> {
            logger.error("An error occurred during streaming ${debridLink.path}", e)
            StreamResult.UNKNOWN_ERROR
        }
    }


    // MultiGauge row removal happens in recordMetrics() via overwrite=true,
    // so these helpers just update the backing queues.
    private fun ConcurrentLinkedQueue<OutputStreamingContext>.removeStream(ctx: OutputStreamingContext) {
        this.remove(ctx)
    }

    private fun ConcurrentLinkedQueue<InputStreamingContext>.removeStream(ctx: InputStreamingContext) {
        if (this.contains(ctx)) {
            this.remove(ctx)
        } else {
            logger.warn("context $ctx not found in queue")
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun sendBytesFromHttpStream(
        debridLink: CachedFile,
        range: Range,
        outputStream: OutputStream,
        onBytesTransferred: (Int) -> Unit = {}
    ) {
        val debridClient = debridClients.first { it.getProvider() == debridLink.provider }
        val length = (range.finish - range.start) + 1
        debridClient.prepareStreamUrl(debridLink, range).execute { response ->
            val upstreamByteReadChannel = response.body<ByteReadChannel>()
            try {
                coroutineScope {
                    val bufferPool = createByteArrayPool(READ_AHEAD_CHUNKS + 1, DEFAULT_BUFFER_SIZE)
                    val chunkChannel = produceChunks(length, bufferPool, upstreamByteReadChannel)
                    chunkChannel.consumeEach { (buffer, bytesRead) ->
                        outputStream.write(buffer, 0, bytesRead)
                        onBytesTransferred(bytesRead)
                        bufferPool.send(buffer)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ClientAbortException) {
            } catch (_: AsyncRequestNotUsableException) {
            } catch (e: IOException) {
                logger.warn("IO error reading from upstream HTTP stream during streaming", e)
                throw ReadFromHttpStreamException("IO error reading from upstream HTTP stream", e)
            } catch (e: Exception) {
                logger.error("An error occurred during streaming", e)
                throw StreamToClientException("An error occurred during streaming", e)
            } finally {
                upstreamByteReadChannel.cancel(null)
                outputStream.close()
            }
        }
    }

    private suspend fun createByteArrayPool(size: Int, bufferSize: Int): Channel<ByteArray> {
        val bufferPool = Channel<ByteArray>(size)
        repeat(size) {
            bufferPool.send(ByteArray(bufferSize))
        }
        return bufferPool
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.produceChunks(
        length: Long,
        bufferPool: Channel<ByteArray>,
        httpResponseChannel: ByteReadChannel,
        readAheadBufferSize: Int = READ_AHEAD_CHUNKS,
    ): ReceiveChannel<Pair<ByteArray, Int>> = produce(capacity = readAheadBufferSize) {
        var remaining = length
        while (remaining > 0) {
            val buffer = bufferPool.receive()
            val size = minOf(remaining, DEFAULT_BUFFER_SIZE.toLong()).toInt()
            val bytesRead = httpResponseChannel.readAvailable(buffer, 0, size)
            if (bytesRead == -1) throw EOFException()
            remaining -= bytesRead
            send(buffer to bytesRead)
        }
    }

    @Scheduled(fixedRate = STREAMING_METRICS_POLLING_RATE_S, timeUnit = TimeUnit.SECONDS)
    fun recordMetrics() {
        // overwrite=true drops rows for streams no longer in the queue so
        // gauges for ended streams disappear from scrape output.
        outputGauge.register(
            activeOutputStream.map { ctx ->
                MultiGauge.Row.of(
                    Tags.of("file", ctx.file, "client", ctx.client),
                    ctx.counter.countAndReset().toDouble().div(STREAMING_METRICS_POLLING_RATE_S),
                )
            },
            true,
        )
        inputGauge.register(
            activeInputStreams.map { ctx ->
                MultiGauge.Row.of(
                    Tags.of(
                        "provider", ctx.provider.toString(),
                        "file", ctx.file,
                        "client", ctx.client,
                    ),
                    ctx.counter.countAndReset().toDouble().div(STREAMING_METRICS_POLLING_RATE_S),
                )
            },
            true,
        )
    }
}
