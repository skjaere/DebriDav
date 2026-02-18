package io.skjaere.debridav.stream

import io.ktor.client.call.body
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.EOFException
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit


private const val DEFAULT_BUFFER_SIZE = 256 * 1024L //256kb
private const val READ_AHEAD_CHUNKS = 2
private const val STREAMING_METRICS_POLLING_RATE_S = 5L //5 seconds

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    prometheusRegistry: PrometheusRegistry
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)
    private val outputGauge =
        Gauge.builder().name("debridav.output.stream.bitrate").labelNames("file", "client")
            .register(prometheusRegistry)
    private val inputGauge = Gauge
        .builder()
        .name("debridav.input.stream.bitrate")
        .labelNames("provider", "file", "client")
        .register(prometheusRegistry)
    private val timeToFirstByteHistogram =
        Histogram.builder().help("Time duration between sending request and receiving first byte")
            .name("debridav.streaming.time.to.first.byte").labelNames("provider", "client").register(prometheusRegistry)
    private val activeOutputStream = ConcurrentLinkedQueue<OutputStreamingContext>()
    private val activeInputStreams = ConcurrentLinkedQueue<InputStreamingContext>()


    @Suppress("TooGenericExceptionCaught")
    suspend fun streamContents(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        client: String,
    ): StreamResult = coroutineScope {
        val result = try {
            val appliedRange = Range(range?.start ?: 0, range?.finish ?: (debridLink.size!! - 1))
            val inputCounter = ByteCounter()
            val outputCounter = ByteCounter()
            val inputCtx = InputStreamingContext(inputCounter, debridLink.provider!!, debridLink.path!!, client)
            val outputCtx = OutputStreamingContext(outputCounter, remotelyCachedEntity.name!!, client)
            activeInputStreams.add(inputCtx)
            activeOutputStream.add(outputCtx)
            val started = Instant.now()
            var ttfbRecorded = false
            try {
                sendBytesFromHttpStream(debridLink, appliedRange, outputStream) { bytes ->
                    if (!ttfbRecorded) {
                        ttfbRecorded = true
                        timeToFirstByteHistogram.labelValues(debridLink.provider.toString(), client)
                            .observe(Duration.between(started, Instant.now()).toMillis().toDouble())
                    }
                    inputCounter.add(bytes.toLong())
                    outputCounter.add(bytes.toLong())
                }
            } finally {
                activeOutputStream.removeStream(outputCtx)
                activeInputStreams.removeStream(inputCtx)
            }
            StreamResult.OK
        } catch (_: LinkNotFoundException) {
            StreamResult.DEAD_LINK
        } catch (_: DebridProviderException) {
            StreamResult.PROVIDER_ERROR
        } catch (_: StreamToClientException) {
            StreamResult.IO_ERROR
        } catch (_: ReadFromHttpStreamException) {
            StreamResult.IO_ERROR
        } catch (_: ClientErrorException) {
            StreamResult.CLIENT_ERROR
        } catch (_: ClientAbortException) {
            StreamResult.OK
        } catch (e: kotlinx.io.IOException) {
            logger.error("IOError occurred during streaming", e)
            StreamResult.IO_ERROR
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("An error occurred during streaming ${debridLink.path}", e)
            StreamResult.UNKNOWN_ERROR
        } finally {
            this.coroutineContext.cancelChildren()
        }
        logger.info("done streaming ${debridLink.path}: $result")
        result
    }


    fun ConcurrentLinkedQueue<OutputStreamingContext>.removeStream(ctx: OutputStreamingContext) {
        outputGauge.remove(ctx.file, ctx.client)
        this.remove(ctx)
    }

    fun ConcurrentLinkedQueue<InputStreamingContext>.removeStream(ctx: InputStreamingContext) {
        inputGauge.remove(ctx.provider.toString(), ctx.file, ctx.client)
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
            val channel = response.body<ByteReadChannel>()
            try {
                coroutineScope {
                    val chunks = produce(capacity = READ_AHEAD_CHUNKS) {
                        var remaining = length
                        while (remaining > 0) {
                            val buffer = ByteArray(minOf(remaining, DEFAULT_BUFFER_SIZE).toInt())
                            val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                            if (bytesRead == -1) throw EOFException()
                            remaining -= bytesRead
                            send(buffer to bytesRead)
                        }
                    }

                    withContext(Dispatchers.IO) {
                        chunks.consumeEach { (buffer, bytesRead) ->
                            outputStream.write(buffer, 0, bytesRead)
                            onBytesTransferred(bytesRead)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ClientAbortException) {

            } catch (e: Exception) {
                logger.error("An error occurred during streaming", e)
                throw StreamToClientException("An error occurred during streaming", e)
            } finally {
                channel.cancel(null)
                outputStream.close()
            }
        }
    }

    @Scheduled(fixedRate = STREAMING_METRICS_POLLING_RATE_S, timeUnit = TimeUnit.SECONDS)
    fun recordMetrics() {
        activeOutputStream.forEach {
            outputGauge.labelValues(it.file, it.client)
                .set(it.counter.countAndReset().toDouble().div(STREAMING_METRICS_POLLING_RATE_S))
        }
        activeInputStreams.forEach {
            inputGauge.labelValues(it.provider.toString(), it.file, it.client)
                .set(it.counter.countAndReset().toDouble().div(STREAMING_METRICS_POLLING_RATE_S))
        }
    }
}
