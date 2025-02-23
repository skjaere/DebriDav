package io.skjaere.debridav.usenet

import io.skjaere.debridav.qbittorrent.Category
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne

@Entity
open class UsenetDownload {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var status: UsenetDownloadStatus? = null
    open var name: String? = null

    @Column(unique = true)
    open var completed: Boolean? = null
    open var percentCompleted: Double? = null
    open var size: Long? = null

    open var hash: String? = null
    open var storagePath: String? = null

    @ManyToOne
    open var category: Category? = null
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
