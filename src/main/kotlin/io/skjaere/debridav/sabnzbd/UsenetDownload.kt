package io.skjaere.debridav.sabnzbd

import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.qbittorrent.Category
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import java.util.*

@Entity
open class UsenetDownload {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var status: UsenetDownloadStatus? = null
    open var name: String? = null
    open var debridId: Long? = null
    open var created: Date? = null
    open var completed: Boolean? = null
    open var percentCompleted: Double? = null
    open var debridProvider: DebridProvider? = null
    open var size: Long? = null
    open var hash: String? = null
    open var eta: String? = null
    open var storagePath: String? = null

    @ManyToOne
    open var category: Category? = null
}

enum class UsenetDownloadStatus {
    CREATED, QUEUED, DOWNLOADING, EXTRACTING, COMPLETED, FAILED, VERIFYING, DELETED, CACHED, REPAIRING;

    fun isCompleted(): Boolean = this == COMPLETED || this == CACHED || this == FAILED

    companion object {
        fun valueFrom(value: String): UsenetDownloadStatus {
            return if (value.equals(
                    "failed (Aborted, cannot be completed - https://sabnzbd.org/not-complete)",
                    ignoreCase = true
                )
            ) FAILED
            else if (value.equals(
                    "FAILED (MISSING ARTICLES)",
                    ignoreCase = true
                )
            ) FAILED
            else valueOf(value)
        }
    }
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

            }
    }

}
