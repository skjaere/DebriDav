package io.skjaere.debridav.test.collection

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.skjaere.debridav.collection.play.CollectionPlayProperties
import io.skjaere.debridav.collection.play.PlaySession
import io.skjaere.debridav.collection.play.PlaySessionRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A play session holds what one player opened (a prepared release, FFmpeg runs, segment files). Every way a session
 * ends must let go of all of it, so nothing piles up while debridav runs.
 */
class PlaySessionRegistryTest {

    @TempDir
    lateinit var root: Path

    private val clock = MutableClock(Instant.parse("2026-11-23T20:00:00Z"))
    private val meters = SimpleMeterRegistry()

    private fun registry(idle: Duration = Duration.ofMinutes(30), maxAge: Duration = Duration.ofHours(8)) =
        PlaySessionRegistry(
            CollectionPlayProperties().apply {
                idleTimeout = idle
                this.maxAge = maxAge
                workDir = root.toString()
            },
            clock,
            meters,
        )

    @Test
    fun `a session is found by its token, which is unguessable`() {
        val registry = registry()
        val session = registry.open("alice")
        assertSame(session, registry.find(session.token))
        assertNull(registry.find("not-a-token"))
        assertTrue(session.token.length >= 40, "a token of 256 random bits")
        assertNotEquals(session.token, registry.open("bob").token)
    }

    @Test
    fun `closing a session lets go of everything it holds`() = runBlocking {
        val registry = registry()
        val session = registry.open("alice")
        val closed = AtomicInteger()
        session.own(AutoCloseable { closed.incrementAndGet() })
        session.own(AutoCloseable { error("one failing to close doesn't stop the others") })
        session.own(AutoCloseable { closed.incrementAndGet() })
        session.workDir.resolve("seg0.m4s").writeText("segment")
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        session.scope.launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        withTimeout(5_000) { started.await() }

        assertTrue(registry.close(session.token))

        assertEquals(2, closed.get())
        assertFalse(session.workDir.exists(), "its folder is deleted")
        withTimeout(5_000) { cancelled.await() }
        assertNull(registry.find(session.token))
        assertTrue(session.isClosed)
        assertEquals(0.0, gauge())
    }

    @Test
    fun `a new play from the same user replaces the old one`() {
        val registry = registry()
        val first = registry.open("alice")
        val other = registry.open("bob")
        val second = registry.open("alice")

        assertTrue(first.isClosed)
        assertFalse(first.workDir.exists())
        assertSame(second, registry.find(second.token))
        assertSame(other, registry.find(other.token))
        assertEquals(2.0, gauge())
    }

    @Test
    fun `an idle session is closed, and one the player keeps asking for is kept`() {
        val registry = registry(idle = Duration.ofMinutes(30))
        val idle = registry.open("alice")
        val busy = registry.open("bob")

        clock.advance(Duration.ofMinutes(20))
        registry.find(busy.token)
        clock.advance(Duration.ofMinutes(15))
        registry.closeExpired()

        assertTrue(idle.isClosed)
        assertFalse(busy.isClosed)
        assertNull(registry.find(idle.token))
    }

    @Test
    fun `a session past its maximum age is closed even while in use`() {
        val registry = registry(idle = Duration.ofMinutes(30), maxAge = Duration.ofHours(2))
        val session = registry.open("alice")
        repeat(5) {
            clock.advance(Duration.ofMinutes(25))
            registry.find(session.token)
        }
        registry.closeExpired()
        assertTrue(session.isClosed)
    }

    @Test
    fun `a resource handed to a closed session is closed at once`() {
        val registry = registry()
        val session = registry.open("alice")
        registry.close(session.token)
        val closed = AtomicInteger()
        session.own(AutoCloseable { closed.incrementAndGet() })
        assertEquals(1, closed.get())
    }

    @Test
    fun `shutting down closes every session`() {
        val registry = registry()
        val sessions = listOf(registry.open("alice"), registry.open("bob"))
        registry.closeAll()
        assertTrue(sessions.all(PlaySession::isClosed))
        assertEquals(emptyList(), root.listDirectoryEntries())
    }

    @Test
    fun `folders a previous run left behind are deleted at startup`() {
        Files.createDirectories(root.resolve("left-over/deeper")).resolve("seg3.m4s").writeText("x")
        registry().deleteLeftovers()
        assertEquals(emptyList(), root.listDirectoryEntries())
    }

    private fun gauge(): Double = meters.get("debridav.collection.play.sessions").gauge().value()

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
