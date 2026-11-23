package io.skjaere.debridav.usenet.queue

import java.time.Instant

data class QueueItemDto(
    val id: Long,
    val name: String,
    val status: String,
    val size: Long?,
    val errorMessage: String?,
    val updatedAt: Instant?,
    val createdAt: Instant?,
    val archiveType: String? = null,
    val files: List<NzbImportFileJson>? = null
)

data class QueueStatusResponse(
    val processing: List<QueueItemDto>,
    val pending: List<QueueItemDto>,
    val history: List<QueueItemDto>
)

data class HistoryPageResponse(
    val content: List<QueueItemDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val last: Boolean
)
