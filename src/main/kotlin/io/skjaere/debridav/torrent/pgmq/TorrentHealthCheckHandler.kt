package io.skjaere.debridav.torrent.pgmq

import com.vdsirotkin.pgmq.PgmqClient
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.DebridCachedTorrentClient
import io.skjaere.debridav.debrid.client.forProviders
import io.skjaere.debridav.debrid.model.DebridFileUnavailableError
import io.skjaere.debridav.health.HealthMetrics
import io.skjaere.debridav.health.HealthMetrics.CheckResult
import io.skjaere.debridav.health.HealthMetrics.HealthType
import io.skjaere.debridav.health.PgmqHealthQueueRepository
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

// Inline wait budget for rate-limit recovery. Stays comfortably under the PGMQ visibility
// timeout (5 min) so a slow handler doesn't get its message re-delivered while still working.
private const val VERIFY_DEADLINE_MINUTES = 4L
private val VERIFY_DEADLINE: Duration = Duration.ofMinutes(VERIFY_DEADLINE_MINUTES)
private val RATE_LIMIT_RETRY_DELAY = 2.seconds

@Service
@Suppress("LongParameterList")
class TorrentHealthCheckHandler(
    private val torrentRepository: TorrentRepository,
    private val pgmqClient: PgmqClient,
    private val clock: Clock,
    private val healthMetrics: HealthMetrics,
    private val debridClients: List<DebridCachedTorrentClient>,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val pgmqHealthQueueRepository: PgmqHealthQueueRepository,
) {
    private val logger = LoggerFactory.getLogger(TorrentHealthCheckHandler::class.java)

    @Transactional
    fun handle(msg: TorrentHealthCheckMessage) {
        val torrent = torrentRepository.findById(msg.torrentId).orElse(null)
        if (torrent == null) {
            logger.warn("Torrent {} not found, skipping health check", msg.torrentId)
            healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.NOT_FOUND)
            return
        }

        val deferred = healthMetrics.timeCheck(HealthType.TORRENT) {
            verifyWithRateLimitBackoff(torrent)
        }

        if (deferred) {
            // The check didn't run to the end. Don't bump lastVerified (so the next
            // scheduler poll picks it up if still eligible) and clear healthCheckEnqueuedAt
            // so the scheduler isn't blocked from re-enqueueing it.
            torrent.healthCheckEnqueuedAt = null
        } else {
            torrent.lastVerified = Instant.now(clock)
            torrent.healthCheckEnqueuedAt = null
        }
        torrentRepository.save(torrent)
    }

    /**
     * Verifies the torrent. On any provider's rate-limiter saturation (RequestNotPermitted),
     * waits in-process and retries until either the verify resolves or [VERIFY_DEADLINE]
     * elapses. Holding the message in-flight while we wait avoids the queue-amplification
     * loop that re-enqueueing would create during a sustained rate-limit period.
     *
     * @return true if the verify was deferred (deadline reached without resolution, or an
     *         unexpected error such as failing to enqueue the repair), false if it completed
     *         (OK, MISSING, or no provider able to answer).
     */
    @Suppress("ReturnCount")
    private fun verifyWithRateLimitBackoff(torrent: Torrent): Boolean {
        val deadline = Instant.now(clock).plus(VERIFY_DEADLINE)
        var rateLimitedAttempts = 0
        while (true) {
            try {
                runVerify(torrent)
                return false
            } catch (@Suppress("SwallowedException") _: RequestNotPermitted) {
                rateLimitedAttempts++
                if (Instant.now(clock).isAfter(deadline)) {
                    logger.warn(
                        "Rate-limited verifying torrent {} '{}'; deadline reached after {} attempts, deferring",
                        torrent.id, torrent.name, rateLimitedAttempts
                    )
                    healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.RATE_LIMITED)
                    return true
                }
                logger.debug(
                    "Rate-limited verifying torrent {} (attempt {}); waiting {}s before retry",
                    torrent.id, rateLimitedAttempts, RATE_LIMIT_RETRY_DELAY.inWholeSeconds
                )
                runBlocking { delay(RATE_LIMIT_RETRY_DELAY) }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Defer rather than count it as checked: when this is the repair enqueue
                // failing, bumping lastVerified would drop the repair for a whole interval.
                logger.error("Unexpected error verifying torrent {}, deferring", torrent.id, e)
                healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.FAILURE)
                return true
            }
        }
    }

    /**
     * Per-torrent availability cascade. For each configured provider in priority order, ask
     * `isHealthy(torrent)` — RD answers via its synced mirror, others via their `isCached`
     * API. Stops at the first "yes". Replaces the prior per-file HEAD-probe sweep, which
     * scaled API budget consumption with file count even though the question is per-torrent.
     *
     * The torrent is repaired only when every provider said it doesn't have it. If any
     * provider couldn't be asked (gate open, quota, 4xx/5xx, timeout, no torrent-capable
     * provider configured), the check ends without a repair and the torrent is checked again
     * after `health-check.torrent-interval`, as NZB checks do after a verification failure.
     * Re-checking on the next poll instead would ask a provider that is already failing about
     * every due torrent every `health-check.torrent-poll-rate`.
     */
    private fun runVerify(torrent: Torrent) {
        if (torrent.magnet.isNullOrBlank()) {
            logger.debug("Torrent {} has no magnet, skipping health check", torrent.id)
            healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.OK)
            return
        }

        val clients = debridClients.forProviders(debridavConfigurationProperties.debridClients)
        if (clients.isEmpty()) {
            logger.warn(
                "No configured debrid provider can check torrents, so torrent {} '{}' was not checked",
                torrent.id, torrent.name
            )
            healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.FAILURE)
            return
        }

        when (availability(clients, torrent)) {
            Availability.AVAILABLE -> {
                logger.debug("Torrent {} verified", torrent.id)
                healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.OK)
            }

            Availability.UNKNOWN -> {
                logger.warn(
                    "Torrent {} '{}' could not be verified: no provider confirmed it and at least one " +
                            "could not be asked. Not repairing; it is checked again next interval",
                    torrent.id, torrent.name
                )
                healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.FAILURE)
            }

            Availability.NOT_AVAILABLE -> enqueueRepair(torrent)
        }
    }

    private fun availability(clients: List<DebridCachedTorrentClient>, torrent: Torrent): Availability {
        var answer = Availability.NOT_AVAILABLE
        for (client in clients) {
            when (isAvailable(client, torrent)) {
                Availability.AVAILABLE -> return Availability.AVAILABLE
                Availability.UNKNOWN -> answer = Availability.UNKNOWN
                Availability.NOT_AVAILABLE -> Unit
            }
        }
        return answer
    }

    private fun enqueueRepair(torrent: Torrent) {
        healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.MISSING)
        // Dedup against the repair queue itself — if a previous sweep already enqueued
        // a repair for this torrent, don't pile on a second message. PGMQ would
        // otherwise hand both to the two repair workers concurrently and they'd race
        // on the same torrent's file deletions.
        if (pgmqHealthQueueRepository.isRepairQueued(torrent.id!!)) {
            logger.debug(
                "Torrent {} '{}' is unhealthy but a repair message is already in flight",
                torrent.id, torrent.name
            )
        } else {
            logger.warn(
                "Torrent {} '{}' is unhealthy on every provider, enqueuing for repair",
                torrent.id, torrent.name
            )
            pgmqClient.send(
                "torrent_health_repair",
                TorrentHealthRepairMessage(
                    torrentId = torrent.id!!,
                    message = "Torrent unavailable on every configured debrid provider",
                    name = torrent.name,
                    category = torrent.category?.name,
                )
            )
        }
    }

    private fun isAvailable(client: DebridCachedTorrentClient, torrent: Torrent): Availability = runBlocking {
        try {
            if (client.isHealthy(torrent)) Availability.AVAILABLE else Availability.NOT_AVAILABLE
        } catch (e: RequestNotPermitted) {
            // Bubble up so the outer in-process wait/retry can fire.
            throw e
        } catch (e: DebridFileUnavailableError) {
            // The provider answered: it won't serve this torrent (DMCA, removed, not allowed).
            logger.debug("Provider {} no longer serves torrent {}: {}", client.getProvider(), torrent.id, e.message)
            Availability.NOT_AVAILABLE
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Open gate, quota, 4xx/5xx, timeout, unparseable response: no answer either way.
            logger.warn(
                "Could not ask {} about torrent {} '{}': {}: {}",
                client.getProvider(), torrent.id, torrent.name, e.javaClass.simpleName, e.message,
            )
            Availability.UNKNOWN
        }
    }

    private enum class Availability { AVAILABLE, NOT_AVAILABLE, UNKNOWN }
}
