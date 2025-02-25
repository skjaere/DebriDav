package io.skjaere.debridav.fs.import

import org.springframework.data.repository.CrudRepository

interface ImportRegistryRepository : CrudRepository<FileImport, Long> {
    fun existsByPath(path: String): Boolean
}
