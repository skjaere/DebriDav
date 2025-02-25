package io.skjaere.debridav.fs

import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.OutputStream

const val INV_READ = "x'40000'"
const val SEEK_SET = 0
const val READ_SIZE = 1024 * 512

@Service
class LocalContentsService(
    private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(LocalContentsService::class.java)

    @Transactional
    @Suppress("NestedBlockDepth")
    suspend fun getContentsOfLocalEntity(
        localEntity: LocalEntity,
        startPosition: Long?,
        endPosition: Long?,
        outputStream: OutputStream
    ) {
        logger.info("Streaming ${localEntity.name} range: $startPosition-$endPosition")
        openLobForReading(localEntity)?.let { fd ->
            startPosition?.let {
                seekToPosition(fd, it)
            }
            var remainingBytesToTransfer = ((endPosition ?: localEntity.size!!) - (startPosition ?: 0)) + 1
            outputStream.use {
                do {
                    val chunkSize =
                        if (remainingBytesToTransfer > READ_SIZE) READ_SIZE.toLong() else remainingBytesToTransfer
                    val chunk = read(fd, chunkSize.toLong())
                    outputStream.write(chunk)
                    remainingBytesToTransfer -= chunkSize
                } while (remainingBytesToTransfer > 0)
            }
        }

    }

    private fun openLobForReading(localEntity: LocalEntity): Int? {
        val query = """
            select lo_open(b.local_contents,  $INV_READ::int) as oid from db_item
            inner join blob b on b.id = db_item.blob_id
            where db_item.id=${localEntity.id};
        """.trimIndent()
        return entityManager.createNativeQuery(query).resultList.firstOrNull() as Int?
    }

    private fun seekToPosition(fd: Int, startPosition: Long) {
        entityManager
            .createNativeQuery("select lo_lseek($fd, $startPosition, $SEEK_SET)")
    }

    private fun read(fd: Int, size: Long): ByteArray {
        return entityManager
            .createNativeQuery("select loread($fd, $size)")
            .resultList
            .first() as ByteArray
    }
}
