package io.skjaere.debridav.usenet

import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * One-shot gate that flips to "ready" once the NNTP pool layer is in its final
 * post-startup configuration. Anything that touches the pool from a Spring bean that
 * may begin work before [ApplicationReadyEvent] (notably the pgmq consumers, which
 * start at SmartLifecycle phase `Int.MAX_VALUE - 1`, BEFORE any
 * `ApplicationReadyEvent` listener runs) should `runBlocking { await() }` first.
 *
 * **Why**: at boot the `nzbStreamer` bean is created from `application.yaml`'s pool
 * config. Then `DatabasePropertySourceInitializer.onApplicationEvent` runs, applies
 * any DB-stored config overrides, and calls `syncRunningPools` — which closes the
 * just-initialized pool and creates a fresh one with the override config. Any
 * waiter the pgmq consumer queued against the old pool in that ~100 ms window
 * surfaces as `IllegalStateException("Pool closed")` from `NntpClientPool.close()`'s
 * exceptional completion of pending waiter deferreds. Gating the handlers on this
 * deferred postpones the first acquire until the pool is in its terminal state.
 *
 * Signalled by [DatabasePropertySourceInitializer] at the end of its
 * [ApplicationListener] callback, regardless of whether overrides existed (the
 * default-no-overrides case still needs to release waiters).
 */
@Component
class NntpPoolReadinessGate {
    private val logger = LoggerFactory.getLogger(NntpPoolReadinessGate::class.java)
    private val gate = CompletableDeferred<Unit>()

    suspend fun await() {
        if (!gate.isCompleted) {
            logger.debug("Awaiting NNTP pool readiness")
        }
        gate.await()
    }

    fun markReady() {
        if (gate.complete(Unit)) {
            logger.info("NNTP pool ready — releasing pgmq consumers")
        }
    }
}
