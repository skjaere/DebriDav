package io.skjaere.debridav.usenet

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany

@Entity
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

    @OneToMany(
        targetEntity = RemotelyCachedEntity::class,
        cascade = [CascadeType.PERSIST, CascadeType.MERGE],
        fetch = FetchType.EAGER,
    )
    open var debridFiles: MutableList<RemotelyCachedEntity> = mutableListOf()
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
