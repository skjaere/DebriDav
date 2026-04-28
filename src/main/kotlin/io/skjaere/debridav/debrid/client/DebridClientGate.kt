package io.skjaere.debridav.debrid.client

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.model.DebridQuotaExceededError
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Circuit-breaker-style gate that short-circuits calls to a debrid provider once the provider
 * has signalled a quota / availability problem. Each [DebridCachedContentClient] embeds an
 * instance via Kotlin's class delegation (`DebridClientGate by DefaultDebridClientGate(...)`),
 * so callers can introspect `isClosed()`/`status()` without knowing about a separate object.
 */
interface DebridClientGate {
    /** True when traffic should pass through to the provider (the default state). */
    fun isClosed(): Boolean

    /** Open the circuit until [releaseAt]. [reason] is opaque telemetry. */
    fun trip(releaseAt: Instant, reason: String)

    /** Close the circuit immediately — typically called on the next successful response. */
    fun reset()

    fun status(): GateStatus
}

data class GateStatus(
    val closed: Boolean,
    val releaseAt: Instant?,
    val reason: String?,
)

/**
 * In-memory time-bounded gate. Auto-resets when [Instant.now] passes [releaseAt] without a
 * caller having to do anything — the next [isClosed] check in that window flips state back to
 * traffic-allowed. State is per-instance, so each provider gets its own gate.
 *
 * On registration with a [MeterRegistry], publishes:
 *   * `debridav.debrid.gate.closed{provider}` gauge — 1 when traffic flowing, 0 when blocked
 *   * `debridav.debrid.gate.tripped{provider, reason}` counter — incremented on every [trip]
 */
class DefaultDebridClientGate(
    private val provider: DebridProvider,
    private val clock: Clock = Clock.systemUTC(),
    meterRegistry: MeterRegistry? = null,
) : DebridClientGate {

    private data class State(val releaseAt: Instant?, val reason: String?)

    private val state = AtomicReference(State(null, null))
    private val tags = Tags.of("provider", provider.name)
    private val tripCounterRegistry = meterRegistry

    init {
        meterRegistry?.gauge(
            "debridav.debrid.gate.closed",
            tags,
            this,
        ) { if (it.isClosed()) 1.0 else 0.0 }
    }

    override fun isClosed(): Boolean {
        val current = state.get()
        val releaseAt = current.releaseAt ?: return true
        if (clock.instant().isAfter(releaseAt)) {
            // Lazy auto-reset: if the release time has elapsed, treat as closed and clear
            // state so the next caller hits the fast path. compareAndSet so we don't stomp
            // on a concurrent trip().
            state.compareAndSet(current, State(null, null))
            return true
        }
        return false
    }

    override fun trip(releaseAt: Instant, reason: String) {
        state.set(State(releaseAt, reason))
        tripCounterRegistry?.counter(
            "debridav.debrid.gate.tripped",
            tags.and("reason", reason),
        )?.increment()
    }

    override fun reset() {
        state.set(State(null, null))
    }

    override fun status(): GateStatus {
        val current = state.get()
        return GateStatus(isClosed(), current.releaseAt, current.reason)
    }
}

/**
 * Wraps [block] so that the [DebridClientGate] short-circuits subsequent calls for [backoff]
 * after a [DebridQuotaExceededError] is observed, and is reset eagerly on success.
 *
 * If the gate is already open when this is invoked, [block] is not executed and a synthetic
 * [DebridQuotaExceededError] is thrown — letting `DebridLinkService.mapExceptionToDebridFile`
 * persist the call as `QuotaExceeded` without making a real provider call.
 *
 * The trip itself is observable via the `debridav.debrid.gate.tripped{provider, reason}`
 * counter that [DefaultDebridClientGate] increments on every [DebridClientGate.trip].
 */
suspend inline fun <T> DebridClientGate.guarded(
    backoff: Duration,
    clock: Clock,
    block: () -> T,
): T {
    if (!isClosed()) {
        val s = status()
        throw DebridQuotaExceededError(
            "gate open until ${s.releaseAt}: ${s.reason}",
            statusCode = 0,
            endpoint = "(gate)",
            reason = s.reason,
        )
    }
    return try {
        block().also { reset() }
    } catch (e: DebridQuotaExceededError) {
        trip(clock.instant().plus(backoff), e.reason ?: "quota_exceeded")
        throw e
    }
}
