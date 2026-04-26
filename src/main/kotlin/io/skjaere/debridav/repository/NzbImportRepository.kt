package io.skjaere.debridav.repository

import io.skjaere.debridav.usenet.queue.NzbImportRecord
import io.skjaere.debridav.usenet.queue.NzbImportStatus
import jakarta.transaction.Transactional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

@Transactional
interface NzbImportRepository : JpaRepository<NzbImportRecord, Long> {
    fun findByStatusInOrderByUpdatedAtDesc(statuses: Collection<NzbImportStatus>): List<NzbImportRecord>
    fun findByStatusInOrderByIdAsc(statuses: Collection<NzbImportStatus>): List<NzbImportRecord>

    @Query(
        "SELECT r FROM NzbImportRecord r WHERE r.status IN :statuses " +
            "AND LOWER(r.name) LIKE LOWER(CONCAT('%', :search, '%'))"
    )
    fun findByStatusInAndNameSearch(
        statuses: Collection<NzbImportStatus>,
        search: String,
        pageable: Pageable
    ): Page<NzbImportRecord>
}
