package io.skjaere.debridav.qbittorrent

import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.repository.CategoryRepository
import io.skjaere.debridav.repository.TorrentFileRepository
import io.skjaere.debridav.repository.TorrentRepository
import io.skjaere.debridav.torrent.TorrentToMagnetConverter
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.net.URLDecoder
import java.time.Instant
import java.util.*

@Service
@Suppress("LongParameterList")
class TorrentService(
    private val debridService: DebridCachedContentService,
    private val fileService: FileService,
    private val debridavConfiguration: DebridavConfiguration,
    private val torrentRepository: TorrentRepository,
    private val torrentFileRepository: TorrentFileRepository,
    private val categoryRepository: CategoryRepository,
    private val arrService: ArrService,
    private val torrentToMagnetConverter: TorrentToMagnetConverter
) {
    private val logger = LoggerFactory.getLogger(TorrentService::class.java)
    fun addTorrent(category: String, torrent: MultipartFile) {
        addMagnet(
            category, torrentToMagnetConverter.convertTorrentToMagnet(torrent.bytes)
        )
    }

    fun addMagnet(category: String, magnet: String) = runBlocking {
        val debridFileContents = runBlocking { debridService.addContent(TorrentMagnet(magnet)) }
        val torrent = createTorrent(debridFileContents, category, magnet)

        if (debridFileContents.isEmpty()) {
            logger.debug("Received empty list of files from debrid service")
            arrService.markDownloadAsFailed(torrent.name!!, category)
        } else {
            debridFileContents.forEach {
                fileService.createDebridFile(
                    "${debridavConfiguration.downloadPath}/${torrent.name}/${it.originalPath}",
                    it
                )
            }
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
        torrent.name =
            getNameFromMagnet(magnet) ?: run {
                if (cachedFiles.isEmpty()) UUID.randomUUID().toString() else getTorrentNameFromDebridFileContent(
                    cachedFiles.first()
                )
            }
        torrent.created = Instant.now()
        torrent.hash = getHashFromMagnet(magnet)
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
        } == true
    }

    private fun getTorrentNameFromDebridFileContent(debridFileContents: DebridFileContents): String {
        val contentPath = debridFileContents.originalPath
        val updatedTorrentName = if (contentPath.contains("/")) {
            contentPath.substringBeforeLast("/")
        } else contentPath.substringBeforeLast(".")

        return updatedTorrentName
    }

    companion object {
        fun getNameFromMagnet(magnet: String): String? {
            return magnet.split("?").last().split("&")
                .map { it.split("=") }
                .associate { it.first() to it.last() }["dn"]
                ?.let {
                    URLDecoder.decode(it, Charsets.UTF_8.name())
                }
        }

        fun getHashFromMagnet(magnet: String): String? {
            return getParamsFromMagnet(magnet)["xt"]
                ?.let {
                    URLDecoder.decode(
                        it.substringAfterLast("urn:btih:"),
                        Charsets.UTF_8.name()
                    )
                }
        }

        private fun getParamsFromMagnet(magnet: String): Map<String, String> {
            return magnet.split("?").last().split("&")
                .map { it.split("=") }
                .associate { it.first() to it.last() }
        }

    }
}
