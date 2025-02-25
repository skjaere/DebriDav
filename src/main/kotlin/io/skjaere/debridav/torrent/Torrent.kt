package io.skjaere.debridav.torrent

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import java.time.Instant

@Entity
open class Torrent {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var name: String? = null

    @ManyToOne(cascade = [(CascadeType.MERGE)])
    open var category: Category? = null

    @OneToMany(
        targetEntity = RemotelyCachedEntity::class,
        cascade = [CascadeType.ALL],
        orphanRemoval = true
    )
    open var files: MutableList<RemotelyCachedEntity> = mutableListOf()
    open var created: Instant? = null
    open var hash: String? = null
    open var savePath: String? = null
    open var status: Status = Status.LIVE
}

enum class Status { LIVE, DELETED }
