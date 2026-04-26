package io.skjaere.debridav.health

import org.springframework.stereotype.Service

@Service
class HealthQueueService(private val repository: PgmqHealthQueueRepository) {

    fun getHealthCheckStatus(): HealthQueueStatusResponse {
        val pending = repository.getPendingHealthChecks()
        return HealthQueueStatusResponse(pending = pending, count = pending.size)
    }

    fun getRepairStatus(): HealthQueueStatusResponse {
        val pending = repository.getPendingRepairs()
        return HealthQueueStatusResponse(pending = pending, count = pending.size)
    }

    fun getHealthCheckHistory(page: Int, size: Int, search: String): HealthQueueHistoryResponse =
        repository.getHealthCheckHistory(page, size, search)

    fun getRepairHistory(page: Int, size: Int, search: String): HealthQueueHistoryResponse =
        repository.getRepairHistory(page, size, search)
}
