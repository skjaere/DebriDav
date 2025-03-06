package io.skjaere.debridav.category

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class CategoryService(
    private val categoryRepository: CategoryRepository,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
) {
    @Transactional
    fun createCategory(categoryName: String): Category {
        val category = Category()
        category.name = categoryName
        category.downloadPath = debridavConfigurationProperties.downloadPath
        return categoryRepository.save(category)
    }

    fun getAllCategories(): List<Category> {
        return categoryRepository.findAll().toList()
    }

    fun findByName(name: String): Category? {
        return categoryRepository.findByName(name)
    }
}
