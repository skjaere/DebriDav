package io.skjaere.debridav.qbittorrent

import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridTorrentService
import io.skjaere.debridav.fs.DebridTorrentFileContents
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.repository.CategoryRepository
import io.skjaere.debridav.repository.TorrentFileRepository
import io.skjaere.debridav.repository.TorrentRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.*

@Service
class TorrentService(
    private val debridTorrentService: DebridTorrentService,
    private val fileService: FileService,
    private val debridavConfiguration: DebridavConfiguration,
    private val torrentRepository: TorrentRepository,
    private val torrentFileRepository: TorrentFileRepository,
    private val categoryRepository: CategoryRepository
) {
    private val logger = LoggerFactory.getLogger(TorrentService::class.java)

    fun addTorrent(category: String, magnet: String): Boolean = runBlocking {
        val debridFileContents = runBlocking { debridTorrentService.addMagnet(magnet) }
        if (debridFileContents.isEmpty()) {
            logger.debug("Received empty list of files from debrid service")
            false
        } else {
            val torrent = createTorrent(debridFileContents, category, magnet)
            debridFileContents.forEach {
                fileService.createDebridFile(
                    "${debridavConfiguration.downloadPath}/${torrent.name}/${it.originalPath}",
                    it
                )
            }
            true
        }
    }


    private fun createTorrent(
        cachedFiles: List<DebridTorrentFileContents>,
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
        category.downloadPath = debridavConfiguration.downloadPath
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

    private val hexArray: CharArray = "0123456789ABCDEF".toCharArray()

    @Suppress("MagicNumber")
    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    companion object {
        fun getNameFromMagnet(magnet: String): String {
            return magnet.split("?").last().split("&")
                .map { it.split("=") }
                .associate { it.first() to it.last() }["dn"]
                ?.let {
                    URLDecoder.decode(it, Charsets.UTF_8.name())
                } ?: UUID.randomUUID().toString()
        }
    }
}
