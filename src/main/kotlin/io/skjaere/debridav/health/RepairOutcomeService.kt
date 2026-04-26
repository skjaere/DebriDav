package io.skjaere.debridav.health

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class RepairOutcomeService(
    private val repository: RepairOutcomeRepository
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(queueName: String, msgId: Long, action: RepairAction) {
        val outcome = RepairOutcome()
        outcome.queueName = queueName
        outcome.msgId = msgId
        outcome.action = action
        repository.save(outcome)
    }
}
