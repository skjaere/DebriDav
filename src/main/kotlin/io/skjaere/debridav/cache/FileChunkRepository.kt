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
    //fun getByRemotelyCachedEntity(remotelyCachedEntity: Long): FileChunk?

    @Transactional
    fun getByRemotelyCachedEntityAndStartByteAndEndByteAndDebridProvider(
        remotelyCachedEntity: RemotelyCachedEntity,
        startByte: Long,
        endByte: Long,
        debridProvider: DebridProvider
    ): FileChunk?

    fun deleteByLastAccessedBefore(lastAccessedBefore: Date)
    fun findByLastAccessedBefore(lastAccessedBefore: Date): List<FileChunk>
    fun findByRemotelyCachedEntity(remotelyCachedEntity: RemotelyCachedEntity): List<FileChunk>

    @Transactional
    @Modifying
    @Query("delete from FileChunk fc where fc.remotelyCachedEntity.id = :remotelyCachedEntityId")
    fun deleteByRemotelyCachedEntity(remotelyCachedEntityId: Long)

    @Query("select sum(b.size) from file_chunk fc inner join blob b on b.id=fc.blob_id", nativeQuery = true)
    fun getTotalCacheSize(): Long

    @Query("select * from file_chunk order by last_accessed desc limit 1", nativeQuery = true)
    fun getOldestEntry(): FileChunk?

    @Query("select coalesce(sum(endByte - startByte), 0) from FileChunk")
    fun getCacheSize(): Long

    @Query("select coalesce(count(*), 0) from FileChunk")
    fun getNumberOfEntries(): Long
}
