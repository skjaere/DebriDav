package io.skjaere.debridav.repository

import io.skjaere.debridav.qbittorrent.Category
import io.skjaere.debridav.qbittorrent.Torrent
import org.springframework.data.repository.CrudRepository
import java.time.Instant

interface TorrentRepository : CrudRepository<Torrent, Long> {
    fun findByCategory(category: Category): List<Torrent>
    fun getByHash(hash: String): Torrent?
    fun getAllByCreatedBefore(before: Instant): List<Torrent>
}
