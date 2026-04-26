package io.skjaere.debridav.usenet

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.health.HealthCheckConfigurationProperties
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.usenet.pgmq.NzbHealthCheckMessage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class NzbHealthCheckService(
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val healthCheckConfigurationProperties: HealthCheckConfigurationProperties,
    private val pgmqClient: PgmqClient,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(NzbHealthCheckService::class.java)

    @Scheduled(fixedDelayString = "\${health-check.nzb-poll-rate}")
    @Transactional
    fun checkNzbHealth() {
        val now = Instant.now(clock)
        val cutoff = now.minus(healthCheckConfigurationProperties.nzbInterval)
        val enqueueCutoff = now.minus(healthCheckConfigurationProperties.nzbInterval)
        val nzbsToVerify = nzbDocumentRepository
            .findByLastVerifiedIsNullOrLastVerifiedBefore(cutoff)
            .filter { it.healthCheckEnqueuedAt == null || it.healthCheckEnqueuedAt!!.isBefore(enqueueCutoff) }

        if (nzbsToVerify.isEmpty()) return

        logger.debug("Health check: enqueuing {} NZB document(s) for verification", nzbsToVerify.size)

        nzbsToVerify.forEach { entity ->
            pgmqClient.send("nzb_health_check", NzbHealthCheckMessage(entity.id!!))
            entity.healthCheckEnqueuedAt = now
            nzbDocumentRepository.save(entity)
        }
    }

    fun triggerFullHealthCheck() {
        val nzbDocuments = nzbDocumentRepository.findAll()
        val now = Instant.now(clock)

        logger.info("Triggering full health check for all NZB documents")

        nzbDocuments.forEach { entity ->
            pgmqClient.send("nzb_health_check", NzbHealthCheckMessage(entity.id!!))
            entity.healthCheckEnqueuedAt = now
            nzbDocumentRepository.save(entity)
        }
    }
}
