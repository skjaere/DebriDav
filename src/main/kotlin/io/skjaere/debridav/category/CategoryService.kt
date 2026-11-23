package io.skjaere.debridav.category

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import org.springframework.stereotype.Service

@Service
class CategoryService(
    private val categoryRepository: CategoryRepository,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
) {
    init {
        debridavConfigurationProperties.defaultCategories.forEach { getOrCreateCategory(it) }
    }

    fun getOrCreateCategory(categoryName: String): Category =
        categoryRepository.upsertByName(categoryName, debridavConfigurationProperties.downloadPath)

    fun createCategory(categoryName: String): Category = getOrCreateCategory(categoryName)

    fun getAllCategories(): List<Category> {
        return categoryRepository.findAll().toList()
    }

    fun findByName(name: String): Category? {
        return categoryRepository.findByNameIgnoreCase(name)
    }
}
