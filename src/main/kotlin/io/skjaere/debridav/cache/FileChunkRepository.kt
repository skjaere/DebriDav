package io.skjaere.debridav.cache

import jakarta.transaction.Transactional
import org.springframework.data.repository.CrudRepository
import java.util.*

@Transactional
interface FileChunkRepository : CrudRepository<FileChunk, Long> {
    @Transactional
    fun getByUrlAndStartByteAndEndByte(url: String, startByte: Long, endByte: Long): FileChunk?

    fun deleteByLastAccessedBefore(lastAccessedBefore: Date)
}
