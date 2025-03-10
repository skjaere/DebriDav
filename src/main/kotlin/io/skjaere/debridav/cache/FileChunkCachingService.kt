package io.skjaere.debridav.cache

import io.milton.http.Range
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.Blob
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
        url: String,
        fileSize: Long,
        range: Range
    ): InputStream? {
        return getByteRange(range.start, range.finish, fileSize)?.let { rangePair ->
            fileChunkRepository.getByUrlAndStartByteAndEndByte(url, rangePair.start, rangePair.finish)?.let {
                it.lastAccessed = Date.from(Instant.ofEpochMilli(range.start))
                fileChunkRepository.save(it)
                it.blob!!.localContents!!.binaryStream
            }
        }
    }

    fun cacheChunk(inputStream: InputStream, url: String, startByte: Long, endByte: Long) {
        val blob = Blob()
        blob.localContents =
            hibernateSession.lobHelper.createBlob(inputStream, (endByte - startByte) + 1)
        val fileChunk = FileChunk()
        fileChunk.url = url
        fileChunk.startByte = startByte
        fileChunk.endByte = endByte
        fileChunk.blob = blob
        fileChunkRepository.save(fileChunk)
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
                Instant.now().minus(debridavConfigurationProperties.cachedFileChunkPurgeAfterLastRead)
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
