package io.skjaere.debridav.debrid.client.realdebrid.model

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository

interface RealDebridDownloadRepository : CrudRepository<RealDebridDownloadEntity, Long> {
    fun getByDownloadIdIgnoreCase(downloadId: String): RealDebridDownloadEntity?
    fun findAllByLinkIsInIgnoreCase(links: Set<String>): Set<RealDebridDownloadEntity>
    fun getDownloadByLinkIgnoreCase(link: String): RealDebridDownloadEntity?

    @Query(
        """
        select downloads.* from real_debrid_download_entity downloads
            left join real_debrid_torrent_entity_links torrent_link on downloads.link=torrent_link.links
            left join real_debrid_torrent_entity torrents on torrents.id=torrent_link.real_debrid_torrent_entity_id
            where downloads.filename = :filename
            and downloads.file_size = :size
            and torrents.hash = :hash
    """, nativeQuery = true
    )
    fun getDownloadByHashAndFilenameAndSize(filename: String, size: Long, hash: String): RealDebridDownloadEntity?
}
