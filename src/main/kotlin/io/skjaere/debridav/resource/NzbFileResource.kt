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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

private const val BUFFER_SIZE = 8192
private const val STALL_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

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
            "streaming NZB file: {} range {} (size: {})",
            streamableFile.path, range, streamableFile.totalSize
        )
        try {
            runBlocking { streamWithStallWatchdog(out, longRange) }
        } catch (e: ArticleNotFoundException) {
            logger.warn("Article not found while streaming {}: {}", streamableFile.path, e.message)
            enqueueRepairForMissingArticle(e.message)
        } catch (e: CancellationException) {
            logger.debug("Stream cancelled for {}: {}", streamableFile.path, e.message)
        }
    }

    // Runs the read pump and a stall watchdog as separate Jobs in the same scope so the
    // watchdog can cancel the pump directly: closing `out` alone doesn't unblock
    // `channel.readAvailable` when the writer is stalled with no bytes to deliver — the
    // read loop sits parked and the surrounding streamSegments coroutineScope leaks its
    // activeStreams counter.
    private suspend fun streamWithStallWatchdog(out: OutputStream, longRange: LongRange?) {
        val sawActivity = AtomicBoolean(true)
        coroutineScope {
            val streamJob = launch {
                nzbStreamer.streamFile(nzbDocument, streamableFile, longRange) { channel ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) {
                            out.write(buffer, 0, bytesRead)
                            sawActivity.set(true)
                        }
                    }
                }
            }
            val watchdog = launch {
                while (isActive) {
                    delay(STALL_TIMEOUT_MS.milliseconds)
                    if (!sawActivity.getAndSet(false)) {
                        logger.warn(
                            "NZB stream '{}' stalled (>{}ms without bytes); cancelling fetch",
                            streamableFile.path, STALL_TIMEOUT_MS
                        )
                        runCatching { out.close() }
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
