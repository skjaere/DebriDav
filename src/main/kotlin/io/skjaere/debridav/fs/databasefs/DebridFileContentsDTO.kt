package io.skjaere.debridav.fs.databasefs

import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorColumn
import jakarta.persistence.DiscriminatorType
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Inheritance
import jakarta.persistence.InheritanceType
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.OneToMany

@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@DiscriminatorColumn(name = "file_type", discriminatorType = DiscriminatorType.STRING)
abstract class DebridFileContentsDTO {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var originalPath: String? = null
    open var size: Long? = null
    open var modified: Long? = null

    @OneToMany(
        targetEntity = DebridFileDTO::class,
        cascade = [CascadeType.ALL],
        fetch = FetchType.EAGER,
        orphanRemoval = true
    )
    //@JoinColumn(name = "debrid_file_debrid_links", referencedColumnName = "debrid_file_debrid_links")
    open var debridLinks: List<DebridFileDTO>? = null

}

@Entity
open class DebridTorrentContentsDTO : DebridFileContentsDTO() {
    @Column(name = "magnet", length = 2048)
    open var magnet: String? = null
}

@Entity
open class DebridUsenetContentsDTO : DebridFileContentsDTO() {
    open var usenetDownloadId: Long? = null
    open var nzbFileLocation: String? = null
    open var hash: String? = null
    open var mimeType: String? = null
}

@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@DiscriminatorColumn(name = "link_type", discriminatorType = DiscriminatorType.STRING)
abstract class DebridFileDTO {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var provider: String? = null
    open var lastChecked: Long? = null
}

@Entity
open class CachedFileDTO : DebridFileDTO() {
    @Column(name = "path", length = 2048)
    open var path: String? = null
    open var size: Long? = null
    open var mimeType: String? = null

    @Column(name = "link", length = 2048)
    open var link: String? = null

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cached_file_params")
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    open var params: Map<String, String>? = mutableMapOf()
}

@Entity
open class MissingFileDTO : DebridFileDTO()

@Entity
open class ProviderErrorDTO : DebridFileDTO()

@Entity
open class ClientErrorDTO : DebridFileDTO()

@Entity
open class NetworkErrorDTO : DebridFileDTO()
