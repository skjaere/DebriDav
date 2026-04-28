package io.skjaere.debridav.test.support

import io.skjaere.debridav.fs.DbEntity
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository

/**
 * Test-only bulk-cleanup queries used by [io.skjaere.debridav.test.integrationtest.TorrentAddBurstIT]
 * to wipe a directory subtree quickly between runs. Deliberately not in
 * `src/main/kotlin/io/skjaere/debridav/repository` — production has no use case for these and
 * doesn't need to expose them on the public repository surface. Lives under `src/test/kotlin`
 * so Spring Data only picks it up during the test classpath scan.
 *
 * The five queries collaborate to delete a whole directory subtree in five native statements:
 * each subtype-content table → file rows → directory rows. `DebridFileContents` uses
 * TABLE_PER_CLASS inheritance, so contents live in three separate subtype tables with no
 * shared parent table.
 */
interface SubtreeCleanupRepository : CrudRepository<DbEntity, Long> {

    @Modifying
    @Transactional
    @Query(
        """
            DELETE FROM debrid_cached_torrent_content
            WHERE id IN (
                SELECT debrid_file_contents_id FROM db_item
                WHERE directory_id IN (SELECT id FROM db_item WHERE path <@ CAST(:directoryPath AS ltree))
                AND debrid_file_contents_id IS NOT NULL
            )
        """,
        nativeQuery = true,
    )
    fun deleteTorrentContentsUnderDirectory(directoryPath: String)

    @Modifying
    @Transactional
    @Query(
        """
            DELETE FROM debrid_cached_usenet_release_content
            WHERE id IN (
                SELECT debrid_file_contents_id FROM db_item
                WHERE directory_id IN (SELECT id FROM db_item WHERE path <@ CAST(:directoryPath AS ltree))
                AND debrid_file_contents_id IS NOT NULL
            )
        """,
        nativeQuery = true,
    )
    fun deleteUsenetContentsUnderDirectory(directoryPath: String)

    @Modifying
    @Transactional
    @Query(
        """
            DELETE FROM nzb_contents
            WHERE id IN (
                SELECT debrid_file_contents_id FROM db_item
                WHERE directory_id IN (SELECT id FROM db_item WHERE path <@ CAST(:directoryPath AS ltree))
                AND debrid_file_contents_id IS NOT NULL
            )
        """,
        nativeQuery = true,
    )
    fun deleteNzbContentsUnderDirectory(directoryPath: String)

    @Modifying
    @Transactional
    @Query(
        """
            DELETE FROM db_item
            WHERE directory_id IN (SELECT id FROM db_item WHERE path <@ CAST(:directoryPath AS ltree))
        """,
        nativeQuery = true,
    )
    fun deleteFilesUnderDirectory(directoryPath: String)

    @Modifying
    @Transactional
    @Query(
        "DELETE FROM db_item WHERE path <@ CAST(:directoryPath AS ltree)",
        nativeQuery = true,
    )
    fun deleteDirectorySubtreeRows(directoryPath: String)
}
