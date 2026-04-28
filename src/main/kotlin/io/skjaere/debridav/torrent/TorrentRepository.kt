package io.skjaere.debridav.torrent

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.fs.DbEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface TorrentRepository : CrudRepository<Torrent, Long> {
    fun findByCategoryAndStatus(category: Category, status: Status): List<Torrent>

    /** Any-status lookup. Used internally for upsert / revive on re-add — callers that need
     *  to ignore soft-deleted rows should use [getByHashIgnoreCaseAndStatus] instead. */
    fun getByHashIgnoreCase(hash: String): Torrent?

    /** Status-filtered lookup. Used by qbit-emulation read paths so soft-deleted torrents
     *  are invisible to the arrs. */
    fun getByHashIgnoreCaseAndStatus(hash: String, status: Status): Torrent?

    fun findByHashIgnoreCase(hash: String): List<Torrent>

    @Transactional
    fun deleteByHashIgnoreCase(hash: String)

    @Modifying
    @Transactional
    @Query("update Torrent set status=io.skjaere.debridav.torrent.Status.DELETED where id=:#{#torrent.id}")
    fun markTorrentAsDeleted(torrent: Torrent)

    fun getTorrentByFilesContains(file: DbEntity): List<Torrent>

    fun findByStatusAndLastVerifiedIsNullOrStatusAndLastVerifiedBefore(
        status1: Status,
        status2: Status,
        cutoff: Instant
    ): List<Torrent>

    fun findByStatus(status: Status): List<Torrent>
}
