package io.skjaere.debridav.cache

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import java.util.*

@Transactional
interface FileChunkRepository : CrudRepository<FileChunk, Long> {
    @Transactional
    fun getByRemotelyCachedEntityAndStartByteAndEndByteAndDebridProvider(
        remotelyCachedEntity: RemotelyCachedEntity,
        startByte: Long,
        endByte: Long,
        debridProvider: DebridProvider
    ): FileChunk?

    fun deleteByLastAccessedBefore(lastAccessedBefore: Date)

    @Transactional
    @Modifying
    @Query("delete from FileChunk fc where fc.remotelyCachedEntity.id = :remotelyCachedEntityId")
    fun deleteByRemotelyCachedEntity(remotelyCachedEntityId: Long)
}
