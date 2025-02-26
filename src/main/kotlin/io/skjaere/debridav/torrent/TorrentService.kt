package io.skjaere.debridav.torrent

import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridFileContents
import jakarta.transaction.Transactional
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
    private val fileService: DatabaseFileService,
    private val debridavConfiguration: DebridavConfiguration,
    private val torrentRepository: TorrentRepository,
    private val categoryService: CategoryService,
    private val arrService: ArrService,
    private val torrentToMagnetConverter: TorrentToMagnetConverter
) {
    private val logger = LoggerFactory.getLogger(TorrentService::class.java)

    @Transactional
    fun addTorrent(category: String, torrent: MultipartFile) {
        addMagnet(
            category, torrentToMagnetConverter.convertTorrentToMagnet(torrent.bytes)
        )
    }

    @Transactional
    fun addMagnet(category: String, magnet: String) = runBlocking {
        val debridFileContents = runBlocking { debridService.addContent(TorrentMagnet(magnet)) }
        val torrent = createTorrent(debridFileContents, category, magnet)

        if (debridFileContents.isEmpty()) {
            logger.info("${torrent.name} is not cached in any debrid services")
            arrService.markDownloadAsFailed(torrent.name!!, category)
        }
    }

    private fun createTorrent(
        cachedFiles: List<DebridFileContents>,
        categoryName: String,
        magnet: String
    ): Torrent {
        val torrent = Torrent()
        torrent.category = categoryService.findByName(categoryName)
            ?: run { categoryService.createCategory(categoryName) }
        torrent.name =
            getNameFromMagnet(magnet) ?: run {
                if (cachedFiles.isEmpty()) UUID.randomUUID().toString() else getTorrentNameFromDebridFileContent(
                    cachedFiles.first()
                )
            }
        torrent.created = Instant.now()
        torrent.hash = getHashFromMagnet(magnet)
        torrent.savePath =
            "${debridavConfiguration.downloadPath}/${URLDecoder.decode(torrent.name, Charsets.UTF_8.name())}"
        torrent.files.addAll(
            cachedFiles.map {
                fileService.createDebridFile(
                    "${debridavConfiguration.downloadPath}/${torrent.name}/${it.originalPath}",
                    it
                )

            }
        )
        logger.info("Saving ${torrent.files.count()} files")
        return torrentRepository.save(torrent)
    }

    fun getTorrentsByCategory(categoryName: String): List<Torrent> {
        return categoryService.findByName(categoryName)?.let { category ->
            torrentRepository.findByCategoryAndStatus(category, Status.LIVE)
        } ?: emptyList()
    }


    fun getTorrentByHash(hash: String): Torrent? {
        return torrentRepository.getByHash(hash)
    }

    @Transactional
    fun deleteTorrentByHash(hash: String): Boolean {
        return torrentRepository.getByHash(hash.uppercase())?.let {
            torrentRepository.markTorrentAsDeleted(it)
            true
        } == true
    }

    private fun getTorrentNameFromDebridFileContent(debridFileContents: DebridFileContents): String {
        val contentPath = debridFileContents.originalPath
        val updatedTorrentName = if (contentPath!!.contains("/")) {
            contentPath.substringBeforeLast("/")
        } else contentPath.substringBeforeLast(".")

        return updatedTorrentName
    }


    companion object {
        fun getNameFromMagnet(magnet: String): String? {
            return getParamsFromMagnet(magnet)["dn"]
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
