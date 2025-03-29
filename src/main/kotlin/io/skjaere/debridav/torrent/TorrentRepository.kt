package io.skjaere.debridav.torrent

import io.skjaere.debridav.category.Category
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TorrentRepository : CrudRepository<Torrent, Long> {
    fun findByCategoryAndStatus(category: Category, status: Status): List<Torrent>
    fun getByHashIgnoreCase(hash: String): Torrent?
    fun deleteByHashIgnoreCase(hash: String)
}
