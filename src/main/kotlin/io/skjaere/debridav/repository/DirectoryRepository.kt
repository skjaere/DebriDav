package io.skjaere.debridav.repository

import io.skjaere.debridav.fs.DbDirectory
import org.springframework.data.repository.CrudRepository

interface DirectoryRepository : CrudRepository<DbDirectory, Long> {
    fun getByPath(path: String): DbDirectory?
}
