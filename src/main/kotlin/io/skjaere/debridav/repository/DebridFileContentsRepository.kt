package io.skjaere.debridav.repository

import io.skjaere.debridav.fs.databasefs.DbItem
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository

@Transactional
interface DebridFileContentsRepository : CrudRepository<DbItem, Long> {
    fun findByPath(path: String): DbItem?

    fun findAllByParentPath(path: String): List<DbItem>?

    @Query("select dufc.id from DebridUsenetContentsDTO dufc where dufc.nzbFileLocation=:nzbFileLocation")
    fun findAllDebridFilesByNzbFilePath(nzbFileLocation: String): List<Long>

    @Query(
        "select dbf.id from DebridTorrentContentsDTO as c " +
                "inner join DbFile dbf on dbf.contents.id=c.id " +
                "where c.magnet=:magnet"
    )
    fun findFileIdsByMagnet(magnet: String): List<Long>

    @Query(
        "select dbf.id from DebridUsenetContentsDTO as c " +
                "inner join DbFile dbf on dbf.contents.id=c.id where c.hash=:hash"
    )
    fun findFileIdsByHash(hash: String): List<Long>

    @Transactional
    fun deleteByPath(path: String)
}
