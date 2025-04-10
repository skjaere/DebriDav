package io.skjaere.debridav.cache

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.Blob
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.*

@Entity
@Table(uniqueConstraints = [UniqueConstraint(columnNames = ["remotelyCachedEntity", "startByte", "endByte"])])
open class FileChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    open var remotelyCachedEntity: RemotelyCachedEntity? = null

    open var debridProvider: DebridProvider? = null

    open var lastAccessed: Date? = null

    open var startByte: Long? = null

    open var endByte: Long? = null

    @OneToOne(cascade = [(CascadeType.ALL)])
    open var blob: Blob? = null
}
