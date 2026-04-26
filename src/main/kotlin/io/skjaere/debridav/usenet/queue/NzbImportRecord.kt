package io.skjaere.debridav.usenet.queue

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.Type
import java.time.Instant

@Entity
@Table(name = "nzb_import")
open class NzbImportRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "usenet_download_id")
    open var usenetDownloadId: Long? = null

    @Column(nullable = false)
    open var name: String = ""

    open var category: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    open var status: NzbImportStatus = NzbImportStatus.QUEUED

    @Column(name = "archive_type", length = 30)
    open var archiveType: String? = null

    @Column(name = "error_message", columnDefinition = "TEXT")
    open var errorMessage: String? = null

    @Type(JsonBinaryType::class)
    @Column(name = "files", columnDefinition = "jsonb")
    open var files: List<NzbImportFileJson>? = null

    open var size: Long? = null

    @Column(name = "created_at")
    open var createdAt: Instant? = null

    @Column(name = "updated_at")
    open var updatedAt: Instant? = null

    @PrePersist
    fun onPrePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }
}

enum class NzbImportStatus {
    QUEUED,
    IMPORTING,
    COMPLETED,
    FAILED
}

data class NzbImportFileJson(
    val path: String,
    val size: Long
) : java.io.Serializable
