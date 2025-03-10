package io.skjaere.debridav.cache

import io.skjaere.debridav.fs.Blob
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToOne
import java.util.*

@Entity
open class FileChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    @Column(nullable = false, length = 2048)
    open var url: String? = null

    open var lastAccessed: Date? = null

    open var startByte: Long? = null

    open var endByte: Long? = null

    @OneToOne(cascade = [(CascadeType.ALL)])
    open var blob: Blob? = null
}
