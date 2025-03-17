package io.skjaere.debridav.debrid.client.realdebrid.model

import org.springframework.data.repository.CrudRepository

interface RealDebridTorrentRepository : CrudRepository<RealDebridTorrentEntity, Long> {
    fun findTorrentsByHashIgnoreCase(hash: String): List<RealDebridTorrentEntity>
    fun getByTorrentIdIgnoreCase(torrentId: String): RealDebridTorrentEntity?
    fun deleteByTorrentIdIgnoreCase(torrentId: String)
}
