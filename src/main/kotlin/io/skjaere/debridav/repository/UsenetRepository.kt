package io.skjaere.debridav.repository

import io.skjaere.debridav.sabnzbd.UsenetDownload
import io.skjaere.debridav.sabnzbd.UsenetDownloadStatus
import jakarta.transaction.Transactional
import org.springframework.data.repository.CrudRepository

@Transactional
interface UsenetRepository : CrudRepository<UsenetDownload, Long> {
    fun findAllByStatusIn(statuses: List<UsenetDownloadStatus>): List<UsenetDownload>
    fun findAllByCompleted(completed: Boolean): MutableList<UsenetDownload>
}
