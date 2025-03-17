package io.skjaere.debridav.debrid.client.realdebrid.model

import jakarta.persistence.CascadeType
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

@Entity
@Table(
    indexes = [
        Index(name = "torrent_id", columnList = "torrentId"),
        Index(name = "hash", columnList = "hash")
    ]
)
open class RealDebridTorrentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var torrentId: String? = null
    open var name: String? = null
    open var hash: String? = null

    @ElementCollection
    open var links: List<String> = emptyList()

    @OneToMany(cascade = [CascadeType.ALL], targetEntity = RealDebridTorrentFile::class)
    open var files: List<TorrentsInfoFile> = emptyList()

}

@Entity
open class RealDebridTorrentFile {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var fileId: Int? = null
    open var path: String? = null
    open var bytes: Long? = null
    open var selected: Int? = null

}
