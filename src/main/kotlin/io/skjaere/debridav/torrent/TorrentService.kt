package io.skjaere.debridav.torrent

import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
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
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val torrentRepository: TorrentRepository,
    private val categoryService: CategoryService,
    private val arrService: ArrService,
    private val torrentToMagnetConverter: TorrentToMagnetConverter
) {
    private val logger = LoggerFactory.getLogger(TorrentService::class.java)

    @Transactional
    fun addTorrent(category: String, torrent: MultipartFile): Boolean {
        return addMagnet(
            category, torrentToMagnetConverter.convertTorrentToMagnet(torrent.bytes)
        )
    }

    @Transactional
    fun addMagnet(category: String, magnet: TorrentMagnet): Boolean = runBlocking {
        val debridFileContents = runBlocking { debridService.addContent(magnet) }

        if (debridFileContents.isEmpty()) {
            logger.info("${getNameFromMagnet(magnet)} is not cached in any debrid services")
            if (arrService.categoryIsMapped(category)) {
                val torrent = createTorrent(debridFileContents, category, magnet)
                arrService.markDownloadAsFailed(torrent.name!!, category)
                true
            } else false
        } else {
            createTorrent(debridFileContents, category, magnet)
            true
        }
    }

    fun createTorrent(
        cachedFiles: List<DebridFileContents>,
        categoryName: String,
        magnet: TorrentMagnet
    ): Torrent {
        val hash = getHashFromMagnet(magnet) ?: error("could not get hash from magnet")
        val torrent = torrentRepository.getByHashIgnoreCase(hash.hash) ?: Torrent()
        torrent.category = categoryService.findByName(categoryName)
            ?: run { categoryService.createCategory(categoryName) }
        torrent.name =
            getNameFromMagnet(magnet) ?: run {
                if (cachedFiles.isEmpty()) UUID.randomUUID().toString() else getTorrentNameFromDebridFileContent(
                    cachedFiles.first()
                )
            }
        torrent.created = Instant.now()
        torrent.hash = hash.hash
        torrent.status = Status.LIVE
        torrent.savePath =
            "${debridavConfigurationProperties.downloadPath}/${torrent.name}"
        torrent.files =
            cachedFiles.map {
                fileService.createDebridFile(
                    "${debridavConfigurationProperties.downloadPath}/${torrent.name}/${it.originalPath}",
                    getHashFromMagnet(magnet)!!.hash,
                    it
                )
            }.toMutableList()

        logger.info("Saving ${torrent.files.count()} files")
        return torrentRepository.save(torrent)
    }

    fun getTorrentsByCategory(categoryName: String): List<Torrent> {
        return categoryService.findByName(categoryName)?.let { category ->
            torrentRepository.findByCategoryAndStatus(category, Status.LIVE)
        } ?: emptyList()
    }


    fun getTorrentByHash(hash: TorrentHash): Torrent? {
        return torrentRepository.getByHashIgnoreCase(hash.hash)
    }

    @Transactional
    fun deleteTorrentByHash(hash: String) {
        return torrentRepository.deleteByHashIgnoreCase(hash)
    }

    private fun getTorrentNameFromDebridFileContent(debridFileContents: DebridFileContents): String {
        val contentPath = debridFileContents.originalPath
        val updatedTorrentName = if (contentPath!!.contains("/")) {
            contentPath.substringBeforeLast("/")
        } else contentPath.substringBeforeLast(".")

        return updatedTorrentName
    }


    companion object {
        val knownVideoExtensions = listOf(".mp4", ".mkv", ".avi", ".ts")
        fun getNameFromMagnet(magnet: TorrentMagnet): String? {
            return getParamsFromMagnet(magnet)["dn"]
                ?.let {
                    URLDecoder.decode(it, Charsets.UTF_8.name())
                }
        }

        fun getNameFromMagnetWithoutContainerExtension(magnet: TorrentMagnet): String? =
            getNameFromMagnet(magnet)?.withoutVideoContainerExtension()

        private fun String.withoutVideoContainerExtension(): String {
            knownVideoExtensions.forEach { extension ->
                if (this.endsWith(extension)) return this.substringBeforeLast(extension)
            }
            return this
        }

        fun getHashFromMagnet(magnet: TorrentMagnet): TorrentHash? {
            return getParamsFromMagnet(magnet)["xt"]
                ?.let {
                    URLDecoder.decode(
                        it.substringAfterLast("urn:btih:"),
                        Charsets.UTF_8.name()
                    ).let { TorrentHash(it) }
                }
        }

        private fun getParamsFromMagnet(magnet: TorrentMagnet): Map<String, String> {
            return magnet.magnet.split("?").last().split("&")
                .map { it.split("=") }
                .associate { it.first() to it.last() }
        }
    }
}
