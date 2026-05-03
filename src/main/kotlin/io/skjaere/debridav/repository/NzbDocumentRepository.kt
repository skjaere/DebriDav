package io.skjaere.debridav.repository

import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import java.time.Instant

interface NzbDocumentRepository : CrudRepository<NzbDocumentEntity, Long> {
    /**
     * Used by the scheduled health-check sweep. Filters to documents that still have at
     * least one NzbContents referencing them — orphaned documents (deleted virtual files)
     * shouldn't burn a sweep cycle since [EmptyNzbDocumentCleanupTask] will prune them.
     *
     * Does NOT load the file/segment graph — that costs MB per row and the sweep only
     * needs metadata to enqueue check messages and bump timestamps.
     */
    @Query(
        "SELECT n FROM NzbDocumentEntity n " +
            "WHERE (n.lastVerified IS NULL OR n.lastVerified < :cutoff) " +
            "AND EXISTS (SELECT 1 FROM NzbContents nc WHERE nc.nzbDocument = n)"
    )
    fun findToVerifyWithContents(cutoff: Instant): List<NzbDocumentEntity>

    /**
     * Projection query for the trigger-full-health-check path. We only need the id to
     * enqueue. Filtered to documents with at least one NzbContents for the same reason
     * as [findToVerifyWithContents] — orphans get pruned, not verified.
     */
    @Query(
        "SELECT n.id FROM NzbDocumentEntity n " +
            "WHERE EXISTS (SELECT 1 FROM NzbContents nc WHERE nc.nzbDocument = n)"
    )
    fun findIdsWithContents(): List<Long>

    /**
     * Single-shot fetch of the document + its files for paths that need to walk into
     * segments (verifySegments, streamFile). Segments themselves are batch-loaded via
     * [NzbFileEntity.segments]'s `FetchMode.SUBSELECT` the first time any file's
     * segments collection is touched — a single follow-up query keyed on the IN-list
     * of files we just loaded. Doing two `LEFT JOIN FETCH` in this query instead
     * would trip Hibernate's MultipleBagFetchException.
     *
     * Returns null when the document doesn't exist. Use in place of
     * `findById(...).orElse(null)` whenever the caller is going to call
     * `toNzbDocument()`.
     */
    @Query(
        "SELECT DISTINCT n FROM NzbDocumentEntity n " +
            "LEFT JOIN FETCH n.files " +
            "WHERE n.id = :id"
    )
    fun findByIdWithFiles(id: Long): NzbDocumentEntity?

    /**
     * Bulk update of `healthCheckEnqueuedAt` so we don't have to load each entity to set a
     * single column. Pairs with [findIdsWithContents] in the trigger path.
     */
    @Modifying
    @Query("UPDATE NzbDocumentEntity n SET n.healthCheckEnqueuedAt = :now WHERE n.id IN :ids")
    fun bulkSetHealthCheckEnqueuedAt(ids: Collection<Long>, now: Instant)

    /**
     * Sweep query: delete `nzb_document` rows that no longer have any `nzb_contents`
     * referencing them. Without this, every sweep keeps re-verifying orphans whose
     * underlying virtual files were already deleted by repair/user action — those
     * orphans get marked unhealthy (their articles are usually long expired by now),
     * the repair handler tries to delete files that aren't there, the arr re-imports a
     * fresh release, and the orphan stays around to be re-checked next sweep.
     *
     * The companion FK-relax migration (V28) flips usenet_download's FK to ON DELETE
     * SET NULL so this delete doesn't trip the NO ACTION constraint when an audit-only
     * usenet_download row still references the parent.
     */
    @Modifying
    @Query(
        value = "DELETE FROM nzb_document nd WHERE NOT EXISTS (" +
            "SELECT 1 FROM nzb_contents nc WHERE nc.nzb_document_id = nd.id)",
        nativeQuery = true
    )
    fun deleteEmptyNzbDocuments(): Int
}
