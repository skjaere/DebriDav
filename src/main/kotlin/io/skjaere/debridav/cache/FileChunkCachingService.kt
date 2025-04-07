package io.skjaere.debridav.cache

import io.milton.http.Range
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.Blob
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.BlobRepository
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.time.Instant
import java.util.*


@Service
class FileChunkCachingService(
    private val fileChunkRepository: FileChunkRepository,
    private val entityManager: EntityManager,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val blobRepository: BlobRepository,
    transactionManager: PlatformTransactionManager
) {
    private val hibernateSession = entityManager.unwrap(Session::class.java)
    private val transactionTemplate = TransactionTemplate(transactionManager)

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
                it.lastAccessed = Date.from(Instant.now())
                fileChunkRepository.save(it)
                entityManager.refresh(it)
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
        fileChunkRepository.findByRemotelyCachedEntity(remotelyCachedEntity).forEach {
            fileChunkRepository.delete(it)
        }
        fileChunkRepository.deleteByRemotelyCachedEntity(remotelyCachedEntity.id!!)
    }

    fun getByteRange(start: Long?, finish: Long?, fileSize: Long): ByteRangeInfo? {
        val start = start ?: 0
        val finish = finish ?: (fileSize - 1)
        return ByteRangeInfo(start, finish)
    }

    @Scheduled(fixedRate = 1000 * 60 * 60) // once per hour
    fun purgeStaleCachedChunks() {
        fileChunkRepository.findByLastAccessedBefore(
            Date.from(
                Instant.now().minus(debridavConfigurationProperties.chunkCachingGracePeriod)
            )
        ).forEach { fileChunk ->
            deleteCachedChunk(fileChunk)
        }

    }

    fun purgeCache() {
        fileChunkRepository.findAll().forEach { fileChunk ->
            transactionTemplate.execute {
                deleteCachedChunk(fileChunk)
            }
        }
    }

    private fun deleteCachedChunk(fileChunk: FileChunk) {
        transactionTemplate.execute {
            deleteChunksForFile(fileChunk.remotelyCachedEntity!!)
            blobRepository.deleteById(fileChunk.blob!!.id!!)

        }
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
