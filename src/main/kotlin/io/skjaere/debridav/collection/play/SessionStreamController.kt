package io.skjaere.debridav.collection.play

import io.skjaere.nzbstreamer.NzbStreamer
import io.ktor.utils.io.readAvailable
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/** A byte range of a file of known size, as a Range header asks for it. */
internal sealed interface ByteRange {
    data class Of(val first: Long, val last: Long) : ByteRange {
        val length: Long get() = last - first + 1
    }

    data object Unsatisfiable : ByteRange

    companion object {
        /**
         * The single range [header] asks of a file of [size] bytes: one running past the end ends at the end, a
         * suffix range is the last bytes; null for the whole file (no header, or one this doesn't serve, such as
         * several ranges).
         */
        fun parse(header: String?, size: Long): ByteRange? {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=") && ',' !in it }?.removePrefix("bytes=")
            val dash = spec?.indexOf('-') ?: -1
            return if (spec == null || dash < 0) {
                null
            } else {
                parse(spec.substring(0, dash).trim(), spec.substring(dash + 1).trim(), size)
            }
        }

        private fun parse(start: String, end: String, size: Long): ByteRange? {
            val first = start.toLongOrNull()
            val last = end.toLongOrNull()
            return when {
                start.isEmpty() && last != null ->
                    if (last <= 0 || size == 0L) Unsatisfiable else Of(maxOf(0, size - last), size - 1)
                first == null || first < 0 -> null
                first >= size -> Unsatisfiable
                end.isEmpty() -> Of(first, size - 1)
                last != null && last >= first -> Of(first, minOf(last, size - 1))
                else -> null
            }
        }
    }
}

/**
 * Streams the release a play session opened, from what nzb-streamer prepared in memory, to whatever reads it by the
 * session's token: ffprobe checking its tracks, FFmpeg remuxing it. Each read runs in the session's scope, so closing
 * the session stops it, as does the reader going away.
 */
@RestController
@RequestMapping("/api/v1/collection/sessions/{token}")
class SessionStreamController(
    private val registry: PlaySessionRegistry,
    private val nzbStreamer: NzbStreamer,
) {
    private val log = LoggerFactory.getLogger(SessionStreamController::class.java)

    @GetMapping("/source")
    fun source(@PathVariable token: String, request: HttpServletRequest, response: HttpServletResponse) {
        val session = registry.find(token)
        val release = session?.release
        if (session == null || release == null) {
            response.status = HttpStatus.NOT_FOUND.value()
            return
        }
        val size = release.file.totalSize
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
        val range = when (val asked = ByteRange.parse(request.getHeader(HttpHeaders.RANGE), size)) {
            ByteRange.Unsatisfiable -> {
                response.status = HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value()
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */$size")
                return
            }
            is ByteRange.Of -> asked
            null -> ByteRange.Of(0, size - 1).takeIf { size > 0 }
        }
        response.contentType = "application/octet-stream"
        if (range == null || range.length == size) {
            response.status = HttpStatus.OK.value()
            response.setContentLengthLong(size)
        } else {
            response.status = HttpStatus.PARTIAL_CONTENT.value()
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes ${range.first}-${range.last}/$size")
            response.setContentLengthLong(range.length)
        }
        if (range != null) stream(session, release, range.first..range.last, response.outputStream)
    }

    private fun stream(session: PlaySession, release: OpenRelease, range: LongRange, out: OutputStream) {
        val buffered = BufferedOutputStream(out, OUTPUT_BUFFER_BYTES)
        try {
            runBlocking {
                session.scope.async {
                    nzbStreamer.streamFile(release.metadata, release.file, range) { channel ->
                        val buffer = ByteArray(READ_BUFFER_BYTES)
                        while (true) {
                            val read = channel.readAvailable(buffer)
                            if (read < 0) break
                            if (read > 0) buffered.write(buffer, 0, read)
                        }
                        buffered.flush()
                    }
                }.await()
            }
        } catch (e: CancellationException) {
            log.debug("play session {}: stream of {} stopped: {}", session.id, release.title, e.message)
        } catch (e: IOException) {
            // The reader went away, e.g. FFmpeg seeking elsewhere.
            log.debug("play session {}: reader of {} went away: {}", session.id, release.title, e.message)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            log.warn("play session {}: streaming {} failed: {}", session.id, release.title, e.message)
        }
    }

    private companion object {
        const val OUTPUT_BUFFER_BYTES = 256 * 1024
        const val READ_BUFFER_BYTES = 64 * 1024
    }
}
