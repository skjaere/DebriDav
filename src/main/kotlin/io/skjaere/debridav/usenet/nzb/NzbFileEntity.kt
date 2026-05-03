package io.skjaere.debridav.usenet.nzb

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.hibernate.annotations.Fetch
import org.hibernate.annotations.FetchMode

/**
 * One file inside an NZB. Replaces the per-file slice of the old `nzb_document.files`
 * JSONB list. Carries the file's `yencSize` + `yencPartEnd` metadata; segments hang
 * off as a child collection ordered by [NzbSegmentEntity.segmentNumber].
 *
 * **fileIndex**: 0-based position within the parent NZB document. Preserved across
 * the V30 backfill via array ordinality so any code that depended on the file order
 * (e.g. nzb-streamer's NzbFile reconstruction) keeps working.
 */
@Entity
@Table(name = "nzb_file")
open class NzbFileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nzb_document_id", nullable = false)
    open var document: NzbDocumentEntity? = null

    @Column(name = "file_index", nullable = false)
    open var fileIndex: Int = 0

    @Column(name = "yenc_size", nullable = false)
    open var yencSize: Long = 0

    @Column(name = "yenc_part_end")
    open var yencPartEnd: Long? = null

    /**
     * Cascade.ALL + orphanRemoval mirrors the DB-level ON DELETE CASCADE: deleting a
     * file (or replacing the parent's file list) drops its segments. Lazy by default
     * so call sites that only need file metadata don't pay for segment loading.
     *
     * [FetchMode.SUBSELECT] is critical: when a parent query JOIN-FETCHes
     * `nzb_document.files` (one bag), trying to JOIN FETCH this collection in the
     * same query trips Hibernate's MultipleBagFetchException. SUBSELECT instead
     * fires a single follow-up query (`WHERE nzb_file_id IN (<original file ids>)`)
     * the first time any segments collection is touched, batching all segments for
     * all loaded files into one round-trip — same end result as a join, no N+1, no
     * cartesian product to deduplicate.
     */
    @OneToMany(
        mappedBy = "file",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("segmentNumber ASC")
    open var segments: MutableList<NzbSegmentEntity> = mutableListOf()
}
