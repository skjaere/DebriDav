package io.skjaere.debridav.repository

import io.skjaere.debridav.usenet.UsenetDownload
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository

@Transactional
interface UsenetRepository : CrudRepository<UsenetDownload, Long> {
    fun getByName(name: String): UsenetDownload?

    @Modifying
    @Query(
        "update UsenetDownload ud " +
                "set ud.status=io.skjaere.debridav.usenet.UsenetDownloadStatus.DELETED " +
                "where ud.id=:#{#usenetDownload.id}"
    )
    fun markUsenetDownloadAsDeleted(usenetDownload: UsenetDownload)

    fun deleteUsenetDownloadById(id: Long)
    fun getByHash(hash: String): UsenetDownload?
    fun deleteByHashIgnoreCase(hash: String)
}
