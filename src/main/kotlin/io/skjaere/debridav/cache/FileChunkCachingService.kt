package io.skjaere.debridav.cache

import io.milton.http.Range
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.Blob
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.BlobRepository
import jakarta.persistence.EntityManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.apache.commons.io.FileUtils
import org.hibernate.Session
import org.postgresql.largeobject.BlobInputStream
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
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
    private val transactionManager: PlatformTransactionManager
) {
    private val hibernateSession = entityManager.unwrap(Session::class.java)
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val logger = LoggerFactory.getLogger(FileChunkCachingService::class.java)

    private val cacheSize =
        Gauge.builder().name("debridav.cache.size").help("Metrics for library files").register(prometheusRegistry)

    private val cacheItems =
        Gauge.builder().name("debridav.cache.items").help("Metrics for library files").register(prometheusRegistry)

    fun getAllCachedChunksForEntity(remotelyCachedEntity: RemotelyCachedEntity): List<FileChunk> =
        fileChunkRepository.findByRemotelyCachedEntity(remotelyCachedEntity)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getBytesFromChunk(
        fileChunk: FileChunk,
        range: LongRange
    ): ByteArray {
        val bytes = runBlocking {
            val transaction = transactionManager.getTransaction(DefaultTransactionDefinition())
            val size = fileChunk.endByte!! - fileChunk.startByte!! + 1
            val stream = fileChunk.blob!!.localContents!!.binaryStream as BlobInputStream
            try {
                if (range.start != fileChunk.startByte!!) {
                    val skipBytes = range.start - fileChunk.startByte!!
                    logger.info("skipping $skipBytes bytes of bytes $size")
                    stream.skipNBytes(skipBytes)
                }
                val bytesToRead = range.endInclusive - range.start + 1
                if (range.endInclusive != fileChunk.endByte) {
                    logger.info("sending subset of chunk")
                }
                val bytes = stream.readNBytes(bytesToRead.toInt())
                bytes
            } finally {
                stream.close()
                if (!transaction.isCompleted) transactionManager.commit(transaction)
            }
        }
        return bytes!!
    }

    fun cacheBytes(
        remotelyCachedEntity: RemotelyCachedEntity, byteArraysToCache: List<BytesToCache>
    ) {
        val merged = mergeBytesToCache(byteArraysToCache)
        val toBeSaved = merged
            .filterNot { existsInCache(remotelyCachedEntity, it.startByte, it.endByte) }
        toBeSaved.forEach {
            cacheChunk(
                it.bytes, remotelyCachedEntity, it.startByte, it.endByte
            )

        }
        logger.info(
            "Saved ${
                FileUtils.byteCountToDisplaySize(toBeSaved.sumOf { it.bytes.size }.toLong())
            } to cache"
        )
    }

    private fun mergeBytesToCache(byteArraysToCache: List<BytesToCache>): MutableList<BytesToCache> =
        byteArraysToCache.fold(mutableListOf()) { acc, bytesToCache ->
            if (acc.isEmpty()) {
                acc.add(bytesToCache)
            } else {
                val last = acc.last()
                if (last.endByte + 1 == bytesToCache.startByte) {
                    last.endByte = bytesToCache.endByte
                    last.bytes = last.bytes.plus(bytesToCache.bytes)
                } else {
                    acc.add(bytesToCache)
                }
            }
            acc

        }

    fun cacheChunk(
        bytes: ByteArray, remotelyCachedEntity: RemotelyCachedEntity, startByte: Long, endByte: Long
    ): FileChunk = transactionTemplate.execute {
        logger.info(
            "caching chunk from $startByte to $endByte " +
                    "for ${remotelyCachedEntity.name} of size ${bytes.size} bytes"
        )
        val size = (endByte - startByte) + 1
        prepareCacheForNewEntry(size)

        val blob = Blob()
        blob.localContents = hibernateSession.lobHelper.createBlob(bytes)
        blob.size = size
        val fileChunk = FileChunk()
        fileChunk.remotelyCachedEntity = remotelyCachedEntity
        fileChunk.startByte = startByte
        fileChunk.endByte = endByte
        fileChunk.blob = blob
        fileChunk.lastAccessed = Date.from(Instant.now())
        fileChunkRepository.save(fileChunk)
    }!!

    private fun prepareCacheForNewEntry(size: Long) {
        if (cacheSizeExceededWithEntryOfSize(size)) {
            transactionTemplate.execute {
                var cacheEmpty = false
                do {
                    fileChunkRepository.getOldestEntry()?.let { oldestEntry -> deleteChunk(oldestEntry) } ?: run {
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
        fileChunkRepository.getTotalCacheSize().toDouble()
            .plus(size) / GIGABYTE > debridavConfigurationProperties.cacheMaxSizeGb

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

    fun existsInCache(remotelyCachedEntity: RemotelyCachedEntity, startByte: Long, endByte: Long): Boolean =
        fileChunkRepository.existsByRemotelyCachedEntityAndStartByteAndEndByte(remotelyCachedEntity, startByte, endByte)


    fun blobExists(blob: Blob): Boolean {
        return blobRepository.existsById(blob.id!!)
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
            fileChunk.blob?.let { blob ->
                blobRepository.deleteById(blob.id!!)
            }

        }

    }

    @Scheduled(fixedRate = 60000)
    fun exportMetrics() {
        cacheSize.set(fileChunkRepository.getCacheSize().toDouble())
        cacheItems.set(fileChunkRepository.getNumberOfEntries().toDouble())
    }

    data class ByteRangeInfo(
        val start: Long, val finish: Long
    ) {
        fun length(): Long {
            return (finish - start) + 1
        }
    }
}
