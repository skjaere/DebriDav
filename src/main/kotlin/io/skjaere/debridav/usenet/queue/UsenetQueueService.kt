package io.skjaere.debridav.usenet.queue

import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.repository.NzbImportRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service

@Service
class UsenetQueueService(
    private val nzbImportRepository: NzbImportRepository,
    private val debridFileContentsRepository: DebridFileContentsRepository
) {

    companion object {
        val PROCESSING_STATUSES = listOf(
            NzbImportStatus.IMPORTING
        )

        val PENDING_STATUSES = listOf(
            NzbImportStatus.QUEUED
        )

        val HISTORY_STATUSES = listOf(
            NzbImportStatus.COMPLETED,
            NzbImportStatus.FAILED
        )

        private val ALLOWED_SORT_FIELDS = setOf("updatedAt", "name")
    }

    fun getQueueStatus(): QueueStatusResponse {
        val processing = nzbImportRepository.findByStatusInOrderByUpdatedAtDesc(PROCESSING_STATUSES)
            .map { it.toDto() }
        val pending = nzbImportRepository.findByStatusInOrderByIdAsc(PENDING_STATUSES)
            .map { it.toDto() }

        return QueueStatusResponse(
            processing = processing,
            pending = pending,
            history = emptyList()
        )
    }

    fun getHistory(page: Int, size: Int, search: String, sort: String, direction: String): HistoryPageResponse {
        val sortField = if (sort in ALLOWED_SORT_FIELDS) sort else "updatedAt"
        val sortDir = if (direction.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC
        val pageResult = nzbImportRepository.findByStatusInAndNameSearch(
            HISTORY_STATUSES,
            search,
            PageRequest.of(page, size, Sort.by(sortDir, sortField))
        )
        return HistoryPageResponse(
            content = pageResult.content.map { it.toDto() },
            page = pageResult.number,
            size = pageResult.size,
            totalElements = pageResult.totalElements,
            totalPages = pageResult.totalPages,
            last = pageResult.isLast
        )
    }

    @Suppress("ReturnCount")
    fun resolveCurrentFilePaths(importId: Long): List<NzbImportFileJson>? {
        val record = nzbImportRepository.findById(importId).orElse(null) ?: return null
        val usenetDownloadId = record.usenetDownloadId ?: return record.files
        val dbItems = debridFileContentsRepository.findByUsenetDownloadId(usenetDownloadId)
        if (dbItems.isEmpty()) return record.files
        val currentFiles = dbItems.mapNotNull { entity ->
            val dirPath = entity.directory?.fileSystemPath() ?: return@mapNotNull null
            val fileName = entity.name ?: return@mapNotNull null
            NzbImportFileJson(
                path = "$dirPath/$fileName",
                size = entity.size ?: 0L
            )
        }
        return currentFiles.ifEmpty { record.files }
    }

    private fun NzbImportRecord.toDto(): QueueItemDto {
        return QueueItemDto(
            id = id!!,
            name = name,
            status = status.name,
            size = size,
            errorMessage = errorMessage,
            updatedAt = updatedAt,
            createdAt = createdAt,
            archiveType = archiveType,
            files = files
        )
    }
}
