package io.skjaere.debridav.usenet.nzb

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
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
import jakarta.persistence.Table
import org.hibernate.annotations.Type

@Entity
@Table(name = "nzb_document")
open class NzbDocumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Type(JsonBinaryType::class)
    @Column(name = "files", columnDefinition = "jsonb", nullable = false)
    open var files: List<NzbFileJson> = emptyList()

    @OneToMany(
        mappedBy = "document",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    open var streamableFiles: MutableList<NzbStreamableFileEntity> = mutableListOf()
}

data class NzbFileJson(
    val yencSize: Long,
    val yencPartEnd: Long? = null,
    val segments: List<NzbSegmentJson>
) : java.io.Serializable

data class NzbSegmentJson(
    val articleId: String,
    val number: Int,
    val bytes: Long
) : java.io.Serializable

@Entity
@Table(name = "nzb_streamable_file")
open class NzbStreamableFileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    open var document: NzbDocumentEntity? = null

    @Column(nullable = false)
    open var path: String = ""

    @Column(name = "total_size", nullable = false)
    open var totalSize: Long = 0

    @Column(name = "start_volume_index", nullable = false)
    open var startVolumeIndex: Int = 0

    @Column(name = "start_offset_in_volume", nullable = false)
    open var startOffsetInVolume: Long = 0

    @Column(name = "continuation_header_size", nullable = false)
    open var continuationHeaderSize: Long = 0

    @Column(name = "end_of_archive_size", nullable = false)
    open var endOfArchiveSize: Long = 0
}
