package io.skjaere.debridav.stream

import io.ktor.client.call.body
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.cache.FileChunkCachingService
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
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue


private const val DEFAULT_BUFFER_SIZE = 16384L

@Service
class StreamingService(
    private val debridClients: List<DebridCachedContentClient>,
    private val fileChunkCachingService: FileChunkCachingService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val streamPlanningService: StreamPlanningService,
    prometheusRegistry: PrometheusRegistry
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)
    private val outputGauge = Gauge.builder().name("debridav.output.stream.bitrate").labelNames("provider", "file")
        .labelNames("provider", "file").register(prometheusRegistry)

    private val inputGauge = Gauge.builder().name("debridav.input.stream.bitrate").labelNames("provider", "file")
        .register(prometheusRegistry)

    private val activeOutputStream = ConcurrentLinkedQueue<OutputStreamingContext>()
    private val activeInputStreams = ConcurrentLinkedQueue<InputStreamingContext>()


    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    suspend fun streamContents(
        debridLink: CachedFile, range: Range?, outputStream: OutputStream, remotelyCachedEntity: RemotelyCachedEntity
    ): StreamResult = coroutineScope {
        logger.info("begin streaming ${debridLink.path}")
        try {
            val appliedRange = Range(range?.start ?: 0, range?.finish ?: (debridLink.size!! - 1))
            streamBytes(remotelyCachedEntity, appliedRange, debridLink, outputStream)
            logger.info("done streaming ${debridLink.path}: OK")
            StreamResult.OK
        } catch (_: CancellationException) {
            StreamResult.OK
        } catch (_: LinkNotFoundException) {
            StreamResult.DEAD_LINK
        } catch (e: Exception) {
            logger.error("An error occurred during streaming ${debridLink.path}", e)
            StreamResult.ERROR
        }
    }


    private suspend fun streamBytes(
        remotelyCachedEntity: RemotelyCachedEntity, range: Range, debridLink: CachedFile, outputStream: OutputStream
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
                ), outputStream, remotelyCachedEntity, range
            )
        }
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
        streamPlan: ReceiveChannel<StreamPlanningService.StreamSource>
    ): ReceiveChannel<ByteArrayContext> =
        this.produce(this.coroutineContext, 2) {
            streamPlan.consumeEach { sourceContext ->
                when (sourceContext) {
                    is StreamPlanningService.StreamSource.Cached -> sendCachedBytes(
                        sourceContext, sourceContext.fileChunk.startByte!!
                    )

                    is StreamPlanningService.StreamSource.Remote -> sendBytesFromHttp(
                        sourceContext, sourceContext.cachedFile.provider
                    )
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<ByteArrayContext>.sendBytesFromHttp(
        source: StreamPlanningService.StreamSource.Remote, provider: DebridProvider?
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
            resp.bodyAsChannel().toInputStream().use { inputStream ->
                val streamingContext = InputStreamingContext(
                    ResettableCountingInputStream(inputStream), source.cachedFile.provider!!, source.cachedFile.path!!
                )
                try {
                    pipeInputStreamToOutputChannel(streamingContext, byteRangeInfo, source, provider)
                } catch (e: Exception) {
                    logger.error("An error occurred during streaming", e)
                    activeInputStreams.remove(streamingContext)
                }
            }
        }
    }

    private suspend fun ProducerScope<ByteArrayContext>.pipeInputStreamToOutputChannel(
        streamingContext: InputStreamingContext,
        byteRangeInfo: FileChunkCachingService.ByteRangeInfo?,
        source: StreamPlanningService.StreamSource.Remote,
        provider: DebridProvider?
    ): Boolean {
        activeInputStreams.add(streamingContext)
        var remaining = byteRangeInfo!!.length()
        while (remaining > 0) {
            val size = listOf(remaining, DEFAULT_BUFFER_SIZE).min()
            try {
                val bytes = withContext(Dispatchers.IO) {
                    streamingContext.inputStream.readNBytes(size.toInt())
                }
                send(
                    ByteArrayContext(
                        bytes, Range(source.range.start, source.range.last), ByteArraySource.REMOTE, provider!!
                    )
                )
                remaining -= bytes.size
            } catch (_: CancellationException) {
                cancel()
                break;
            }
        }
        return activeInputStreams.remove(streamingContext)
    }

    private fun ProducerScope<ByteArrayContext>.sendCachedBytes(
        source: StreamPlanningService.StreamSource.Cached, startByte: Long
    ) {
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
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
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
                    val bytes = context.byteArray
                    if (shouldCache) {
                        if (bytesToCache.sumOf { it.size }
                                .plus(bytesToCache.size) > debridavConfigurationProperties.chunkCachingSizeThreshold) {
                            shouldCache = false
                            bytesToCache = mutableListOf()
                        } else bytesToCache.add(bytes)
                    }

                    withContext(Dispatchers.IO) {
                        try {
                            gaugeContext.outputStream.write(bytes)
                        } catch (_: CancellationException) {
                            cancel()
                        }
                    }

                    bytesSent += context.byteArray.size
                } catch (_: ClientAbortException) {
                    if (bytesToCache.isNotEmpty() && hasFreshBytes) {
                        cacheBytes(
                            remotelyCachedEntity, Range(
                                context.range.start, context.range.start + bytesToCache.sumOf { it.size }.toLong() - 1
                            ), bytesToCache
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

    fun cacheBytes(
        remotelyCachedEntity: RemotelyCachedEntity, range: Range, bytes: List<ByteArray>
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
            outputGauge.labelValues(it.provider.toString(), it.file).set(it.outputStream.countAndReset().toDouble())
        }
        activeInputStreams.forEach {
            inputGauge.labelValues(it.provider.toString(), it.file).set(it.inputStream.countAndReset().toDouble())
        }
    }

}

