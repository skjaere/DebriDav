package io.skjaere.debridav.fs.databasefs

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
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import jakarta.persistence.Table

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "db_item_type", discriminatorType = DiscriminatorType.STRING)
@Table(indexes = [Index(name = "db_item_path", columnList = "path")])
abstract class DbItem {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    @Column(name = "name", length = 2048)
    open var name: String? = null

    @Column(name = "path", length = 2048, unique = true)
    open var path: String? = null

    open var lastModified: Long? = null

    @ManyToOne(cascade = [(CascadeType.MERGE)], fetch = FetchType.LAZY)
    open var parent: DbDirectory? = null
}

@Entity
open class DbDirectory : DbItem() {

    @OneToMany(targetEntity = DbItem::class, cascade = [(CascadeType.ALL)], fetch = FetchType.EAGER)
    open var children: MutableList<DbItem>? = null
}

@Entity
open class DbFile : DbItem() {
    @Column(name = "directory", length = 2048)
    open var directory: String? = null

    @OneToOne(cascade = [CascadeType.ALL])
    @JoinColumn(name = "debrid_file_contents_id")
    open var contents: DebridFileContentsDTO? = null

    open var size: Long? = null

    open var mimeType: String? = null
}

@Entity
open class LocalFile : DbItem() {
    open var size: Long? = null
    open var mimeType: String? = null

    @Lob
    open var contents: ByteArray? = null
}
