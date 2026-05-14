package io.skjaere.debridav.torrent

import io.skjaere.debridav.arrs.ArrService
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
    private val torrentToMagnetConverter: TorrentToMagnetConverter,
    private val arrService: ArrService,
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
            // Fire-and-forget: nudge the matching arr to start importing the new
            // content immediately rather than waiting for its polling interval.
            // createTorrent's @Transactional commits before this returns, so the
            // arr will see the torrent in the qbit-emulation listing.
            runBlocking { arrService.refreshMonitoredDownloads(category) }
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
        torrent.category = categoryService.getOrCreateCategory(categoryName)
        torrent.name =
            getNameFromMagnet(magnet) ?: run {
                if (cachedFiles.isEmpty()) UUID.randomUUID().toString() else getTorrentNameFromDebridFileContent(
                    cachedFiles.first()
                )
            }
        torrent.created = Instant.now()
        torrent.hash = hash.hash
        torrent.magnet = magnet.magnet
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

    /**
     * All live torrents regardless of category. Used by the qBittorrent emulation
     * when /api/v2/torrents/info is called without a category filter — qBittorrent's
     * spec makes every query parameter on that endpoint optional, defaulting to "all".
     */
    fun getAllTorrents(): List<Torrent> = torrentRepository.findByStatus(Status.LIVE)


    fun getTorrentByHash(hash: TorrentHash): Torrent? {
        return torrentRepository.getByHashIgnoreCaseAndStatus(hash.hash, Status.LIVE)
    }

    @Transactional
    fun getTorrentFilesByHash(hash: TorrentHash): List<RemotelyCachedEntity>? {
        // Touch files inside the transaction so Hibernate initializes the lazy
        // collection before we hand it back to a controller (OSIV is off). Filtered to
        // LIVE so soft-deleted torrents are invisible to the qbit-emulation API even
        // though their rows survive in the DB for audit + reconstruction anchoring.
        return torrentRepository.getByHashIgnoreCaseAndStatus(hash.hash, Status.LIVE)?.files?.toList()
    }

    @Transactional
    fun deleteTorrentByHash(hash: String) {
        // Soft-delete: preserves the Torrent row, the torrent_files join entries, the magnet,
        // and the category. The arrs see the qbit-emulation listing exclude DELETED rows
        // (via getByHashIgnoreCaseAndStatus + findByCategoryAndStatus filtering on LIVE) so
        // from their perspective the torrent is gone. If the same magnet is re-added later
        // createTorrent's getByHashIgnoreCase upsert revives the row by setting status=LIVE.
        torrentRepository.getByHashIgnoreCase(hash)?.let { torrent ->
            torrentRepository.markTorrentAsDeleted(torrent)
        }
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
            // Use substringAfter("?") rather than split("?").last(): tracker URLs
            // (tr=) may contain unencoded '?' that would otherwise become the
            // split boundary and drop xt=/dn=. Likewise split("=", limit = 2)
            // preserves '=' inside values.
            return magnet.magnet.substringAfter("?").split("&")
                .map { it.split("=", limit = 2) }
                .associate { it[0] to it.getOrElse(1) { "" } }
        }
    }
}
