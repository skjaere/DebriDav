package io.skjaere.debridav.usenet

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

@Entity
@Table(indexes = [Index(name = "idx_usenet_download_category_id", columnList = "category_id")])
open class UsenetDownload {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var status: UsenetDownloadStatus? = null
    open var name: String? = null
    open var hash: String? = null

    open var percentCompleted: Double? = null
    open var size: Long? = null

    open var storagePath: String? = null

    @ManyToOne(cascade = [(CascadeType.MERGE)])
    open var category: Category? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nzb_document_id")
    open var nzbDocument: NzbDocumentEntity? = null

    @OneToMany(
        targetEntity = RemotelyCachedEntity::class,
        cascade = [CascadeType.PERSIST, CascadeType.MERGE],
        fetch = FetchType.LAZY,
    )
    open var debridFiles: MutableList<RemotelyCachedEntity> = mutableListOf()

    // Equality on the business key (NZB-bytes md5). `is UsenetDownload` matches
    // Hibernate proxies as well as real instances.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UsenetDownload) return false
        return hash != null && hash == other.hash
    }

    override fun hashCode(): Int = hash?.hashCode() ?: 0
}

enum class UsenetDownloadStatus {
    CREATED, QUEUED, DOWNLOADING, EXTRACTING, COMPLETED, FAILED, VERIFYING,
    DELETED, CACHED, REPAIRING, POST_PROCESSING, VALIDATING;

    fun isCompleted(): Boolean = this == COMPLETED || this == CACHED || this == FAILED
}

enum class SabnzbdUsenetDownloadStatus {
    CREATED, QUEUED, DOWNLOADING, EXTRACTING, COMPLETED, FAILED, VERIFYING, DELETED, REPAIRING;

    companion object {
        fun fromUsenetDownloadStatus(status: UsenetDownloadStatus): SabnzbdUsenetDownloadStatus =
            when (status) {
                UsenetDownloadStatus.CREATED -> QUEUED
                UsenetDownloadStatus.QUEUED -> QUEUED
                UsenetDownloadStatus.DOWNLOADING -> DOWNLOADING
                UsenetDownloadStatus.EXTRACTING -> EXTRACTING
                UsenetDownloadStatus.COMPLETED -> COMPLETED
                UsenetDownloadStatus.FAILED -> FAILED
                UsenetDownloadStatus.VERIFYING -> VERIFYING
                UsenetDownloadStatus.DELETED -> DELETED
                UsenetDownloadStatus.CACHED -> COMPLETED
                UsenetDownloadStatus.REPAIRING -> REPAIRING
                UsenetDownloadStatus.VALIDATING -> VERIFYING
                UsenetDownloadStatus.POST_PROCESSING -> VERIFYING
            }
    }

}
