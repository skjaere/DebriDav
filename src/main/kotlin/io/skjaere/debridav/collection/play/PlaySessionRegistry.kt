package io.skjaere.debridav.collection.play

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ConfigurationProperties(prefix = "collection.play")
class CollectionPlayProperties {
    /** A session the player hasn't asked anything of for this long is closed: the player went away. */
    var idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT

    /** No session lives longer than this, in use or not. */
    var maxAge: Duration = DEFAULT_MAX_AGE

    /** The ffprobe binary that checks a release's tracks. */
    var ffprobe: String = "ffprobe"

    /** The ffmpeg binary that remuxes a release for the browser. */
    var ffmpeg: String = "ffmpeg"

    /** How many releases a play tries before it gives up; each costs an indexer grab. */
    var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS

    /** Where sessions' segment folders go; emptied at startup. */
    var workDir: String = Paths.get(System.getProperty("java.io.tmpdir"), "debridav-play").toString()

    private companion object {
        val DEFAULT_IDLE_TIMEOUT: Duration = Duration.ofMinutes(30)
        val DEFAULT_MAX_AGE: Duration = Duration.ofHours(8)
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}

/**
 * The open play sessions, by token. A session ends when its player closes it, when the same user starts another, when
 * it has been idle for [CollectionPlayProperties.idleTimeout], when it reaches [CollectionPlayProperties.maxAge], or
 * when debridav shuts down; whatever ends it, [PlaySession.close] lets go of all it holds. Folders a crashed run left
 * behind are deleted at startup.
 */
@Component
class PlaySessionRegistry(
    private val properties: CollectionPlayProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(PlaySessionRegistry::class.java)
    private val sessions = ConcurrentHashMap<String, PlaySession>()
    private val random = SecureRandom()

    init {
        Gauge.builder("debridav.collection.play.sessions", sessions) { it.size.toDouble() }
            .description("Open collection play sessions")
            .register(meterRegistry)
    }

    /** A new session for [user], closing any they had: a player plays one thing at a time. */
    fun open(user: String): PlaySession {
        sessions.values.filter { it.user == user }.forEach { close(it.token) }
        val id = UUID.randomUUID().toString()
        val workDir = Files.createDirectories(root().resolve(id))
        val session = PlaySession(id, newToken(), user, workDir, clock.instant(), clock)
        sessions[session.token] = session
        log.info("play session {} opened for {}", id, user)
        return session
    }

    /** The session [token] opens, marked as in use; null when there is none. */
    fun find(token: String): PlaySession? = sessions[token]?.also { it.touch() }

    /** Closes the session [token] opens; false when there was none. */
    fun close(token: String): Boolean {
        val session = sessions.remove(token) ?: return false
        session.close()
        return true
    }

    @Scheduled(fixedDelayString = "PT30S")
    fun closeExpired() {
        val now = clock.instant()
        sessions.values
            .filter { session ->
                session.lastActivity.plus(properties.idleTimeout) <= now ||
                    session.createdAt.plus(properties.maxAge) <= now
            }
            .forEach { session ->
                log.info("play session {} expired (last asked for at {})", session.id, session.lastActivity)
                close(session.token)
            }
    }

    @PreDestroy
    fun closeAll() {
        sessions.keys.toList().forEach(::close)
    }

    /** Deletes the segment folders a previous run left behind, e.g. one that crashed. */
    @EventListener(ApplicationReadyEvent::class)
    fun deleteLeftovers() {
        val root = root()
        if (!Files.isDirectory(root)) return
        Files.list(root).use { entries ->
            entries.filter { path -> sessions.values.none { it.workDir == path } }
                .forEach(PlaySession::deleteRecursively)
        }
    }

    private fun root(): Path = Files.createDirectories(Paths.get(properties.workDir))

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
