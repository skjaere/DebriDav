package io.skjaere.debridav.torrent

import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
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
    private val torrentToMagnetConverter: TorrentToMagnetConverter
) {
    private val logger = LoggerFactory.getLogger(TorrentService::class.java)

    fun addTorrent(category: String, torrent: MultipartFile): Boolean {
        return addMagnet(
            category, torrentToMagnetConverter.convertTorrentToMagnet(torrent.bytes)
        )
    }

    fun addMagnet(category: String, magnet: TorrentMagnet): Boolean {
        val debridFileContents = runBlocking { debridService.addContent(magnet) }

        return if (debridFileContents.isEmpty()) {
            logger.info("${getNameFromMagnet(magnet)} is not cached in any debrid services")
            false
        } else {
            createTorrent(debridFileContents, category, magnet)
            true
        }
    }

    @Transactional
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
        val torrentBasePath = "${debridavConfigurationProperties.downloadPath}/${torrent.name}"
        torrent.savePath = torrentBasePath
        torrent.files = fileService.createDebridFiles(
            cachedFiles.map { "$torrentBasePath/${it.originalPath}" to it },
            hash.hash,
        ).toMutableList()

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
    fun getTorrentFilesByHash(hash: TorrentHash): List<RemotelyCachedEntity>? {
        // Touch files inside the transaction so Hibernate initializes the lazy
        // collection before we hand it back to a controller (OSIV is off).
        return torrentRepository.getByHashIgnoreCase(hash.hash)?.files?.toList()
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
