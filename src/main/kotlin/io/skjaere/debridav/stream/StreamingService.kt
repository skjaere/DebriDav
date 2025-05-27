package io.skjaere.debridav.stream

import io.ktor.client.call.body
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.cache.BytesToCache
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.cache.StreamPlanningService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.IOException
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit


private const val DEFAULT_BUFFER_SIZE = 65536L //64kb
private const val STREAMING_METRICS_POLLING_RATE_S = 5L //5 seconds
private const val BYTE_CHANNEL_CAPACITY = 2000
private const val STREAM_EMPTY_RESPONSE_RETRIES = 3
private const val STREAM_EMPTY_RESPONSE_RETRY_DELAY_MS = 150L

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    private val fileChunkCachingService: FileChunkCachingService,
    private val debridavConfigProperties: DebridavConfigurationProperties,
    private val streamPlanningService: StreamPlanningService,
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


    @OptIn(ExperimentalCoroutinesApi::class)
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
            val streamingPlan = streamPlanningService.generatePlan(
                fileChunkCachingService.getAllCachedChunksForEntity(remotelyCachedEntity),
                LongRange(range.start, range.finish),
                debridLink
            )
            val sources = getSources(streamingPlan)
            val byteArrays = getByteArrays(sources)
            sendContent(byteArrays, outputStream, remotelyCachedEntity)
        }
    }

    fun ConcurrentLinkedQueue<OutputStreamingContext>.removeStream(ctx: OutputStreamingContext) {
        outputGauge.remove(ctx.file)
        this.remove(ctx)
    }

    fun ConcurrentLinkedQueue<InputStreamingContext>.removeStream(ctx: InputStreamingContext) {
        logger.info("removing context $ctx")
        inputGauge.remove(ctx.provider.toString(), ctx.file)
        if (this.contains(ctx)) {
            this.remove(ctx)
        } else {
            logger.info("context $ctx not found in queue")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getSources(
        streamPlan: StreamPlanningService.StreamPlan
    ): ReceiveChannel<StreamPlanningService.StreamSource> = this.produce(this.coroutineContext, 2) {
        streamPlan.sources.forEach {
            send(it)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getByteArrays(
        streamPlan: ReceiveChannel<StreamPlanningService.StreamSource>
    ): ReceiveChannel<ByteArrayContext> = this.produce(this.coroutineContext, BYTE_CHANNEL_CAPACITY) {
        streamPlan.consumeEach { sourceContext ->
            when (sourceContext) {
                is StreamPlanningService.StreamSource.Cached ->
                    sendCachedBytes(sourceContext)

                is StreamPlanningService.StreamSource.Remote ->
                    sendBytesFromHttpStreamWithKtor(sourceContext)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<ByteArrayContext>.sendBytesFromHttpStreamWithKtor(
        source: StreamPlanningService.StreamSource.Remote
    ) {
        val debridClient = debridClients.first { it.getProvider() == source.cachedFile.provider }
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range, source.cachedFile.size!!
        )
        val started = Instant.now()
        debridClient.prepareStreamUrl(source.cachedFile, range).execute { response ->

            val byteReadChannel = response.body<ByteReadChannel>()
            byteReadChannel.toInputStream().use { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream),
                    source.cachedFile.provider!!,
                    source.cachedFile.path!!
                )
                activeInputStreams.add(streamingContext)
                try {
                    withContext(Dispatchers.IO) {
                        pipeHttpInputStreamToOutputChannel(
                            streamingContext, byteRangeInfo, source, started
                        )
                    }

                } catch (e: CancellationException) {
                    close(e)
                    throw e
                } catch (e: Exception) {
                    logger.error("An error occurred during reading from stream", e)
                    throw ReadFromHttpStreamException("An error occurred during reading from stream", e)
                } finally {
                    response.cancel()
                    activeInputStreams.removeStream(streamingContext)
                }
            }
        }
    }

    /*@OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    private fun ProducerScope<ByteArrayContext>.sendBytesFromHttpStreamWithApache(
        source: StreamPlanningService.StreamSource.Remote
    ) {
        val debridClient = debridClients.first { it.getProvider() == source.cachedFile.provider }
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range, source.cachedFile.size!!
        )
        val httpStreamingParams: StreamHttpParams = debridClient.getStreamParams(source.cachedFile, range)
        val request = generateRequestFromSource(source, httpStreamingParams)

        val started = Instant.now()
        HttpClients.createDefault().let { httpClient ->
            httpClient.executeOpen(null, request, null).entity.content.let { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream),
                    source.cachedFile.provider!!,
                    source.cachedFile.path!!
                )
                activeInputStreams.add(streamingContext)
                try {
                    runBlocking(Dispatchers.IO) {
                        try {
                            pipeHttpInputStreamToOutputChannel(
                                streamingContext, byteRangeInfo, source, started
                            )
                        } finally {
                            httpClient.close()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("An error occurred during reading from stream", e)
                    throw ReadFromHttpStreamException("An error occurred during reading from stream", e)
                } finally {
                    activeInputStreams.removeStream(streamingContext)
                }
            }
        }
    }*/

    /*private fun generateRequestFromSource(
        source: StreamPlanningService.StreamSource.Remote, httpStreamingParams: StreamHttpParams
    ): HttpGet {
        val request = HttpGet(source.cachedFile.link)
        request.config = RequestConfig.custom()
            .setConnectionRequestTimeout(
                Timeout.ofMilliseconds(httpStreamingParams.timeouts.connectTimeoutMillis)
            )
            .setResponseTimeout(
                Timeout.ofMilliseconds(httpStreamingParams.timeouts.requestTimeoutMillis)
            )
            .build()
        httpStreamingParams.headers.forEach { (key, value) -> request.addHeader(key, value) }
        return request
    }*/

    private suspend fun ProducerScope<ByteArrayContext>.pipeHttpInputStreamToOutputChannel(
        streamingContext: InputStreamingContext,
        byteRangeInfo: FileChunkCachingService.ByteRangeInfo?,
        source: StreamPlanningService.StreamSource.Remote,
        started: Instant
    ) {
        var hasReadFirstByte = false;
        var timeToFirstByte: Double
        var remaining = byteRangeInfo!!.length()
        var firstByte = source.range.start
        var readBytes = 0L
        var tries = 0
        while (remaining > 0) {
            val size = listOf(remaining, DEFAULT_BUFFER_SIZE).min()

            val bytes = streamingContext.inputStream.readNBytes(size.toInt())
            readBytes += bytes.size
            if (!hasReadFirstByte) {
                hasReadFirstByte = true
                timeToFirstByte = Duration.between(started, Instant.now()).toMillis().toDouble()
                timeToFirstByteHistogram
                    .labelValues(source.cachedFile.provider.toString())
                    .observe(timeToFirstByte)
                logger.info("time to first byte: $timeToFirstByte")
            }
            if (bytes.isEmpty()) {
                logger.info("No bytes read from stream")
                logger.info("remaining: $remaining")
                logger.info("readBytes: $readBytes")
                logger.info("stream available: ${streamingContext.inputStream.available()}")
                if (tries > STREAM_EMPTY_RESPONSE_RETRIES) {
                    throw IOException("no more bytes to read from stream after $tries tries")
                } else {
                    tries++
                    delay(STREAM_EMPTY_RESPONSE_RETRY_DELAY_MS)
                }
            } else {
                send(
                    ByteArrayContext(
                        bytes,
                        Range(firstByte, firstByte + bytes.size - 1),
                        ByteArraySource.REMOTE
                    )
                )
                firstByte = firstByte + bytes.size
                remaining -= bytes.size
            }
        }
    }

    private suspend fun ProducerScope<ByteArrayContext>.sendCachedBytes(
        source: StreamPlanningService.StreamSource.Cached
    ) {
        val bytes = fileChunkCachingService.getBytesFromChunk(
            source.fileChunk, source.range
        )

        this.send(
            ByteArrayContext(
                bytes,
                Range(source.range.start, source.range.last),
                ByteArraySource.CACHED
            )
        )
        logger.info("sending cached bytes complete. closed transaction.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    suspend fun CoroutineScope.sendContent(
        byteArrayChannel: ReceiveChannel<ByteArrayContext>,
        outputStream: OutputStream,
        remotelyCachedEntity: RemotelyCachedEntity
    ) {
        var shouldCache = true
        var bytesToCache = mutableListOf<BytesToCache>()
        var bytesToCacheSize = 0L
        var bytesSent = 0L
        val gaugeContext = OutputStreamingContext(
            ResettableCountingOutputStream(outputStream), remotelyCachedEntity.name!!
        )
        activeOutputStream.add(gaugeContext)
        try {
            byteArrayChannel.consumeEach { context ->
                if (context.source == ByteArraySource.REMOTE) {
                    if (shouldCache) {
                        bytesToCacheSize += context.byteArray.size
                        if (bytesToCacheSize > debridavConfigProperties.chunkCachingSizeThreshold) {
                            shouldCache = false
                            bytesToCache = mutableListOf()
                        } else bytesToCache.add(
                            BytesToCache(
                                context.byteArray, context.range.start, context.range.finish
                            )
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    gaugeContext.outputStream.write(context.byteArray)
                }
                bytesSent += context.byteArray.size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ClientAbortException) {
            cancel()
        } catch (e: Exception) {
            logger.error("An error occurred during streaming", e)
            throw StreamToClientException("An error occurred during streaming", e)
        } finally {
            gaugeContext.outputStream.close()
            activeOutputStream.removeStream(gaugeContext)
            if (bytesToCache.isNotEmpty()) {
                fileChunkCachingService.cacheBytes(remotelyCachedEntity, bytesToCache)
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

