package io.skjaere.debridav.torrent

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.health.HealthCheckConfigurationProperties
import io.skjaere.debridav.torrent.pgmq.TorrentHealthCheckMessage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class TorrentHealthCheckService(
    private val torrentRepository: TorrentRepository,
    private val healthCheckConfigurationProperties: HealthCheckConfigurationProperties,
    private val pgmqClient: PgmqClient,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(TorrentHealthCheckService::class.java)

    @Scheduled(fixedDelayString = "\${health-check.torrent-poll-rate:PT5M}")
    @Transactional
    fun checkTorrentHealth() {
        val now = Instant.now(clock)
        val cutoff = now.minus(healthCheckConfigurationProperties.torrentInterval)
        val enqueueCutoff = now.minus(healthCheckConfigurationProperties.torrentInterval)

        val torrentsToVerify = torrentRepository
            .findByStatusAndLastVerifiedIsNullOrStatusAndLastVerifiedBefore(
                Status.LIVE, Status.LIVE, cutoff
            )
            .filter {
                it.healthCheckEnqueuedAt == null || it.healthCheckEnqueuedAt!!.isBefore(enqueueCutoff)
            }

        if (torrentsToVerify.isEmpty()) return

        logger.debug("Health check: enqueuing {} torrent(s) for verification", torrentsToVerify.size)

        torrentsToVerify.forEach { torrent ->
            pgmqClient.send("torrent_health_check", TorrentHealthCheckMessage(torrent.id!!))
            torrent.healthCheckEnqueuedAt = now
            torrentRepository.save(torrent)
        }
    }

    fun triggerFullHealthCheck() {
        val torrents = torrentRepository.findByStatus(Status.LIVE)
        val now = Instant.now(clock)

        logger.info("Triggering full health check for all {} live torrents", torrents.size)

        torrents.forEach { torrent ->
            pgmqClient.send("torrent_health_check", TorrentHealthCheckMessage(torrent.id!!))
            torrent.healthCheckEnqueuedAt = now
            torrentRepository.save(torrent)
        }
    }
}
