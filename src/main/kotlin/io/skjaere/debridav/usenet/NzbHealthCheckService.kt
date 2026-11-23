package io.skjaere.debridav.usenet

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.health.HealthCheckConfigurationProperties
import io.skjaere.debridav.health.PgmqHealthQueueRepository
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
    private val pgmqHealthQueueRepository: PgmqHealthQueueRepository,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(NzbHealthCheckService::class.java)

    @Scheduled(fixedDelayString = "\${health-check.nzb-poll-rate}")
    @Transactional
    fun checkNzbHealth() {
        val now = Instant.now(clock)
        val cutoff = now.minus(healthCheckConfigurationProperties.nzbInterval)

        // Dedup against BOTH queues — same shape as torrent path. Skip NZBs already
        // pending a check or being repaired so we don't pile a check message on a
        // document the repair worker is mid-processing. The repository query also
        // filters to documents with at least one NzbContents — orphans are pruned
        // by EmptyNzbDocumentCleanupTask, not verified.
        val alreadyQueued = pgmqHealthQueueRepository.queuedHealthCheckNzbIds() +
                pgmqHealthQueueRepository.queuedRepairNzbIds()
        val nzbsToVerify = nzbDocumentRepository
            .findToVerifyWithContents(cutoff)
            .filterNot { it.id in alreadyQueued }

        if (nzbsToVerify.isEmpty()) return

        logger.debug("Health check: enqueuing {} NZB document(s) for verification", nzbsToVerify.size)

        val ids = nzbsToVerify.mapNotNull { it.id }
        ids.forEach { id ->
            pgmqClient.send("nzb_health_check", NzbHealthCheckMessage(id))
        }
        nzbDocumentRepository.bulkSetHealthCheckEnqueuedAt(ids, now)
    }

    /**
     * Trigger a full sweep without ever loading the JSONB columns of every NZB document.
     * Earlier this was `findAll().forEach { … save … }` — for a library of N NZBs each
     * carrying a multi-MB `files` JSONB blob plus `streamableFiles`, that materialised the
     * entire library into heap on the trigger thread (~1 GB transient spike on a moderate
     * library). Project to ids only, skip already-queued via the queue dedup, enqueue, and
     * bulk-update `healthCheckEnqueuedAt` in a single statement.
     */
    @Transactional
    fun triggerFullHealthCheck() {
        val now = Instant.now(clock)
        val alreadyQueued = pgmqHealthQueueRepository.queuedHealthCheckNzbIds() +
                pgmqHealthQueueRepository.queuedRepairNzbIds()
        // Filter out orphans (no NzbContents referencing them) — same reasoning as the
        // scheduled path; EmptyNzbDocumentCleanupTask is responsible for pruning them.
        val candidateIds = nzbDocumentRepository.findIdsWithContents()
            .filterNot { it in alreadyQueued }

        logger.info(
            "Triggering full NZB health check: enqueuing {} document(s) ({} already in-flight, skipped)",
            candidateIds.size, alreadyQueued.size,
        )

        if (candidateIds.isEmpty()) return

        candidateIds.forEach { id ->
            pgmqClient.send("nzb_health_check", NzbHealthCheckMessage(id))
        }
        nzbDocumentRepository.bulkSetHealthCheckEnqueuedAt(candidateIds, now)
    }
}
