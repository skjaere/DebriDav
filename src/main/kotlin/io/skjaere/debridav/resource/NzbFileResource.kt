package io.skjaere.debridav.resource

import com.vdsirotkin.pgmq.PgmqClient
import io.ktor.utils.io.readAvailable
import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.usenet.pgmq.NzbHealthRepairMessage
import io.skjaere.nntp.ArticleNotFoundException
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

private const val BUFFER_SIZE = 64 * 1024
private const val OUTPUT_BUFFER_SIZE = 256 * 1024
private const val STALL_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

// Threshold above which the per-stream startup-latency breakdown is escalated from
// debug to warn. Sized to "this is noticeable to the user" — typical healthy streams
// emit a first byte within hundreds of ms, so anything above 5s deserves a log line
// that surfaces in normal scrolling and (via sentry-logback later, if desired) GlitchTip.
private const val SLOW_START_THRESHOLD_MS = 5_000L

class NzbFileResource(
    val file: RemotelyCachedEntity,
    fileService: DatabaseFileService,
    private val nzbStreamer: NzbStreamer,
    private val nzbDocument: NzbDocument,
    private val streamableFile: StreamableFile,
    debridavConfigurationProperties: DebridavConfigurationProperties,
    private val pgmqClient: PgmqClient? = null,
    private val nzbDocumentId: Long? = null,
    private val nzbDocumentName: String? = null,
    private val nzbDocumentCategory: String? = null,
) : AbstractResource(fileService, file, debridavConfigurationProperties),
    GetableResource, DeletableResource {

    private val logger = LoggerFactory.getLogger(NzbFileResource::class.java)

    override fun getUniqueId(): String = dbItem.id!!.toString()

    override fun getName(): String = dbItem.name!!

    override fun getModifiedDate(): Date = Date.from(Instant.ofEpochMilli(dbItem.lastModified!!))

    override fun getCreateDate(): Date = modifiedDate

    override fun checkRedirect(request: Request?): String? = null

    override fun delete() {
        fileService.deleteFile(dbItem)
    }

    override fun getMaxAgeSeconds(auth: Auth?): Long = 100

    override fun getContentType(accepts: String?): String {
        return file.contents?.mimeType ?: "application/octet-stream"
    }

    override fun getContentLength(): Long = streamableFile.totalSize

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) {
        val longRange = range?.let { r ->
            val start = r.start ?: 0L
            val finish = r.finish ?: (streamableFile.totalSize - 1)
            start..finish
        }
        logger.debug(
            "streaming NZB file: {} (path: {}) range {} (size: {})",
            logLabel(), streamableFile.path, range, streamableFile.totalSize
        )
        try {
            runBlocking { streamWithStallWatchdog(out, longRange) }
        } catch (e: ArticleNotFoundException) {
            logger.warn("Article not found while streaming {}: {}", logLabel(), e.message)
            enqueueRepairForMissingArticle(e.message)
        } catch (e: TimeoutCancellationException) {
            // Timeout — distinct from a definitive 430. The article may still exist; don't
            // enqueue a repair (which blocklists the NZB in Sonarr/Radarr). Surface as a
            // warn so the operator sees transient NNTP slowness without it being mistaken
            // for a "stream cancelled by client" no-op.
            logger.warn("Stream timed out fetching segment for {}: {}", logLabel(), e.message)
        } catch (e: CancellationException) {
            logger.debug("Stream cancelled for {}: {}", logLabel(), e.message)
        }
    }

    // Prefer the release name from the parent NzbDocumentEntity (e.g.
    // "Fire.Country.S04E17.1080p...") over the streamable file path, which for
    // RAW NZBs and a lot of NZBGeek-style uploads is an obfuscated hash like
    // "d91f84087e75401f871accd499e2f7d5.mkv". Falls back to the file path when
    // the document name is null. Used for log lines + the stream identifier
    // passed into nzb-streamer's bitrate gauge and startup trace.
    private fun logLabel(): String = nzbDocumentName ?: streamableFile.path

    // Runs the read pump and a stall watchdog as separate Jobs in the same scope so the
    // watchdog can cancel the pump directly: closing `out` alone doesn't unblock
    // `channel.readAvailable` when the writer is stalled with no bytes to deliver — the
    // read loop sits parked and the surrounding streamSegments coroutineScope leaks its
    // activeStreams counter.
    //
    // `bufferedOut` wraps the raw ServletOutputStream so producer-side bursty reads
    // (readAvailable can return as little as 1 byte) coalesce in JVM memory before
    // hitting the socket — without it each readAvailable triggers its own Tomcat
    // write/flush, capping single-stream throughput around 20 MiB/s on saturated NNTP
    // links. The 5-minute stall watchdog still runs unmodified: writes into the
    // BufferedOutputStream count as activity even before they're flushed downstream,
    // which is the producer-liveness signal we actually care about.
    private suspend fun streamWithStallWatchdog(out: OutputStream, longRange: LongRange?) {
        val sawActivity = AtomicBoolean(true)
        val bufferedOut = BufferedOutputStream(out, OUTPUT_BUFFER_SIZE)
        // Three-point timing breakdown to localize startup stalls without needing JDWP:
        //   T0 = entry to this function (caller is sendContent, runBlocking just took the thread)
        //   T1 = nzb-streamer's streamFile invokes our consume callback for the first time
        //        (everything before this is producer-side setup: connection pool checkout,
        //        any internal seek/header work, first article fetch in flight)
        //   T2 = first byte actually arrives at the consumer (channel.readAvailable > 0)
        // T0→T1 isolates producer setup; T1→T2 isolates first-NNTP-byte latency. After
        // first byte we stop measuring — sustained-throughput problems are a different
        // diagnostic and the stall watchdog already handles mid-stream stuck states.
        val t0 = System.nanoTime()
        var t1 = 0L
        var t2 = 0L
        coroutineScope {
            val streamJob = launch {
                nzbStreamer.streamFile(nzbDocument, streamableFile, longRange, streamName = logLabel()) { channel ->
                    t1 = System.nanoTime()
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) {
                            if (t2 == 0L) {
                                t2 = System.nanoTime()
                                logStartupTiming(longRange, t0, t1, t2)
                            }
                            bufferedOut.write(buffer, 0, bytesRead)
                            sawActivity.set(true)
                        }
                    }
                    bufferedOut.flush()
                }
            }
            val watchdog = launch {
                while (isActive) {
                    delay(STALL_TIMEOUT_MS.milliseconds)
                    if (!sawActivity.getAndSet(false)) {
                        logger.warn(
                            "NZB stream '{}' stalled (>{}ms without bytes); cancelling fetch",
                            logLabel(), STALL_TIMEOUT_MS
                        )
                        runCatching { bufferedOut.close() }
                        streamJob.cancel(
                            CancellationException("Stream stalled (no bytes for ${STALL_TIMEOUT_MS}ms)")
                        )
                        break
                    }
                }
            }
            streamJob.join()
            watchdog.cancel()
        }
    }

    private fun logStartupTiming(longRange: LongRange?, t0: Long, t1: Long, t2: Long) {
        val setupMs = (t1 - t0) / 1_000_000
        val firstByteMs = (t2 - t1) / 1_000_000
        val totalMs = (t2 - t0) / 1_000_000
        if (totalMs >= SLOW_START_THRESHOLD_MS) {
            logger.warn(
                "Slow stream startup for '{}' range={} size={}: setup={}ms firstByte={}ms total={}ms",
                logLabel(), longRange, streamableFile.totalSize,
                setupMs, firstByteMs, totalMs,
            )
        } else {
            logger.debug(
                "Stream startup for '{}': setup={}ms firstByte={}ms total={}ms",
                logLabel(), setupMs, firstByteMs, totalMs,
            )
        }
    }

    private fun enqueueRepairForMissingArticle(failureMessage: String?) {
        if (pgmqClient == null || nzbDocumentId == null) return
        pgmqClient.send(
            "nzb_health_repair",
            NzbHealthRepairMessage(
                nzbDocumentId = nzbDocumentId,
                message = "Streaming failure: $failureMessage",
                name = nzbDocumentName,
                category = nzbDocumentCategory,
            )
        )
    }
}
