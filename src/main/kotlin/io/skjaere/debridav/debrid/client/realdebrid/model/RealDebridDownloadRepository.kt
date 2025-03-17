package io.skjaere.debridav.debrid.client.realdebrid.model

import org.springframework.data.repository.CrudRepository

interface RealDebridDownloadRepository : CrudRepository<RealDebridDownloadEntity, Long> {
    fun getByDownloadId(downloadId: String): RealDebridDownloadEntity?
    fun findAllByLinkIsInIgnoreCase(links: Set<String>): Set<RealDebridDownloadEntity>
}
