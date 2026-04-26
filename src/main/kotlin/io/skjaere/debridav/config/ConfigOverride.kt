package io.skjaere.debridav.config

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "config_override")
open class ConfigOverride {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "prop_key", nullable = false, unique = true)
    open var propKey: String = ""

    @Column(name = "prop_value", columnDefinition = "TEXT")
    open var propValue: String? = null

    @Column(name = "sensitive")
    open var sensitive: Boolean = false

    @Column(name = "created_at")
    open var createdAt: Instant? = null

    @Column(name = "updated_at")
    open var updatedAt: Instant? = null
}
