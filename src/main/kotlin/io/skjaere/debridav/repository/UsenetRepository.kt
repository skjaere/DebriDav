package io.skjaere.debridav.repository

import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.UsenetDownloadStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.transaction.annotation.Transactional

interface UsenetRepository : CrudRepository<UsenetDownload, Long> {
    fun getByName(name: String): UsenetDownload?

    @Modifying
    @Transactional
    @Query(
        "update UsenetDownload ud " +
                "set ud.status=io.skjaere.debridav.usenet.UsenetDownloadStatus.DELETED " +
                "where ud.id=:#{#usenetDownload.id}"
    )
    fun markUsenetDownloadAsDeleted(usenetDownload: UsenetDownload)

    @Transactional
    fun deleteUsenetDownloadById(id: Long)
    fun getByHash(hash: String): UsenetDownload?

    @Transactional
    fun deleteByHashIgnoreCase(hash: String)
    fun findByCategoryName(categoryName: String): List<UsenetDownload>
    fun findByNzbDocumentId(nzbDocumentId: Long): UsenetDownload?

    @Query("SELECT u FROM UsenetDownload u ORDER BY u.id DESC")
    fun findRecent(pageable: Pageable): List<UsenetDownload>

    @Query("SELECT u FROM UsenetDownload u WHERE u.category.name = :categoryName ORDER BY u.id DESC")
    fun findRecentByCategoryName(categoryName: String, pageable: Pageable): List<UsenetDownload>
}
