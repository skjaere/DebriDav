package io.skjaere.debridav.torrent.pgmq

import com.vdsirotkin.pgmq.PgmqClient
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.ProviderError
import io.skjaere.debridav.health.HealthMetrics
import io.skjaere.debridav.health.HealthMetrics.CheckResult
import io.skjaere.debridav.health.HealthMetrics.HealthType
import io.skjaere.debridav.torrent.TorrentRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val RATE_LIMITED_REQUEUE_DELAY = 60.seconds

@Service
class TorrentHealthCheckHandler(
    private val torrentRepository: TorrentRepository,
    private val debridLinkService: DebridLinkService,
    private val pgmqClient: PgmqClient,
    private val clock: Clock,
    private val healthMetrics: HealthMetrics,
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

        val rateLimited = healthMetrics.timeCheck(HealthType.TORRENT) {
            try {
                val files = torrent.files
                if (files.isEmpty()) {
                    logger.debug("Torrent {} has no files, skipping health check", torrent.id)
                    healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.OK)
                    return@timeCheck false
                }

                val unhealthy = files.any { file ->
                    val contents = file.contents ?: return@any false
                    val healthyLink = runBlocking {
                        debridLinkService.getFlowOfDebridLinks(contents)
                            .firstOrNull { it !is MissingFile && it !is ProviderError }
                    }
                    val allUnavailable = healthyLink == null
                    if (allUnavailable) {
                        logger.warn(
                            "Torrent {} file '{}' is unhealthy — all providers returned MissingFile or ProviderError",
                            torrent.id, file.name
                        )
                    }
                    allUnavailable
                }

                if (unhealthy) {
                    logger.warn("Torrent {} '{}' is unhealthy, enqueuing for repair", torrent.id, torrent.name)
                    healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.MISSING)
                    pgmqClient.send(
                        "torrent_health_repair",
                        TorrentHealthRepairMessage(
                            torrentId = torrent.id!!,
                            message = "One or more files unavailable from all debrid providers"
                        )
                    )
                } else {
                    logger.debug("Torrent {} verified successfully", torrent.id)
                    healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.OK)
                }
                false
            } catch (e: RequestNotPermitted) {
                // Resilience4j rate limiter timed out waiting for a permit. Transient
                // backpressure (a full-trigger fans out hundreds of torrents past RD's
                // 249/min budget), not a real failure. Re-enqueue with a delay so the
                // next attempt has a refreshed permit budget, then return normally so
                // PgmqConsumer archives this message — re-throwing would otherwise
                // produce an ERROR-level stack trace per rate-limited message.
                logger.warn(
                    "Rate-limited verifying torrent {} '{}'; re-enqueuing in {}s",
                    torrent.id, torrent.name, RATE_LIMITED_REQUEUE_DELAY.inWholeSeconds
                )
                healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.RATE_LIMITED)
                pgmqClient.send("torrent_health_check", msg, RATE_LIMITED_REQUEUE_DELAY)
                true
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("Unexpected error verifying torrent {}", torrent.id, e)
                healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.FAILURE)
                false
            }
        }

        if (rateLimited) {
            // The delayed re-send is the in-flight marker; keep healthCheckEnqueuedAt
            // populated so the scheduler doesn't double-enqueue, and don't bump
            // lastVerified — the deferred attempt is what actually verifies.
            torrent.healthCheckEnqueuedAt = Instant.now(clock)
        } else {
            torrent.lastVerified = Instant.now(clock)
            torrent.healthCheckEnqueuedAt = null
        }
        torrentRepository.save(torrent)
    }
}
