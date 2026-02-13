package io.skjaere.debridav.stream

import io.ktor.client.call.body
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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


private const val DEFAULT_BUFFER_SIZE = 65536L //64kb
private const val STREAMING_METRICS_POLLING_RATE_S = 5L //5 seconds

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    prometheusRegistry: PrometheusRegistry
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)
    private val outputGauge =
        Gauge.builder().name("debridav.output.stream.bitrate").labelNames("provider", "file").labelNames("file")
            .register(prometheusRegistry)
    private val inputGauge = Gauge.builder().name("debridav.input.stream.bitrate").labelNames("provider", "file")
        .register(prometheusRegistry)
    private val timeToFirstByteHistogram =
        Histogram.builder().help("Time duration between sending request and receiving first byte")
            .name("debridav.streaming.time.to.first.byte").labelNames("provider").register(prometheusRegistry)
    private val activeOutputStream = ConcurrentLinkedQueue<OutputStreamingContext>()
    private val activeInputStreams = ConcurrentLinkedQueue<InputStreamingContext>()


    @Suppress("TooGenericExceptionCaught")
    suspend fun streamContents(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
    ): StreamResult = coroutineScope {
        val result = try {
            val appliedRange = Range(range?.start ?: 0, range?.finish ?: (debridLink.size!! - 1))
            streamBytes(remotelyCachedEntity, appliedRange, debridLink, outputStream)
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


    private suspend fun streamBytes(
        remotelyCachedEntity: RemotelyCachedEntity, range: Range, debridLink: CachedFile, outputStream: OutputStream
    ) = coroutineScope {
        launch {
            sendBytesFromHttpStream(debridLink, range, outputStream, remotelyCachedEntity)
        }
    }

    fun ConcurrentLinkedQueue<OutputStreamingContext>.removeStream(ctx: OutputStreamingContext) {
        outputGauge.remove(ctx.file)
        this.remove(ctx)
    }

    fun ConcurrentLinkedQueue<InputStreamingContext>.removeStream(ctx: InputStreamingContext) {
        inputGauge.remove(ctx.provider.toString(), ctx.file)
        if (this.contains(ctx)) {
            this.remove(ctx)
        } else {
            logger.warn("context $ctx not found in queue")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendBytesFromHttpStream(
        debridLink: CachedFile,
        range: Range,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ) {
        val debridClient = debridClients.first { it.getProvider() == debridLink.provider }
        val length = (range.finish - range.start) + 1
        val started = Instant.now()
        val gaugeContext = OutputStreamingContext(
            ResettableCountingOutputStream(outputStream), remotelyCachedEntity.name!!
        )
        activeOutputStream.add(gaugeContext)
        debridClient.prepareStreamUrl(debridLink, range).execute { response ->
            response.body<ByteReadChannel>().toInputStream().use { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream), debridLink.provider!!, debridLink.path!!
                )
                activeInputStreams.add(streamingContext)
                try {
                    withContext(Dispatchers.IO) {
                        var hasReadFirstByte = false
                        var remaining = length
                        while (remaining > 0) {
                            val size = listOf(remaining, DEFAULT_BUFFER_SIZE).min()
                            val bytes = streamingContext.inputStream.readNBytes(size.toInt())
                            if (!hasReadFirstByte) {
                                hasReadFirstByte = true
                                val timeToFirstByte =
                                    Duration.between(started, Instant.now()).toMillis().toDouble()
                                timeToFirstByteHistogram.labelValues(debridLink.provider.toString())
                                    .observe(timeToFirstByte)
                            }
                            if (bytes.isNotEmpty()) {
                                gaugeContext.outputStream.write(bytes)
                                remaining -= bytes.size
                            } else {
                                throw EOFException()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ClientAbortException) {
                    cancel()
                } catch (e: Exception) {
                    logger.error("An error occurred during streaming", e)
                    throw StreamToClientException("An error occurred during streaming", e)
                } finally {
                    response.cancel()
                    gaugeContext.outputStream.close()
                    activeOutputStream.removeStream(gaugeContext)
                    activeInputStreams.removeStream(streamingContext)
                }
            }
        }
    }

    @Scheduled(fixedRate = STREAMING_METRICS_POLLING_RATE_S, timeUnit = TimeUnit.SECONDS)
    fun recordMetrics() {
        activeOutputStream.forEach {
            outputGauge.labelValues(it.file)
                .set(it.outputStream.countAndReset().toDouble().div(STREAMING_METRICS_POLLING_RATE_S))
        }
        activeInputStreams.forEach {
            inputGauge.labelValues(it.provider.toString(), it.file)
                .set(it.inputStream.countAndReset().toDouble().div(STREAMING_METRICS_POLLING_RATE_S))
        }
    }
}
