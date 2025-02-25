package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.DebridProvider
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
abstract class DebridFileContents {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var originalPath: String? = null
    open var size: Long? = null
    open var modified: Long? = null
    open var mimeType: String? = null

    @OneToMany(
        targetEntity = DebridFile::class,
        cascade = [CascadeType.ALL],
        fetch = FetchType.EAGER,
        orphanRemoval = true
    )
    open var debridLinks: MutableList<DebridFile> = mutableListOf()

    fun replaceOrAddDebridLink(debridLink: DebridFile) {
        if (debridLinks.any { link -> link.provider == debridLink.provider }) {
            val index = debridLinks.indexOfFirst { link -> link.provider == debridLink.provider }
            debridLinks[index] = debridLink
        } else {
            debridLinks.add(debridLink)
        }
    }
}

@Entity
open class DebridCachedTorrentContent() : DebridFileContents() {
    @Column(name = "magnet", length = 2048)
    open var magnet: String? = null

    constructor(magnet: String) : this() {
        this.magnet = magnet
    }

    constructor(
        originalPath: String?,
        size: Long?,
        modified: Long?,
        magnet: String?,
        mimeType: String?,
        debridLinks: MutableList<DebridFile>
    ) : this() {
        this.originalPath = originalPath
        this.size = size
        this.modified = modified
        this.magnet = magnet
        this.debridLinks = debridLinks
        this.mimeType = mimeType
    }
}

@Entity
open class DebridCachedUsenetReleaseContent() : DebridFileContents() {
    @Column(name = "releaseName", length = 2048)
    open var releaseName: String? = null

    constructor(releaseName: String) : this() {
        this.releaseName = releaseName
    }

    constructor(
        originalPath: String?,
        size: Long?,
        modified: Long?,
        releaseName: String?,
        mimeType: String?,
        debridLinks: MutableList<DebridFile>
    ) : this() {
        this.originalPath = originalPath
        this.size = size
        this.modified = modified
        this.releaseName = releaseName
        this.debridLinks = debridLinks
        this.mimeType = mimeType
    }
}

@Entity
open class DebridUsenetContents : DebridFileContents() {
    open var usenetDownloadId: Long? = null
    open var nzbFileLocation: String? = null
    open var hash: String? = null
    //open var mimeType: String? = null
}

@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@DiscriminatorColumn(name = "link_type", discriminatorType = DiscriminatorType.STRING)
abstract class DebridFile {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var provider: DebridProvider? = null
    open var lastChecked: Long? = null
}

@Entity
open class CachedFile() : DebridFile() {
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

    @Suppress("LongParameterList")
    constructor(
        path: String,
        size: Long,
        mimeType: String,
        link: String,
        params: Map<String, String>?,
        lastChecked: Long,
        provider: DebridProvider
    ) : this() {
        this.path = path
        this.size = size
        this.mimeType = mimeType
        this.link = link
        this.params = params
        this.provider = provider
        this.lastChecked = lastChecked
    }
}

@Entity
open class MissingFile() : DebridFile() {
    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}

@Entity
open class ProviderError() : DebridFile() {
    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}

@Entity
open class ClientError() : DebridFile() {
    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}

@Entity
open class NetworkError() : DebridFile() {
    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}

@Entity
open class UnknownError() : DebridFile() {
    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}
