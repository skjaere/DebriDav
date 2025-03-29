package io.skjaere.debridav.cache

import io.milton.http.Range
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.Blob
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.hibernate.Session
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.InputStream
import java.time.Instant
import java.util.*


@Service
class FileChunkCachingService(
    private val fileChunkRepository: FileChunkRepository,
    private val entityManager: EntityManager,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) {
    private val hibernateSession = entityManager.unwrap(Session::class.java)

    @Transactional
    fun getCachedChunk(
        remotelyCachedEntity: RemotelyCachedEntity,
        fileSize: Long,
        debridProvider: DebridProvider,
        range: Range
    ): InputStream? {

        return getByteRange(range.start, range.finish, fileSize)?.let { rangePair ->
            fileChunkRepository.getByRemotelyCachedEntityAndStartByteAndEndByteAndDebridProvider(
                remotelyCachedEntity,
                rangePair.start,
                rangePair.finish,
                debridProvider,
            )?.let {
                it.lastAccessed = Date.from(Instant.ofEpochMilli(range.start))
                fileChunkRepository.save(it)
                it.blob!!.localContents!!.binaryStream
            }
        }
    }


    fun cacheChunk(
        inputStream: InputStream,
        remotelyCachedEntity: RemotelyCachedEntity,
        startByte: Long,
        endByte: Long,
        debridProvider: DebridProvider,
    ) {
        val blob = Blob()
        blob.localContents =
            hibernateSession.lobHelper.createBlob(inputStream, (endByte - startByte) + 1)
        val fileChunk = FileChunk()
        fileChunk.remotelyCachedEntity = remotelyCachedEntity
        fileChunk.startByte = startByte
        fileChunk.endByte = endByte
        fileChunk.blob = blob
        fileChunk.lastAccessed = Date.from(Instant.now())
        fileChunk.debridProvider = debridProvider
        fileChunkRepository.save(fileChunk)
    }

    fun deleteChunksForFile(remotelyCachedEntity: RemotelyCachedEntity) {
        entityManager.createNativeQuery(
            """
            SELECT lo_unlink(b.loid) from (
                select b.local_contents as loid from file_chunk
                inner join blob b on file_chunk.blob_id = b.id
                where file_chunk.remotely_cached_entity_id = ${remotelyCachedEntity.id}
            ) as b
           
            """.trimMargin()
        ).resultList
        entityManager.close()
        fileChunkRepository.deleteByRemotelyCachedEntity(remotelyCachedEntity.id!!)
    }

    fun getByteRange(start: Long?, finish: Long?, fileSize: Long): ByteRangeInfo? {
        val start = start ?: 0
        val finish = finish ?: (fileSize - 1)
        return if (start == 0L && finish == (fileSize - 1)) {
            null
        } else ByteRangeInfo(start, finish)
    }

    @Scheduled(fixedRate = 1000 * 60 * 60) // once per hour
    fun purgeStaleCachedChunks() {
        fileChunkRepository.deleteByLastAccessedBefore(
            Date.from(
                Instant.now().minus(debridavConfigurationProperties.chunkCachingGracePeriod)
            )
        )
    }

    data class ByteRangeInfo(
        val start: Long,
        val finish: Long
    ) {
        fun length(): Long {
            return (finish - start) + 1
        }
    }
}
