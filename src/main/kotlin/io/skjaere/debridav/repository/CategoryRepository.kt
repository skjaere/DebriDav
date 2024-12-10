package io.skjaere.debridav.repository

import io.skjaere.debridav.qbittorrent.Category
import org.springframework.data.repository.CrudRepository

interface CategoryRepository : CrudRepository<Category, Long> {
    fun findByName(name: String): Category?
}
