package io.skjaere.debridav.torrent

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.DebridCachedTorrentClient
import io.skjaere.debridav.health.HealthCheckConfigurationProperties
import io.skjaere.debridav.health.PgmqHealthQueueRepository
import io.skjaere.debridav.torrent.pgmq.TorrentHealthCheckMessage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
@Suppress("LongParameterList")
class TorrentHealthCheckService(
    private val torrentRepository: TorrentRepository,
    private val healthCheckConfigurationProperties: HealthCheckConfigurationProperties,
    private val pgmqClient: PgmqClient,
    private val pgmqHealthQueueRepository: PgmqHealthQueueRepository,
    private val debridClients: List<DebridCachedTorrentClient>,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(TorrentHealthCheckService::class.java)

    @Scheduled(fixedDelayString = "\${health-check.torrent-poll-rate:PT5M}")
    @Transactional
    fun checkTorrentHealth() {
        val now = Instant.now(clock)
        val cutoff = now.minus(healthCheckConfigurationProperties.torrentInterval)

        // Dedup against BOTH queues: skip torrents that are either already pending a check
        // OR currently being repaired. Enqueueing a check while a repair is in flight would
        // race the repair worker on the same torrent's files (cascade-deletes vs. lazy-load
        // contents in the check handler) — exactly the StaleObjectStateException pattern we
        // saw earlier. Replaces the older healthCheckEnqueuedAt timestamp filter.
        val alreadyQueued = pgmqHealthQueueRepository.queuedHealthCheckTorrentIds() +
                pgmqHealthQueueRepository.queuedRepairTorrentIds()
        val torrentsToVerify = torrentRepository
            .findByLastVerifiedIsNullOrLastVerifiedBefore(cutoff)
            .filterNot { it.id in alreadyQueued }

        if (torrentsToVerify.isEmpty()) return

        logger.debug("Health check: enqueuing {} torrent(s) for verification", torrentsToVerify.size)

        torrentsToVerify.forEach { torrent ->
            pgmqClient.send("torrent_health_check", TorrentHealthCheckMessage(torrent.id!!))
            torrent.healthCheckEnqueuedAt = now
            torrentRepository.save(torrent)
        }
    }

    fun triggerFullHealthCheck() {
        // Give every enabled provider a chance to refresh whatever local state isHealthy()
        // depends on before we fan out the sweep. RD syncs /torrents/ → mirror; TorBox/
        // Premiumize default to a no-op since their isCached hits the API directly.
        // Run in parallel — each provider's prep is independent.
        runBlocking {
            coroutineScope {
                debridavConfigurationProperties.debridClients.forEach { provider ->
                    val client = debridClients.firstOrNull { it.getProvider() == provider }
                    if (client != null) {
                        launch {
                            runCatching { client.prepareHealthCheck() }
                                .onFailure { e ->
                                    logger.warn(
                                        "prepareHealthCheck failed for {}: {}",
                                        provider, e.message,
                                    )
                                }
                        }
                    }
                }
            }
        }

        // Skip torrents that already have a message in flight on EITHER the check or
        // repair queue. Re-enqueueing a check for a torrent currently being repaired would
        // race the repair worker on the same files. The queue dedup is exact and self-healing.
        val alreadyQueued = pgmqHealthQueueRepository.queuedHealthCheckTorrentIds() +
                pgmqHealthQueueRepository.queuedRepairTorrentIds()
        val torrents = torrentRepository.findAll().toList()
        val candidates = torrents.filterNot { it.id in alreadyQueued }
        val now = Instant.now(clock)

        logger.info(
            "Triggering full health check: enqueuing {} torrent(s) ({} already in-flight, skipped)",
            candidates.size, torrents.size - candidates.size,
        )

        candidates.forEach { torrent ->
            pgmqClient.send("torrent_health_check", TorrentHealthCheckMessage(torrent.id!!))
            torrent.healthCheckEnqueuedAt = now
            torrentRepository.save(torrent)
        }
    }
}
