package io.skjaere.debridav.health

import java.time.Instant

data class HealthQueueItemDto(
    val msgId: Long,
    val documentId: Long,
    val name: String?,
    val category: String?,
    val type: String,
    val readCount: Int,
    val enqueuedAt: Instant?,
    val lastReadAt: Instant?,
    val archivedAt: Instant?,
    val message: String?,
    val action: String? = null
)

data class HealthQueueStatusResponse(
    val pending: List<HealthQueueItemDto>,
    val count: Int
)

data class HealthQueueHistoryResponse(
    val content: List<HealthQueueItemDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val last: Boolean
)
