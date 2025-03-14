package io.skjaere.debridav.torrent

import com.fasterxml.jackson.annotation.JsonProperty
import io.skjaere.debridav.category.Category
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class QBittorrentEmulationController(
    private val torrentService: TorrentService,
    private val resourceLoader: ResourceLoader,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val categoryService: CategoryService
) {
    companion object {
        const val API_VERSION = "2.9.3"
    }

    private val logger = LoggerFactory.getLogger(QBittorrentEmulationController::class.java)

    @GetMapping("/api/v2/app/webapiVersion")
    fun version(): String = API_VERSION

    @GetMapping("/api/v2/torrents/categories")
    fun categories(): Map<String, Category> {
        return categoryService.getAllCategories().associateBy { it.name!! }
    }

    @RequestMapping(
        path = ["api/v2/torrents/createCategory"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    private fun createCategory(@RequestParam category: String): Category {
        return categoryService.createCategory(category)
    }

    @GetMapping("api/v2/app/preferences")
    fun preferences(): String {
        return resourceLoader
            .getResource("classpath:qbittorrent_properties_response.json")
            .getContentAsString(Charsets.UTF_8)
            .replace(
                "%DOWNLOAD_DIR%",
                "${debridavConfigurationProperties.mountPath}${debridavConfigurationProperties.downloadPath}"
            )
    }

    @GetMapping("/version/api")
    fun versionTwo(): ResponseEntity<String> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Not found")
    }

    data class TorrentsInfoRequestParams(
        val filter: String?,
        val category: String?,
        val tag: String?,
        val sort: String?,
        val reverse: Boolean?,
        val limit: Int?,
        val offset: Int?,
        val hashes: String?
    )

    @GetMapping("/api/v2/torrents/info")
    fun torrentsInfo(requestParams: TorrentsInfoRequestParams): List<TorrentsInfoResponse> {
        return torrentService
            .getTorrentsByCategory(requestParams.category!!)
            //.filter { it.files?.firstOrNull()?.originalPath != null }
            .map {
                TorrentsInfoResponse.ofTorrent(it, debridavConfigurationProperties.mountPath)
            }
    }

    @GetMapping("/api/v2/torrents/properties")
    fun torrentsProperties(@RequestParam hash: String): TorrentPropertiesResponse? {
        return torrentService.getTorrentByHash(hash)?.let {
            TorrentPropertiesResponse.ofTorrent(it)
        }
    }

    @Suppress("MagicNumber")
    @GetMapping("/api/v2/torrents/files")
    fun torrentFiles(@RequestParam hash: String): List<TorrentFilesResponse>? {
        return torrentService.getTorrentByHash(hash)?.let {
            it.files.map { torrentFile ->
                TorrentFilesResponse(
                    0,
                    torrentFile.contents!!.originalPath!!,
                    torrentFile.size!!.toInt(),
                    100,
                    1,
                    true,
                    pieceRange = listOf(1, 100),
                    availability = 1.0.toFloat()
                )
            }
        }
    }

    @RequestMapping(
        path = ["/api/v2/torrents/add"],
        method = [RequestMethod.POST],
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun addTorrent(
        @RequestPart urls: String?,
        @RequestPart torrents: MultipartFile?,
        @RequestPart category: String,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        logger.info("${request.method} ${request.requestURL}")
        val result = urls?.let {
            torrentService.addMagnet(category, it)
        } ?: run {
            torrents?.let {
                torrentService.addTorrent(category, it)
            }
        }
        return when (result) {
            null -> ResponseEntity.badRequest().body("Request body must contain either urls or torrents")
            true -> ResponseEntity.ok("ok")
            false -> ResponseEntity.unprocessableEntity().build()
        }
    }

    @RequestMapping(
        path = ["/api/v2/torrents/add"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun addTorrentFile(
        request: AddTorrentRequest
    ): ResponseEntity<String> {
        return if (torrentService.addMagnet(request.category, request.urls)) {
            ResponseEntity.ok("")
        } else {
            ResponseEntity.unprocessableEntity().build()
        }
    }

    data class AddTorrentRequest(
        val urls: String,
        val category: String
    )

    @RequestMapping(
        path = ["api/v2/torrents/delete"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun deleteTorrents(
        @RequestParam hashes: List<String>
    ): ResponseEntity<String> {
        hashes.forEach {
            torrentService.deleteTorrentByHash(it)
        }

        return ResponseEntity.ok("ok")
    }

    data class TorrentFilesResponse(
        val index: Int,
        val name: String,
        val size: Int,
        val progress: Int,
        val priority: Int,
        @JsonProperty("is_seed")
        val isSeed: Boolean,
        @JsonProperty("piece_range")
        val pieceRange: List<Int>,
        val availability: Float
    )
}
