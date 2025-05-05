package io.skjaere.debridav.cache

import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
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

const val GIGABYTE = 1024 * 1024 * 1024

@Service
class FileChunkCachingService(
    private val fileChunkRepository: FileChunkRepository,
    private val entityManager: EntityManager,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val blobRepository: BlobRepository,
    prometheusRegistry: PrometheusRegistry,
    transactionManager: PlatformTransactionManager
) {
    private val hibernateSession = entityManager.unwrap(Session::class.java)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    private val cacheSize = Gauge.builder()
        .name("debridav.cache.size")
        .help("Metrics for library files")
        .register(prometheusRegistry)

    private val cacheItems = Gauge.builder()
        .name("debridav.cache.items")
        .help("Metrics for library files")
        .register(prometheusRegistry)

    fun getCachedChunk(
        remotelyCachedEntity: RemotelyCachedEntity,
        fileSize: Long,
        debridProvider: DebridProvider,
        range: ByteRangeInfo,
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
        val size = (endByte - startByte) + 1
        prepareCacheForNewEntry(size)

        val blob = Blob()
        blob.localContents =
            hibernateSession.lobHelper.createBlob(inputStream, size)
        blob.size = size
        val fileChunk = FileChunk()
        fileChunk.remotelyCachedEntity = remotelyCachedEntity
        fileChunk.startByte = startByte
        fileChunk.endByte = endByte
        fileChunk.blob = blob
        fileChunk.lastAccessed = Date.from(Instant.now())
        fileChunk.debridProvider = debridProvider
        fileChunkRepository.save(fileChunk)
    }

    private fun prepareCacheForNewEntry(size: Long) {
        if (cacheSizeExceededWithEntryOfSize(size)) {
            transactionTemplate.execute {
                var cacheEmpty = false
                do {
                    fileChunkRepository.getOldestEntry()
                        ?.let { oldestEntry -> deleteChunk(oldestEntry) }
                        ?: run {
                            cacheEmpty = true
                        }
                } while (cacheSizeExceededWithEntryOfSize(size) && !cacheEmpty)
            }
        }
    }

    private fun deleteChunk(chunk: FileChunk) {
        transactionTemplate.execute {
            entityManager.createNativeQuery(
                """
                SELECT lo_unlink(b.loid) from (
                select b.local_contents as loid from file_chunk
                inner join blob b on file_chunk.blob_id = b.id
                where file_chunk.id = ${chunk.id}
            ) as b
            """.trimIndent()
            ).resultList
            fileChunkRepository.delete(chunk)
        }
    }

    private fun cacheSizeExceededWithEntryOfSize(size: Long): Boolean =
        (fileChunkRepository.getTotalCacheSize()
            ?: (0 + size)).toDouble() / GIGABYTE > debridavConfigurationProperties.cacheMaxSizeGb

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

    fun getByteRange(range: Range, fileSize: Long): ByteRangeInfo? {
        val start = range.start ?: 0
        val finish = range.finish ?: (fileSize - 1)
        return ByteRangeInfo(start, finish)
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

    @Scheduled(fixedRate = 60000)
    fun exportMetrics() {
        cacheSize.set(fileChunkRepository.getCacheSize().toDouble())
        cacheItems.set(fileChunkRepository.getNumberOfEntries().toDouble())
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
