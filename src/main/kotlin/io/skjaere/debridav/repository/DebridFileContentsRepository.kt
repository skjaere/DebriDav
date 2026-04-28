package io.skjaere.debridav.repository

import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository

@Suppress("TooManyFunctions")
interface DebridFileContentsRepository : CrudRepository<DbEntity, Long> {
    fun findByDirectoryAndName(directory: DbDirectory, name: String): DbEntity?

    fun findAllByDirectoryInAndNameIn(directories: Collection<DbDirectory>, names: Collection<String>): List<DbEntity>

    @Query(
        "select * from db_item entity where entity.db_item_type='DbDirectory' AND entity.path = CAST(:path AS ltree)",
        nativeQuery = true
    )
    fun getDirectoryByPath(path: String): DbDirectory?

    /**
     * Race-safe get-or-create for directories. Same TOCTOU pattern as
     * [CategoryRepository.upsertByName]: concurrent torrent/NZB adds that needed the
     * same parent directory tree were each finding it missing (native query, no
     * auto-flush of other transactions' pending INSERTs) and racing to insert,
     * losing on `db_item_directory_unique_path`. The no-op DO UPDATE makes
     * RETURNING produce a row regardless.
     */
    @Transactional
    @Query(
        value = """
            INSERT INTO db_item (id, db_item_type, path, name, last_modified)
            VALUES (nextval('db_item_seq'), 'DbDirectory', CAST(:path AS ltree), :name, :lastModified)
            ON CONFLICT (path) DO UPDATE SET path = EXCLUDED.path
            RETURNING *
        """,
        nativeQuery = true,
    )
    fun upsertDirectory(path: String, name: String?, lastModified: Long): DbDirectory

    fun getByDirectory(directory: DbDirectory): List<DbEntity>

    @Query(
        "select * from db_item directory where directory.path ~ CAST(CONCAT(:#{#directory.path},'.*{1}') AS lquery)",
        nativeQuery = true
    )
    fun getChildrenByDirectory(directory: DbDirectory): List<DbDirectory>

    @Modifying
    @Transactional
    @Query(
        "update db_item set path = CAST(:destinationPath AS ltree) " +
                "|| subpath(path, nlevel(CAST(:#{#directory.path} AS ltree))-1) " +
                "where path <@ CAST(:#{#directory.path} AS ltree)", nativeQuery = true
    )
    fun moveDirectory(directory: DbDirectory, destinationPath: String)

    @Modifying
    @Transactional
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

    @Modifying
    @Transactional
    @Query("delete from torrent_files tf where tf.files_id = :#{#file.id}", nativeQuery = true)
    fun unlinkFileFromTorrents(file: DbEntity)

    /**
     * Delete every Torrent row that no longer has any entries in the torrent_files join.
     *
     * Called from the file-deletion path right after [unlinkFileFromTorrents] so that the
     * Torrent row is removed atomically with its last file going away — natural lifecycle:
     * while files referencing the magnet exist we keep the Torrent (LIVE or soft-DELETED)
     * as the audit + repair anchor; once nothing references it any more there's no point
     * keeping the row around.
     *
     * This is a sweep, not scoped to a particular torrent_id. Cost = O(empty torrents).
     * On the deploy that introduces this, it'll prune the 13 zero-file LIVE torrents that
     * had accumulated; in steady state it touches at most one row per file deletion.
     */
    @Modifying
    @Transactional
    @Query(
        "DELETE FROM torrent " +
                "USING torrent t " +
                "LEFT JOIN torrent_files tf ON tf.torrent_id = t.id " +
                "WHERE torrent.id = t.id AND tf.torrent_id IS NULL",
        nativeQuery = true,
    )
    fun deleteEmptyTorrents(): Int

    @Modifying
    @Transactional
    @Query("delete from usenet_download_debrid_files tf where tf.debrid_files_id = :#{#file.id}", nativeQuery = true)
    fun unlinkFileFromUsenet(file: DbEntity)

    @Modifying
    @Query("delete from RemotelyCachedEntity rce where rce.hash = :hash")
    @Transactional
    fun deleteDbEntityByHash(hash: String)

    @Query("select rce from RemotelyCachedEntity rce where lower(rce.hash) = lower(:hash)")
    fun getByHash(hash: String): List<DbEntity>

    @Query(
        """
        select  jsonb_path_query(debrid_links, '$[*].provider') as provider,
                jsonb_path_query(debrid_links, '$[*].\@type') as type,
                count(*) as count
        from debrid_cached_torrent_content group by provider, type;
    """, nativeQuery = true
    )
    fun getLibraryMetricsTorrents(): List<Map<String, Any>>

    @Query(
        """
        select  jsonb_path_query(debrid_links, '$[*].provider') as provider,
                jsonb_path_query(debrid_links, '$[*].\@type') as type,
                count(*) as count
        from debrid_cached_usenet_release_content group by provider, type;
    """, nativeQuery = true
    )
    fun getLibraryMetricsUsenet(): List<Map<String, Any>>

    @Query("select count(*) from DebridCachedTorrentContent ")
    fun numberOfRemotelyCachedTorrentEntities(): Long

    @Query("select count(*) from DebridCachedUsenetReleaseContent ")
    fun numberOfRemotelyCachedUsenetEntities(): Long

    @Query(
        "select rce.* from db_item rce " +
                "join usenet_download_debrid_files udf on udf.debrid_files_id = rce.id " +
                "where udf.usenet_download_id = :usenetDownloadId",
        nativeQuery = true
    )
    fun findByUsenetDownloadId(usenetDownloadId: Long): List<RemotelyCachedEntity>
}

data class LibraryStats(val provider: String, val type: String, val count: Long)
