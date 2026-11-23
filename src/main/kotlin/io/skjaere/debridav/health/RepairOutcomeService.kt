package io.skjaere.debridav.health

import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class RepairOutcomeService(
    private val repository: RepairOutcomeRepository,
    private val healthCheckConfig: HealthCheckConfigurationProperties,
) {
    /**
     * True when health-check.max-repairs-per-hour torrent and NZB repairs (replaced in an arr, or
     * deleted) already ran in the past hour. The limit contains a systematic false negative, such
     * as a misconfiguration during the first sweep after an upgrade, to a few repairs an hour
     * instead of the whole library. Concurrent repair workers can each pass the check, so the limit
     * can be exceeded by the number of workers.
     */
    fun isHourlyRepairLimitReached(): Boolean {
        val limit = healthCheckConfig.maxRepairsPerHour
        if (limit <= 0) return false
        val since = Instant.now().minus(Duration.ofHours(1))
        return repository.countByActionInAndCreatedAtAfter(ACTING_REPAIRS, since) >= limit
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(queueName: String, msgId: Long, action: RepairAction) {
        val outcome = RepairOutcome()
        outcome.queueName = queueName
        outcome.msgId = msgId
        outcome.action = action
        repository.save(outcome)
    }

    private companion object {
        val ACTING_REPAIRS = setOf(RepairAction.REPAIRED, RepairAction.DELETED)
    }
}
