package io.skjaere.debridav.usenet.pgmq

import io.skjaere.debridav.pgmq.PgmqConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

@Service
class PgmqArchiveCleanupService(
    private val jdbc: JdbcTemplate,
    private val props: PgmqConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(PgmqArchiveCleanupService::class.java)

    companion object {
        private val MAIN_QUEUES = listOf(
            "nzb_import", "nzb_health_check", "nzb_health_repair",
            "torrent_health_check", "torrent_health_repair"
        )
        private val DEAD_LETTER_QUEUES = MAIN_QUEUES.map { "${it}_dlq" }
        private val ALL_QUEUES = MAIN_QUEUES + DEAD_LETTER_QUEUES
    }

    /** Drops rows from `pgmq.a_<queue>` (archive tables) older than [archiveRetention]. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    fun cleanupArchivedMessages() {
        val cutoff = Instant.now().minus(props.archiveRetention)
        logger.debug("Cleaning up archived PGMQ messages older than {}", cutoff)

        var totalDeleted = 0L
        for (queueName in ALL_QUEUES) {
            val deleted = jdbc.update(
                "DELETE FROM pgmq.a_$queueName WHERE archived_at < ?",
                Timestamp.from(cutoff)
            )
            if (deleted > 0) {
                logger.info("Deleted {} archived messages from queue '{}'", deleted, queueName)
                totalDeleted += deleted
            }
        }

        if (totalDeleted > 0) {
            logger.info("Archive cleanup complete: {} total messages removed", totalDeleted)
        } else {
            logger.debug("Archive cleanup complete: no expired messages found")
        }
    }

    /**
     * Ages out live dead-letter messages: anything sitting in a `*_dlq` queue beyond
     * [deadLetterRetention] gets archived so the existing [cleanupArchivedMessages] loop
     * can eventually reap it. Gives operators a retention window to investigate poison
     * payloads without letting the DLQ grow unbounded.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    fun archiveStaleDeadLetterMessages() {
        val cutoff = Instant.now().minus(props.deadLetterRetention)
        logger.debug("Archiving dead-letter messages enqueued before {}", cutoff)

        var totalArchived = 0L
        for (queueName in DEAD_LETTER_QUEUES) {
            val archived = jdbc.query(
                "SELECT pgmq.archive(?, msg_id) AS archived FROM pgmq.q_$queueName WHERE enqueued_at < ?",
                { rs, _ -> rs.getBoolean("archived") },
                queueName, Timestamp.from(cutoff)
            ).count { it }
            if (archived > 0) {
                logger.info("Archived {} stale dead-letter messages from queue '{}'", archived, queueName)
                totalArchived += archived
            }
        }

        if (totalArchived > 0) {
            logger.info("Dead-letter archive complete: {} total messages moved to archive", totalArchived)
        }
    }
}
