package io.skjaere.debridav.pgmq

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Polls PGMQ's `pgmq.metrics_all()` view once per [REFRESH_INTERVAL] and publishes
 * queue-depth + message-age gauges per queue. Used by the health dashboard to
 * alert on backlog and DLQ fill-up.
 */
@Service
class PgmqMetricsService(
    private val jdbc: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(PgmqMetricsService::class.java)

    private val queueLengthGauge = MultiGauge
        .builder("debridav.pgmq.queue.length")
        .description("Number of visible messages in the PGMQ queue")
        .register(meterRegistry)

    private val oldestMsgAgeGauge = MultiGauge
        .builder("debridav.pgmq.oldest.message.age.seconds")
        .description("Age of the oldest visible message in the queue")
        .register(meterRegistry)

    private val totalMessagesGauge = MultiGauge
        .builder("debridav.pgmq.messages.total")
        .description("Total messages (visible + invisible) on the queue")
        .register(meterRegistry)

    @Scheduled(fixedDelayString = REFRESH_INTERVAL, initialDelayString = "PT15S")
    fun refresh() {
        val rows = try {
            jdbc.query(
                """
                SELECT queue_name,
                       queue_length,
                       COALESCE(oldest_msg_age_sec, 0) AS oldest_msg_age_sec,
                       total_messages
                FROM pgmq.metrics_all()
                """.trimIndent()
            ) { rs, _ ->
                QueueMetrics(
                    queueName = rs.getString("queue_name"),
                    queueLength = rs.getLong("queue_length"),
                    oldestMsgAgeSec = rs.getLong("oldest_msg_age_sec"),
                    totalMessages = rs.getLong("total_messages"),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn("Failed to fetch pgmq metrics: {}", e.message)
            return
        }

        queueLengthGauge.register(
            rows.map { MultiGauge.Row.of(Tags.of("queue", it.queueName), it.queueLength.toDouble()) },
            true
        )
        oldestMsgAgeGauge.register(
            rows.map { MultiGauge.Row.of(Tags.of("queue", it.queueName), it.oldestMsgAgeSec.toDouble()) },
            true
        )
        totalMessagesGauge.register(
            rows.map { MultiGauge.Row.of(Tags.of("queue", it.queueName), it.totalMessages.toDouble()) },
            true
        )
    }

    private data class QueueMetrics(
        val queueName: String,
        val queueLength: Long,
        val oldestMsgAgeSec: Long,
        val totalMessages: Long,
    )

    companion object {
        private const val REFRESH_INTERVAL = "PT30S"
    }
}
