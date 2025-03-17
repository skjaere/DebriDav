package io.skjaere.debridav.debrid.client.realdebrid.model

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    indexes = [
        Index(name = "download_id", columnList = "downloadId")
    ]
)
open class RealDebridDownloadEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var downloadId: String? = null
    open var filename: String? = null
    open var mimeType: String? = null
    open var fileSize: Long? = null
    open var link: String? = null
    open var host: String? = null
    open var chunks: Int? = null
    open var download: String? = null
    open var streamable: Int? = null
    open var generated: String? = null
}
