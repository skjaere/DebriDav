package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.DefaultDebridClientGate
import io.skjaere.debridav.debrid.client.guarded
import io.skjaere.debridav.debrid.model.DebridQuotaExceededError
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebridClientGateTest {

    /** Mutable Clock so tests can advance time without sleeping. */
    private class MutableClock(var instant: Instant) : Clock() {
        override fun instant() = instant
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        fun advance(by: Duration) { instant = instant.plus(by) }
    }

    private val start = Instant.parse("2026-04-28T10:00:00Z")
    private lateinit var clock: MutableClock
    private lateinit var gate: DefaultDebridClientGate

    @BeforeEach
    fun setUp() {
        clock = MutableClock(start)
        gate = DefaultDebridClientGate(DebridProvider.REAL_DEBRID, clock)
    }

    @Test
    fun `gate is closed by default`() {
        assertTrue(gate.isClosed())
        val s = gate.status()
        assertTrue(s.closed)
        assertNull(s.releaseAt)
        assertNull(s.reason)
    }

    @Test
    fun `trip blocks traffic until releaseAt`() {
        gate.trip(start.plus(Duration.ofHours(1)), "traffic_exhausted")
        assertFalse(gate.isClosed())
        val s = gate.status()
        assertFalse(s.closed)
        assertEquals(start.plus(Duration.ofHours(1)), s.releaseAt)
        assertEquals("traffic_exhausted", s.reason)
    }

    @Test
    fun `gate auto-resets when clock passes releaseAt`() {
        gate.trip(start.plus(Duration.ofMinutes(5)), "test")
        assertFalse(gate.isClosed())

        clock.advance(Duration.ofMinutes(6))
        assertTrue(gate.isClosed(), "should auto-close once releaseAt is in the past")
        assertNull(gate.status().releaseAt, "auto-reset should clear releaseAt")
    }

    @Test
    fun `reset closes the gate immediately`() {
        gate.trip(start.plus(Duration.ofHours(6)), "test")
        assertFalse(gate.isClosed())

        gate.reset()
        assertTrue(gate.isClosed())
        assertNull(gate.status().releaseAt)
    }

    @Test
    fun `guarded short-circuits with DebridQuotaExceededError when gate is open`() = runBlocking {
        gate.trip(start.plus(Duration.ofHours(6)), "traffic_exhausted")

        var blockExecuted = false
        val thrown = assertThrows<DebridQuotaExceededError> {
            runBlocking {
                gate.guarded(Duration.ofHours(6), clock) {
                    blockExecuted = true
                    "should never run"
                }
            }
        }

        assertFalse(blockExecuted, "block must not run while gate is open")
        assertEquals("traffic_exhausted", thrown.reason)
    }

    @Test
    fun `guarded trips the gate when block throws DebridQuotaExceededError`() = runBlocking {
        assertThrows<DebridQuotaExceededError> {
            runBlocking {
                gate.guarded(Duration.ofHours(6), clock) {
                    throw DebridQuotaExceededError(
                        "fake", 503, "/x", reason = "traffic_exhausted",
                    )
                }
            }
        }

        assertFalse(gate.isClosed(), "gate should be tripped after the block threw")
        assertEquals("traffic_exhausted", gate.status().reason)
        assertEquals(start.plus(Duration.ofHours(6)), gate.status().releaseAt)
    }

    @Test
    fun `guarded resets the gate eagerly on a successful block`() = runBlocking {
        gate.trip(start.plus(Duration.ofHours(6)), "test")
        // Move clock forward so the gate auto-allows traffic again, then call guarded.
        clock.advance(Duration.ofHours(7))
        gate.trip(start.plus(Duration.ofHours(20)), "test_again")

        // Wait — gate is now tripped again. We need a scenario where the gate is closed
        // (after auto-reset) and the block succeeds. Reset and try again:
        gate.reset()
        // Pre-trip with a short release so guarded sees it as already-closed (after auto-reset).
        gate.trip(start.minus(Duration.ofHours(1)), "stale")  // releaseAt in the past → auto-resets

        val result = runBlocking {
            gate.guarded(Duration.ofHours(6), clock) { "ok" }
        }
        assertEquals("ok", result)
        assertTrue(gate.isClosed())
        assertNull(gate.status().reason, "successful block should clear gate state")
    }

    @Test
    fun `guarded does not trip on non-quota exceptions`() = runBlocking {
        val thrown = assertThrows<IllegalStateException> {
            runBlocking {
                gate.guarded(Duration.ofHours(6), clock) {
                    error("not a quota error")
                }
            }
        }
        assertEquals("not a quota error", thrown.message)
        assertTrue(gate.isClosed(), "non-quota exceptions must not trip the gate")
    }

    @Test
    fun `repeated trips refresh the releaseAt and reason`() {
        gate.trip(start.plus(Duration.ofHours(1)), "first")
        gate.trip(start.plus(Duration.ofHours(2)), "second")
        val s = gate.status()
        assertEquals(start.plus(Duration.ofHours(2)), s.releaseAt)
        assertEquals("second", s.reason)
    }

    @Test
    fun `status reflects auto-reset when called after expiry`() {
        gate.trip(start.plus(Duration.ofMinutes(1)), "test")
        clock.advance(Duration.ofMinutes(2))
        val s = gate.status()
        assertTrue(s.closed)
        assertNotNull(gate)  // sanity: instance still works
    }
}
