package io.skjaere.debridav.usenet.pgmq

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.health.HealthMetrics
import io.skjaere.debridav.health.HealthMetrics.CheckResult
import io.skjaere.debridav.health.HealthMetrics.HealthType
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.usenet.nzb.toNzbDocument
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.enrichment.VerificationResult
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class NzbHealthCheckHandler(
    private val nzbStreamer: NzbStreamer,
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val pgmqClient: PgmqClient,
    private val clock: Clock,
    private val healthMetrics: HealthMetrics,
) {
    private val logger = LoggerFactory.getLogger(NzbHealthCheckHandler::class.java)

    @Transactional
    fun handle(msg: NzbHealthCheckMessage) {
        val entity = nzbDocumentRepository.findById(msg.nzbDocumentId).orElse(null)
        if (entity == null) {
            logger.warn("NZB document {} not found, skipping health check", msg.nzbDocumentId)
            healthMetrics.recordCheck(HealthType.NZB, CheckResult.NOT_FOUND)
            return
        }

        healthMetrics.timeCheck(HealthType.NZB) {
            try {
                val nzbDocument = entity.toNzbDocument()
                when (val result = runBlocking { nzbStreamer.verifySegments(nzbDocument) }) {
                    is VerificationResult.Success -> {
                        logger.debug("NZB document {} verified successfully", entity.id)
                        healthMetrics.recordCheck(HealthType.NZB, CheckResult.OK)
                    }

                    is VerificationResult.MissingArticles -> {
                        logger.warn(
                            "NZB document {} has missing articles: {}",
                            entity.id,
                            result.message
                        )
                        healthMetrics.recordCheck(HealthType.NZB, CheckResult.MISSING)
                        pgmqClient.send(
                            "nzb_health_repair",
                            NzbHealthRepairMessage(
                                nzbDocumentId = entity.id!!,
                                message = result.message
                            )
                        )
                    }

                    is VerificationResult.Failure -> {
                        logger.error(
                            "NZB document {} verification failed: {}",
                            entity.id,
                            result.message,
                            result.cause
                        )
                        healthMetrics.recordCheck(HealthType.NZB, CheckResult.FAILURE)
                    }
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("Unexpected error verifying NZB document {}", entity.id, e)
                healthMetrics.recordCheck(HealthType.NZB, CheckResult.FAILURE)
            }
        }

        entity.lastVerified = Instant.now(clock)
        entity.healthCheckEnqueuedAt = null
        nzbDocumentRepository.save(entity)
    }
}
