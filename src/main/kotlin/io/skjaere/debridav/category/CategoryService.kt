package io.skjaere.debridav.category

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class CategoryService(
    private val categoryRepository: CategoryRepository,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
) {
    init {
        debridavConfigurationProperties.defaultCategories.forEach {
            if (findByName(it) == null) {
                createCategory(it)
            }
        }
    }

    suspend fun getOrCreateCategory(categoryName: String): Category {
        return categoryRepository.findByNameIgnoreCase(categoryName) ?: kotlin.run {
            val newCategory = Category()
            newCategory.name = categoryName
            categoryRepository.save(newCategory)
        }
    }

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
        return categoryRepository.findByNameIgnoreCase(name)
    }
}
