package io.skjaere.debridav.collection.play

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException

/** A release as the progress screen shows it. */
data class ReleaseInfo(val id: String, val title: String, val indexer: String?)

data class TrackInfo(val label: String, val language: String?, val default: Boolean, val playable: Boolean)

data class VideoInfo(val label: String, val width: Int?, val height: Int?, val fps: Double?, val durationMs: Long?)

data class FailureInfo(val release: ReleaseInfo, val failure: String)

/**
 * What a play tells its progress screen, as server-sent events named by [type]: the search (each stage and indexer),
 * then each release tried, step by step (fetching the NZB, checking availability, opening, checking audio and video),
 * then the release that plays, or why none did.
 */
sealed interface PlayEvent {
    val type: String
}

data class SearchStageEvent(val stage: String, val indexers: Int) : PlayEvent {
    override val type = "search-stage"
}

data class SearchIndexerEvent(
    val stage: String?,
    val indexer: String,
    /** How it was searched: "TVDB id", "IMDb id", "name", ...; null when it wasn't. */
    val by: String?,
    val found: Int,
    val accepted: Int,
    val error: String?,
    val skipped: String?,
) : PlayEvent {
    override val type = "search-indexer"
}

data class SearchDoneEvent(val playable: Int, val hidden: Int, val hiddenReasons: List<String>) : PlayEvent {
    override val type = "search-done"
}

data class AttemptEvent(val attempt: Int, val of: Int, val release: ReleaseInfo) : PlayEvent {
    override val type = "attempt"
}

/**
 * One step of one release: [status] running (with [done] of [total]), done (with [detail]) or failed (with
 * [failure]).
 */
data class StepEvent(
    val release: String,
    val step: String,
    val status: String,
    val done: Int? = null,
    val total: Int? = null,
    val millis: Long? = null,
    val detail: String? = null,
    val failure: String? = null,
) : PlayEvent {
    override val type = "step"
}

data class SkippedEvent(val release: ReleaseInfo, val reason: String) : PlayEvent {
    override val type = "skipped"
}

/** A release plays: its tracks, and where the player loads it from. */
data class PlayingEvent(
    val release: ReleaseInfo,
    val video: VideoInfo?,
    val audio: List<TrackInfo>,
    val subtitles: List<TrackInfo>,
    val failedAttempts: List<FailureInfo>,
    /** The HLS playlist; null until the stream is ready. */
    val playlist: String? = null,
) : PlayEvent {
    override val type = "playing"
}

/**
 * No release played: each one tried and why it failed; [notTried] left, [skipped] passed over (their indexer
 * refused).
 */
data class ExhaustedEvent(val failures: List<FailureInfo>, val notTried: Int, val skipped: Int) : PlayEvent {
    override val type = "exhausted"
}

data class ErrorEvent(val message: String) : PlayEvent {
    override val type = "error"
}

data object ClosedEvent : PlayEvent {
    override val type = "closed"
}

/**
 * A session's events: every one kept, so a progress screen that connects (or reconnects) late gets them all, then the
 * new ones as they come. Closed with the session, which ends every stream after a last [ClosedEvent].
 */
class SessionEvents : AutoCloseable {
    private val log = LoggerFactory.getLogger(SessionEvents::class.java)
    private val lock = Any()
    private val history = mutableListOf<PlayEvent>()
    private val emitters = mutableListOf<SseEmitter>()
    private var closed = false

    val events: List<PlayEvent> get() = synchronized(lock) { history.toList() }

    fun emit(event: PlayEvent) {
        val receivers = synchronized(lock) {
            if (closed) return
            history += event
            emitters.toList()
        }
        receivers.forEach { send(it, event) }
    }

    /** A stream of this session's events, the past ones first; one that has ended when the session is closed. */
    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(NO_TIMEOUT)
        val (past, open) = synchronized(lock) {
            if (!closed) emitters += emitter
            history.toList() to !closed
        }
        emitter.onCompletion { forget(emitter) }
        emitter.onTimeout { forget(emitter) }
        emitter.onError { forget(emitter) }
        past.forEach { send(emitter, it) }
        if (!open) {
            send(emitter, ClosedEvent)
            emitter.complete()
        }
        return emitter
    }

    override fun close() {
        val receivers = synchronized(lock) {
            if (closed) return
            closed = true
            history += ClosedEvent
            emitters.toList().also { emitters.clear() }
        }
        receivers.forEach {
            send(it, ClosedEvent)
            it.complete()
        }
    }

    private fun forget(emitter: SseEmitter) {
        synchronized(lock) { emitters -= emitter }
    }

    private fun send(emitter: SseEmitter, event: PlayEvent) {
        try {
            emitter.send(SseEmitter.event().name(event.type).data(event, MediaType.APPLICATION_JSON))
        } catch (e: IOException) {
            log.debug("progress stream went away: {}", e.message)
            forget(emitter)
        } catch (e: IllegalStateException) {
            log.debug("progress stream completed already: {}", e.message)
            forget(emitter)
        }
    }

    private companion object {
        /** The stream lasts as long as the session; the session's own timeouts end it. */
        const val NO_TIMEOUT = 0L
    }
}
