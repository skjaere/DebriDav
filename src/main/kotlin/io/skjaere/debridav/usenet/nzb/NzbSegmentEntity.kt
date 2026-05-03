package io.skjaere.debridav.usenet.nzb

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * One yenc segment belonging to a single NZB file. Replaces the per-segment entry of
 * the old `nzb_file.segments` JSONB array.
 *
 * `articleId` is `TEXT` because Usenet article-ids have no real upper bound — most
 * are ~60 chars but some posters generate considerably longer message-ids.
 */
@Entity
@Table(name = "nzb_segment")
open class NzbSegmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nzb_file_id", nullable = false)
    open var file: NzbFileEntity? = null

    @Column(name = "segment_number", nullable = false)
    open var segmentNumber: Int = 0

    @Column(name = "article_id", nullable = false, columnDefinition = "TEXT")
    open var articleId: String = ""

    @Column(name = "bytes", nullable = false)
    open var bytes: Long = 0
}
