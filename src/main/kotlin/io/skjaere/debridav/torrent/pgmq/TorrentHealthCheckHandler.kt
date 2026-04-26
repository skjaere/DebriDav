package io.skjaere.debridav.torrent.pgmq

import com.vdsirotkin.pgmq.PgmqClient
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

        healthMetrics.timeCheck(HealthType.TORRENT) {
            try {
                val files = torrent.files
                if (files.isEmpty()) {
                    logger.debug("Torrent {} has no files, skipping health check", torrent.id)
                    healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.OK)
                    return@timeCheck
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
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("Unexpected error verifying torrent {}", torrent.id, e)
                healthMetrics.recordCheck(HealthType.TORRENT, CheckResult.FAILURE)
            }
        }

        torrent.lastVerified = Instant.now(clock)
        torrent.healthCheckEnqueuedAt = null
        torrentRepository.save(torrent)
    }
}
