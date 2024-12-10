package io.skjaere.debridav.qbittorrent

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

@Entity
open class TorrentFile {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var fileName: String? = null
    open var size: Long? = null
    open var path: String? = null
    open var hash: String? = null
}
