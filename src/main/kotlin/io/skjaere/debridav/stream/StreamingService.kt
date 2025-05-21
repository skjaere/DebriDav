package io.skjaere.debridav.stream

import io.ktor.client.call.body
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.cache.StreamPlanningService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.debrid.client.StreamHttpParams
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.catalina.connector.ClientAbortException
import org.apache.hc.client5.http.fluent.Request
import org.apache.hc.core5.util.Timeout
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue


private const val DEFAULT_BUFFER_SIZE = 65536L //64kb
private const val NOT_FOUND = 404

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    private val fileChunkCachingService: FileChunkCachingService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val streamPlanningService: StreamPlanningService,
    prometheusRegistry: PrometheusRegistry
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)
    private val outputGauge = Gauge.builder().name("debridav.output.stream.bitrate")
        .labelNames("provider", "file")
        .labelNames("file").register(prometheusRegistry)
    private val inputGauge = Gauge.builder().name("debridav.input.stream.bitrate")
        .labelNames("provider", "file")
        .register(prometheusRegistry)
    private val timeToFirstByteHistogram = Histogram.builder()
        .help("Time duration between sending request and receiving first byte")
        .name("debridav.streaming.time.to.first.byte")
        .labelNames("provider")
        .register(prometheusRegistry)
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
        logger.info("begin streaming ${debridLink.path}")
        val result = try {
            val appliedRange = Range(range?.start ?: 0, range?.finish ?: (debridLink.size!! - 1))
            streamBytes(remotelyCachedEntity, appliedRange, debridLink, outputStream)
            StreamResult.OK
        } catch (_: LinkNotFoundException) {
            StreamResult.DEAD_LINK
        } catch (_: ClientAbortException) {
            StreamResult.OK
        } catch (_: DebridProviderException) {
            StreamResult.PROVIDER_ERROR
        } catch (_: ClientErrorException) {
            StreamResult.CLIENT_ERROR
        } catch (e: IOException) {
            
            logger.error("IOError occurred during streaming", e)
            StreamResult.IO_ERROR
        } catch (e: Exception) {
            logger.error("An error occurred during streaming ${debridLink.path}", e)
            StreamResult.UNKNOWN_ERROR
        }
        logger.info("done streaming ${debridLink.path}: $result")
        result
    }


    private suspend fun streamBytes(
        remotelyCachedEntity: RemotelyCachedEntity, range: Range, debridLink: CachedFile, outputStream: OutputStream
    ) = coroutineScope {
        launch {
            sendContent(
                this.coroutineContext,
                getByteArrays(
                    this.coroutineContext,
                    getSources(
                        streamPlanningService.generatePlan(
                            fileChunkCachingService.getAllCachedChunksForEntity(remotelyCachedEntity),
                            LongRange(range.start, range.finish),
                            debridLink
                        )
                    )
                ), outputStream, remotelyCachedEntity, range
            )
        }
    }

    fun ConcurrentLinkedQueue<OutputStreamingContext>.removeStream(ctx: OutputStreamingContext) {
        outputGauge.remove(ctx.file)
        this.remove(ctx)
    }

    fun ConcurrentLinkedQueue<InputStreamingContext>.removeStream(ctx: InputStreamingContext) {
        inputGauge.remove(ctx.provider.toString(), ctx.file)
        this.remove(ctx)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getSources(
        streamPlan: StreamPlanningService.StreamPlan
    ): ReceiveChannel<StreamPlanningService.StreamSource> =
        this.produce(this.coroutineContext, 2) {
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
    suspend fun CoroutineScope.getByteArrays(
        parentContext: CoroutineContext,
        streamPlan: ReceiveChannel<StreamPlanningService.StreamSource>
    ): ReceiveChannel<ByteArrayContext> =
        this.produce(this.coroutineContext, 2) {
            streamPlan.consumeEach { sourceContext ->
                when (sourceContext) {
                    is StreamPlanningService.StreamSource.Cached -> runBlocking {
                        sendCachedBytes(
                            sourceContext, sourceContext.fileChunk.startByte!!,
                        )
                    }

                    is StreamPlanningService.StreamSource.Remote -> sendBytesFromHttpStream(
                        sourceContext,
                        parentContext
                    )
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<ByteArrayContext>.sendBytesFromHttpWithKtor(
        source: StreamPlanningService.StreamSource.Remote
    ) {
        val debridClient = debridClients.first { it.getProvider() == source.cachedFile.provider }
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range, source.cachedFile.size!!
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
            val started = Instant.now()
            resp.bodyAsChannel().toInputStream().use { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream), source.cachedFile.provider!!, source.cachedFile.path!!
                )
                try {
                    runBlocking {
                        withContext(Dispatchers.IO) {
                            pipeInputStreamToOutputChannel(streamingContext, byteRangeInfo, source, started)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("An error occurred during streaming", e)
                    activeInputStreams.remove(streamingContext)
                }
            }
        }
    }

    private fun ProducerScope<ByteArrayContext>.sendBytesFromHttpStream(
        source: StreamPlanningService.StreamSource.Remote,
        parentContext: CoroutineContext
    ) {
        val debridClient = debridClients.first { it.getProvider() == source.cachedFile.provider }
        val range = Range(source.range.start, source.range.last)
        val byteRangeInfo = fileChunkCachingService.getByteRange(
            range, source.cachedFile.size!!
        )
        val httpStreamingParams: StreamHttpParams = debridClient.getStreamParams(source.cachedFile, range)
        val request = Request
            .get(source.cachedFile.link)
        httpStreamingParams.headers.forEach { (key, value) -> request.addHeader(key, value) }
        request.connectTimeout(Timeout.ofMilliseconds(httpStreamingParams.timeouts.connectTimeoutMillis))
        request.responseTimeout(Timeout.ofMilliseconds(httpStreamingParams.timeouts.requestTimeoutMillis))

        val started = Instant.now()
        request.execute().handleResponse { response ->
            response.entity.content.use { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream),
                    source.cachedFile.provider!!,
                    source.cachedFile.path!!
                )
                activeInputStreams.add(streamingContext)
                try {
                    runBlocking {
                        withContext(Dispatchers.IO) {
                            pipeInputStreamToOutputChannel(
                                streamingContext,
                                byteRangeInfo,
                                source,
                                started
                            )
                        }

                    }
                } catch (_: CancellationException) {
                    logger.info("cancelled")
                } catch (e: Exception) {
                    logger.error("An error occurred during streaming", e)
                    logger.info("Cancelling job 5")
                    parentContext.cancel()
                } finally {
                    activeInputStreams.removeStream(streamingContext)
                }
            }
        }
    }

    private suspend fun ProducerScope<ByteArrayContext>.pipeInputStreamToOutputChannel(
        streamingContext: InputStreamingContext,
        byteRangeInfo: FileChunkCachingService.ByteRangeInfo?,
        source: StreamPlanningService.StreamSource.Remote,
        started: Instant
    ) {
        var hasReadFirstByte = false;
        var timeToFirstByte: Double
        var remaining = byteRangeInfo!!.length()
        while (remaining > 0) {
            val size = listOf(remaining, DEFAULT_BUFFER_SIZE).min()

            val bytes = streamingContext.inputStream.readNBytes(size.toInt())
            if (!hasReadFirstByte) {
                hasReadFirstByte = true
                timeToFirstByte = Duration.between(started, Instant.now()).toMillis().toDouble()
                timeToFirstByteHistogram
                    .labelValues(source.cachedFile.provider.toString())
                    .observe(timeToFirstByte)
            }
            send(
                ByteArrayContext(
                    bytes, Range(source.range.start, source.range.last), ByteArraySource.REMOTE
                )
            )
            remaining -= bytes.size
        }
    }

    private fun ProducerScope<ByteArrayContext>.sendCachedBytes(
        source: StreamPlanningService.StreamSource.Cached,
        startByte: Long
    ): Boolean {
        fileChunkCachingService.getBytesFromChunk(
            source.fileChunk, source.range, startByte
        ).let { bytes ->
            runBlocking {
                send(
                    ByteArrayContext(
                        bytes, Range(source.range.start, source.range.last), ByteArraySource.CACHED
                    )
                )
            }
        }
        logger.info("sending cached bytes complete. closed transaction.")
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    suspend fun CoroutineScope.sendContent(
        parentContext: CoroutineContext,
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
        val gaugeContext = OutputStreamingContext(
            ResettableCountingOutputStream(outputStream),
            remotelyCachedEntity.name!!
        )
        activeOutputStream.add(gaugeContext)
        try {
            byteArrayChannel.consumeEach { context ->
                if (context.source == ByteArraySource.REMOTE) hasFreshBytes = true
                val bytes = context.byteArray

                if (shouldCache) {
                    if (bytesToCache.sumOf { it.size }
                            .plus(bytesToCache.size) > debridavConfigurationProperties.chunkCachingSizeThreshold) {
                        shouldCache = false
                        bytesToCache = mutableListOf()
                    } else bytesToCache.add(bytes)
                }
                withContext(Dispatchers.IO) {
                    gaugeContext.outputStream.write(bytes)
                }
                bytesSent += context.byteArray.size
            }
        } catch (_: CancellationException) {
            parentContext.cancel()
        } catch (_: ClientAbortException) {
            parentContext.cancel()
        } catch (e: Exception) {
            logger.error("An error occurred during streaming", e)
            parentContext.cancel()
        } finally {
            gaugeContext.outputStream.close()
            activeOutputStream.removeStream(gaugeContext)
            if (bytesToCache.isNotEmpty() && hasFreshBytes) {
                cacheBytes(
                    remotelyCachedEntity, Range(
                        requestedRange.start,
                        requestedRange.start + bytesToCache.sumOf { it.size }.toLong() - 1
                    ), bytesToCache
                )
            }
        }
    }

    fun cacheBytes(
        remotelyCachedEntity: RemotelyCachedEntity,
        range: Range,
        bytes: List<ByteArray>
    ) {
        val totalSize = bytes.sumOf { it.size }
        val byteArrayToCache = bytes.fold(ByteArray(0)) { acc, bytes -> acc.plus(bytes) }

        fileChunkCachingService.cacheChunk(
            byteArrayToCache, remotelyCachedEntity, range.start, range.finish
        )

        logger.info("done saving chunk: ${range.start}-${range.finish} of $totalSize bytes to cache")
    }

    @Scheduled(fixedRate = 5_000)
    fun recordMetrics() {
        activeOutputStream.forEach {
            outputGauge.labelValues(it.file).set(it.outputStream.countAndReset().toDouble().div(5))
        }
        activeInputStreams.forEach {
            inputGauge.labelValues(it.provider.toString(), it.file)
                .set(it.inputStream.countAndReset().toDouble().div(5))
        }
    }
}

