package io.skjaere.debridav.collection.play

import io.skjaere.debridav.collection.hls.HlsStream
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A release opened for playing, held in memory only: what nzb-streamer prepared from its NZB (which articles hold which
 * bytes), and the file picked to play. Nothing of it is written to debridav's filesystem.
 */
class OpenRelease(val title: String, val metadata: ExtractedMetadata, val file: StreamableFile)

/**
 * One player's play: the release it opened, the work started for it ([scope]: the search, the attempts, FFmpeg) and the
 * folder its segments go to. Found by its [token], which is the only key to it. [close] lets go of all of it, once.
 */
class PlaySession internal constructor(
    val id: String,
    val token: String,
    val user: String,
    /** The browser it plays in, as its page names it; null from a page that doesn't. */
    val browser: String?,
    val workDir: Path,
    val createdAt: Instant,
    private val clock: Clock,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(PlaySession::class.java)
    private val closed = AtomicBoolean(false)
    private val owned = ConcurrentLinkedDeque<AutoCloseable>()

    /** What the play tells its progress screen; closed last, so its final event says the session ended. */
    val events: SessionEvents = SessionEvents()

    /** Everything started for this play; cancelled when it closes. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("play-$id"))

    @Volatile
    var lastActivity: Instant = createdAt
        private set

    /** The playing release's HLS stream, once one plays; the session owns it, and closing stops its FFmpeg. */
    @Volatile
    var hls: HlsStream? = null

    /** The release being checked or played; null before one is opened and after the session closes. */
    @Volatile
    var release: OpenRelease? = null
        private set

    val isClosed: Boolean get() = closed.get()

    /** The player asked for something: the session isn't idle. */
    fun touch() {
        lastActivity = clock.instant()
    }

    /** [release] is what this session plays now, in place of any before it. */
    fun open(release: OpenRelease) {
        if (!isClosed) this.release = release
    }

    /** Lets go of [release] if it is still this session's, e.g. once its attempt failed. */
    fun letGo(release: OpenRelease) {
        if (this.release === release) this.release = null
    }

    /** Closes [resource] with this session, the latest first; at once if the session is closed already. */
    fun <T : AutoCloseable> own(resource: T): T {
        owned.push(resource)
        // A close running now either took it from the queue, and closes it, or didn't, and this does.
        if (isClosed && owned.remove(resource)) closeQuietly(resource)
        return resource
    }

    /** [resource] was closed by its owner: the session needn't close it. */
    fun disown(resource: AutoCloseable) {
        owned.remove(resource)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        release = null
        hls = null
        generateSequence { owned.pollFirst() }.forEach(::closeQuietly)
        closeQuietly(events)
        deleteRecursively(workDir)
        log.info("play session {} closed", id)
    }

    private fun closeQuietly(resource: AutoCloseable) {
        try {
            resource.close()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            log.warn("play session {}: closing {} failed: {}", id, resource, e.message)
        }
    }

    internal companion object {
        fun deleteRecursively(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
