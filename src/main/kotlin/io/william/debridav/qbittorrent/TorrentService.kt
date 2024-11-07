package io.william.debridav.qbittorrent

import io.william.debridav.debrid.*
import io.william.debridav.fs.DebridFileContents
import io.william.debridav.fs.FileService
import io.william.debridav.repository.CategoryRepository
import io.william.debridav.repository.TorrentFileRepository
import io.william.debridav.repository.TorrentRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID


@Service
class TorrentService(
    private val debridService: DebridService,
    private val fileService: FileService,
    private val torrentRepository: TorrentRepository,
    private val torrentFileRepository: TorrentFileRepository,
    private val categoryRepository: CategoryRepository
) {
    private val logger = LoggerFactory.getLogger(TorrentService::class.java)

    fun addTorrent(category: String, magnet: String): Boolean = runBlocking {
        if (debridService.isCached(magnet)) {
            debridService.getDebridFiles(magnet).let { debridFileContents ->
                if (debridFileContents.isEmpty()) {
                    logger.debug("Received empty list of files from debrid service")
                    return@runBlocking false
                }
                val torrent = createTorrent(debridFileContents, category, magnet)
                debridFileContents.forEach {
                    fileService.createDebridFile(
                        "${torrent.name}/${it.originalPath}",
                        it
                    )
                }
            }
            return@runBlocking true
        } else {
            logger.info("$magnet is not cached")
            return@runBlocking false
        }
    }

    private fun createTorrent(
        cachedFiles: List<DebridFileContents>,
        categoryName: String,
        magnet: String
    ): Torrent {
        val torrent = Torrent()
        torrent.category = categoryRepository.findByName(categoryName)
            ?: run { createCategory(categoryName) }
        torrent.name = getNameFromMagnet(magnet)
        torrent.created = Instant.now()
        torrent.hash = generateHash(torrent)
        torrent.savePath =
            "${torrent.category!!.downloadPath}/${URLDecoder.decode(torrent.name, Charsets.UTF_8.name())}"
        torrent.files = cachedFiles.map {
            val torrentFile = TorrentFile()
            torrentFile.fileName = it.originalPath.split("/").last()
            torrentFile.size = it.size
            torrentFile.path = it.originalPath
            torrentFileRepository.save(torrentFile)
        }
        return torrentRepository.save(torrent)
    }

    fun getTorrentsByCategory(categoryName: String): List<Torrent> {
        return categoryRepository.findByName(categoryName)?.let { category ->
            torrentRepository.findByCategory(category)
        } ?: emptyList()
    }

    fun createCategory(categoryName: String): Category {
        val category = Category()
        category.name = categoryName
        category.downloadPath = "/downloads"
        return categoryRepository.save(category)

    }

    fun getCategories(): List<Category> {
        return categoryRepository.findAll().toMutableList()
    }

    fun getTorrentByHash(hash: String): Torrent? {
        return torrentRepository.getByHash(hash)
    }

    fun deleteTorrentByHash(hash: String): Boolean {
        return torrentRepository.getByHash(hash.uppercase())?.let {
            torrentRepository.delete(it)
            true
        } ?: false
    }


    private fun generateHash(torrent: Torrent): String {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-1")
        var bytes: ByteArray =
            "${torrent.id}${torrent.name}${torrent.created}${torrent.category}".toByteArray(Charsets.UTF_8)
        digest.update(bytes, 0, bytes.size)
        bytes = digest.digest()

        return bytesToHex(bytes)
    }

    protected val hexArray: CharArray = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    fun getNameFromMagnet(magnet: String): String {
        return magnet.split("?").last().split("&")
            .map { it.split("=") }
            .associate { it.first() to it.last() }["dn"]
            ?.let {
                URLDecoder.decode(it, Charsets.UTF_8.name())
            } ?: UUID.randomUUID().toString()
    }
}
