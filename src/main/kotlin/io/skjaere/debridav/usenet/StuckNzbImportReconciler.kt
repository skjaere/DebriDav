package io.skjaere.debridav.usenet

import io.skjaere.debridav.health.PgmqHealthQueueRepository
import io.skjaere.debridav.pgmq.PgmqConfigurationProperties
import io.skjaere.debridav.repository.NzbImportRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.queue.NzbImportStatus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

/**
 * Fails NZB imports stuck in QUEUED or IMPORTING with no message left to process them: queued
 * by a send that failed after its transaction committed, or abandoned some other way the
 * dead-letter hook never saw. Without this the UI shows "processing" forever, and Sonarr/Radarr
 * never see a failure, so they never search again. An import still has a message while it is
 * waiting, running or between retries, so only one past the whole retry window
 * (maxReadCount × importVisibilityTimeout + importTimeout) without a message is failed.
 */
@Component
class StuckNzbImportReconciler(
    private val nzbImportRepository: NzbImportRepository,
    private val usenetRepository: UsenetRepository,
    private val pgmqHealthQueueRepository: PgmqHealthQueueRepository,
    private val pgmqConfigurationProperties: PgmqConfigurationProperties,
    platformTransactionManager: PlatformTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(StuckNzbImportReconciler::class.java)
    private val transactionTemplate = TransactionTemplate(platformTransactionManager)

    @Scheduled(fixedDelayString = "\${pgmq.stuck-import-check-rate:PT10M}")
    fun failStuckImports() {
        val window = retryWindow()
        val queued = pgmqHealthQueueRepository.queuedImportRecordIds()
        val stuck = nzbImportRepository
            .findByStatusInAndUpdatedAtBefore(IN_PROGRESS, Instant.now().minus(window))
            .filterNot { it.id in queued }
        stuck.forEach { record ->
            transactionTemplate.executeWithoutResult {
                val reason = "No progress for ${window.toMinutes()} minutes and no import queued"
                logger.warn("Failing stuck NZB import {} '{}': {}", record.id, record.name, reason)
                record.status = NzbImportStatus.FAILED
                record.errorMessage = reason
                nzbImportRepository.save(record)
                record.usenetDownloadId?.let { id ->
                    usenetRepository.findById(id).ifPresent {
                        it.status = UsenetDownloadStatus.FAILED
                        usenetRepository.save(it)
                    }
                }
            }
        }
    }

    private fun retryWindow(): Duration = pgmqConfigurationProperties.importVisibilityTimeout
        .multipliedBy(pgmqConfigurationProperties.maxReadCount)
        .plus(pgmqConfigurationProperties.importTimeout)

    private companion object {
        val IN_PROGRESS = listOf(NzbImportStatus.QUEUED, NzbImportStatus.IMPORTING)
    }
}
