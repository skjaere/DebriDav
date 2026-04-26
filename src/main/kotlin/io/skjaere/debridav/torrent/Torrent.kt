package io.skjaere.debridav.torrent

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(indexes = [Index(name = "idx_torrent_category_id", columnList = "category_id")])
open class Torrent {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var name: String? = null

    @ManyToOne(cascade = [(CascadeType.MERGE)])
    open var category: Category? = null

    @OneToMany(
        targetEntity = RemotelyCachedEntity::class,
        cascade = [CascadeType.PERSIST, CascadeType.MERGE],
        fetch = FetchType.LAZY,
    )
    open var files: MutableList<RemotelyCachedEntity> = mutableListOf()
    open var created: Instant? = null

    @Column(nullable = false, unique = true)
    open var hash: String? = null

    @Column(nullable = false, length = 2048)
    open var savePath: String? = null
    open var status: Status = Status.LIVE

    @Column(name = "last_verified")
    open var lastVerified: Instant? = null

    @Column(name = "health_check_enqueued_at")
    open var healthCheckEnqueuedAt: Instant? = null

    // Equality on the business key (info-hash). Hibernate proxies are subclasses
    // of the entity, so the `is Torrent` check works for both real and proxied
    // instances.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Torrent) return false
        return hash != null && hash == other.hash
    }

    override fun hashCode(): Int = hash?.hashCode() ?: 0
}

enum class Status { LIVE, DELETED }
