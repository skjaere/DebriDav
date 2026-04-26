package io.skjaere.debridav.config

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

interface ConfigOverrideRepository : JpaRepository<ConfigOverride, Long> {
    fun findByPropKey(key: String): ConfigOverride?
    fun findAllByPropKeyIn(keys: Collection<String>): List<ConfigOverride>
    fun findAllByPropKeyStartingWith(prefix: String): List<ConfigOverride>

    @Transactional
    fun deleteAllByPropKeyStartingWith(prefix: String)
}
