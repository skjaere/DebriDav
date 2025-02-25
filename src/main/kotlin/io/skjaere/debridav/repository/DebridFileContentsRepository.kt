package io.skjaere.debridav.repository

import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository

@Transactional
interface DebridFileContentsRepository : CrudRepository<DbEntity, Long> {
    fun findByDirectoryAndName(directory: DbDirectory, name: String): DbEntity?

    @Query(
        "select * from db_item entity where entity.db_item_type='DbDirectory' AND entity.path = CAST(:path AS ltree)",
        nativeQuery = true
    )
    fun getDirectoryByPath(path: String): DbDirectory?

    fun getByDirectory(directory: DbDirectory): List<DbEntity>

    @Query(
        "select * from db_item directory where directory.path ~ CAST(CONCAT(:#{#directory.path},'.*{1}') AS lquery)",
        nativeQuery = true
    )
    fun getChildrenByDirectory(directory: DbDirectory): List<DbDirectory>

    @Modifying
    @Query(
        "update db_item set path = CAST(:destinationPath AS ltree) " +
                "|| subpath(path, nlevel(CAST(:#{#directory.path} AS ltree))-1) " +
                "where path <@ CAST(:#{#directory.path} AS ltree)", nativeQuery = true
    )
    fun moveDirectory(directory: DbDirectory, destinationPath: String)

    @Modifying
    @Query(
        """
            UPDATE db_item 
            set path =
                CASE 
                    WHEN nlevel(path) != nlevel(CAST(:directoryPath as ltree)) THEN subltree(CAST(:directoryPath as ltree), 0, nlevel(CAST(:directoryPath as ltree))-1) || CAST(:encodedNewName AS ltree)  || subpath(path, nlevel(CAST(:directoryPath as ltree)))
                    WHEN nlevel(path) = nlevel(CAST(:directoryPath as ltree)) THEN subltree(CAST(:directoryPath as ltree), 0, nlevel(CAST(:directoryPath as ltree))-1) || CAST(:encodedNewName AS ltree)
                END,
                name = :readableNewName
            where path <@ CAST(:directoryPath as ltree);
            
        """, nativeQuery = true
    )
    fun renameDirectory(directoryPath: String, encodedNewName: String, readableNewName: String)

    @Query(
        "select dbitem.* from debrid_cached_torrent_content ct " +
                "inner join db_item dbitem on dbitem.debrid_file_contents_id = ct.id" +
                "where ct.magnet = :magnet", nativeQuery = true
    )
    fun findFilesByMagnet(magnet: String): List<Long>

    @Modifying
    @Query("delete from torrent_files tf where tf.files_id = :#{#file.id}", nativeQuery = true)
    fun unlinkFileFromTorrents(file: DbEntity)

    @Modifying
    @Query("delete from usenet_download_debrid_files tf where tf.debrid_files_id = :#{#file.id}", nativeQuery = true)
    fun unlinkFileFromUsenet(file: DbEntity)

    @Modifying
    @Query(
        """
           SELECT files.* FROM db_item dir
           inner join db_item files on dir.id=files.directory_id
           WHERE dir.path <@ CAST(:#{#directory.path} AS ltree) OR file.id=dir.id
        """, nativeQuery = true
    )
    fun getEntitiesInBranch(directory: DbDirectory): List<DbEntity>
}
