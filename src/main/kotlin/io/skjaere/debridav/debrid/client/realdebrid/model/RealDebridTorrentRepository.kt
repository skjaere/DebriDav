package io.skjaere.debridav.debrid.client.realdebrid.model

import org.springframework.data.repository.CrudRepository
import org.springframework.transaction.annotation.Transactional

interface RealDebridTorrentRepository : CrudRepository<RealDebridTorrentEntity, Long> {
    fun findTorrentsByHashIgnoreCase(hash: String): List<RealDebridTorrentEntity>
    fun getByTorrentIdIgnoreCase(torrentId: String): RealDebridTorrentEntity?

    @Transactional
    fun deleteByTorrentIdIgnoreCase(torrentId: String)
}
