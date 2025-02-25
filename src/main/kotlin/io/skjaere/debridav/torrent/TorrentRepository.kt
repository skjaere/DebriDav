package io.skjaere.debridav.torrent

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.fs.DbEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TorrentRepository : CrudRepository<Torrent, Long> {
    fun findByCategoryAndStatus(category: Category, status: Status): List<Torrent>
    fun getByHash(hash: String): Torrent?

    @Modifying
    @Query("update Torrent set status=io.skjaere.debridav.torrent.Status.DELETED where id=:#{#torrent.id}")
    fun markTorrentAsDeleted(torrent: Torrent)

    fun getTorrentByFilesContains(file: DbEntity): List<Torrent>
}
