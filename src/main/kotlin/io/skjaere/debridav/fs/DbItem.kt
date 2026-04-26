package io.skjaere.debridav.fs

import io.ipfs.multibase.Base58
import io.skjaere.debridav.debrid.DebridProvider
import jakarta.persistence.Basic
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorColumn
import jakarta.persistence.DiscriminatorType
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Inheritance
import jakarta.persistence.InheritanceType
import jakarta.persistence.JoinColumn
import jakarta.persistence.Lob
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Type


@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "db_item_type", discriminatorType = DiscriminatorType.STRING)
@Table(
    name = "db_item",
    indexes = [
        Index(name = "directory_path", columnList = "path"),
        Index(name = "idx_db_item_directory_id", columnList = "directory_id"),
    ],
    uniqueConstraints = [UniqueConstraint(columnNames = arrayOf("directory_id", "name"))]
)
abstract class DbEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var name: String? = null

    open var lastModified: Long? = null

    open var size: Long? = null

    open var mimeType: String? = null

    @ManyToOne(cascade = [CascadeType.MERGE, CascadeType.DETACH], targetEntity = DbDirectory::class)
    open var directory: DbDirectory? = null
}

@Entity
open class DbDirectory : DbEntity() {
    @Column(name = "path", nullable = true, length = Int.MAX_VALUE, unique = true, columnDefinition = "ltree")
    @Type(value = LtreeType::class)
    open var path: String? = null

    fun fileSystemPath(): String? = path
        ?.split(".")
        ?.toMutableList()
        ?.filter { it != "ROOT" }
        ?.joinToString("/") { Base58.decode(it).decodeToString() }
        ?.let { "/$it" }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DbDirectory) return false
        return path != null && path == other.path
    }

    override fun hashCode(): Int = path?.hashCode() ?: 0
}

@Entity
open class RemotelyCachedEntity : DbEntity() {
    // Lifecycle cascades only — drop REFRESH and DETACH, which the audit flagged
    // as risky for an exclusively-owned child like this one.
    @OneToOne(cascade = [CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE])
    @JoinColumn(name = "debrid_file_contents_id")
    open var contents: DebridFileContents? = null

    open var hash: String? = null

    fun isNoLongerCached(debridClients: List<DebridProvider>) =
        contents!!
            .debridLinks
            .filter { debridClients.contains(it.provider) }
            .all { it is MissingFile }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RemotelyCachedEntity

        if (contents != other.contents) return false
        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contents?.hashCode() ?: 0
        result = 31 * result + (hash?.hashCode() ?: 0)
        return result
    }


}

@Entity
open class LocalEntity : DbEntity() {
    @OneToOne(
        fetch = FetchType.LAZY,
        cascade = [CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE],
    )
    @JoinColumn(name = "blob_id")
    open var blob: Blob? = null
}

@Entity
open class Blob() {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var size: Long? = null

    @Lob
    @Basic(fetch = FetchType.LAZY)
    open var localContents: java.sql.Blob? = null

    constructor(blob: java.sql.Blob, size: Long) : this() {
        this.localContents = blob
        this.size = size
    }
}
