package io.skjaere.debridav.collection.indexer

import io.skjaere.newznab.Indexer
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** A Newznab indexer the collection player searches. [apiKey] never leaves the server. */
@Entity
@Table(name = "newznab_indexer")
open class NewznabIndexer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "name", nullable = false)
    open var name: String = ""

    @Column(name = "url", nullable = false)
    open var url: String = ""

    @Column(name = "api_key", nullable = false)
    open var apiKey: String = ""

    @Column(name = "enabled", nullable = false)
    open var enabled: Boolean = true

    @Column(name = "user_agent", nullable = false)
    open var userAgent: String = ""

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant = Instant.now()

    /** This indexer as newznab-kotlin searches it. */
    fun toIndexer(): Indexer = Indexer(
        id = id.toString(),
        url = url,
        apiKey = apiKey,
        name = name,
        enabled = enabled,
        userAgent = userAgent,
    )
}

interface NewznabIndexerRepository : JpaRepository<NewznabIndexer, Long>
