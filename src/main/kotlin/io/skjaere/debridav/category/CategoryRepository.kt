package io.skjaere.debridav.category

import org.springframework.data.repository.CrudRepository

interface CategoryRepository : CrudRepository<Category, Long> {
    fun findByNameIgnoreCase(name: String): Category?
}
