package io.skjaere.debridav.torrent

import com.fasterxml.jackson.annotation.JsonProperty
import io.skjaere.debridav.category.Category
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.config.auth.JwtService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.TorrentMagnet
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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

private const val MAGNET_HASH_LENGTH = 120

@RestController
class QBittorrentEmulationController(
    private val torrentService: TorrentService,
    private val resourceLoader: ResourceLoader,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val categoryService: CategoryService,
    private val jwtService: JwtService,
) {
    companion object {
        const val API_VERSION = "2.9.3"
        const val APP_VERSION = "v4.5.5"
        private const val SID_COOKIE = "SID"
    }

    private val logger = LoggerFactory.getLogger(QBittorrentEmulationController::class.java)

    @GetMapping("/api/v2/app/webapiVersion")
    fun webapiVersion(): String = API_VERSION

    // Separate from /webapiVersion — Sportarr (and probably others) test connectivity
    // by hitting this endpoint after login, treating a 200 here as success and 404 as
    // a failed connection. Matches qBittorrent's wire format: leading 'v' + semver.
    @GetMapping("/api/v2/app/version")
    fun appVersion(): String = APP_VERSION

    /**
     * qBittorrent's quirky login: status is 200 either way; success is signaled by the
     * literal body `Ok.` plus a `SID` session cookie, failure by the body `Fails.`.
     * We piggyback on the existing webdav credentials and stuff the issued JWT into
     * the SID cookie so [io.skjaere.debridav.config.auth.JwtAuthenticationFilter] can
     * validate subsequent qBittorrent-style requests from the same cookie when the
     * caller doesn't supply an Authorization header.
     */
    @RequestMapping(
        path = ["/api/v2/auth/login"],
        method = [RequestMethod.POST],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    )
    fun login(
        @RequestParam username: String,
        @RequestParam password: String,
        response: HttpServletResponse
    ): ResponseEntity<String> {
        val expectedUsername = debridavConfigurationProperties.webdavUsername
        val expectedPassword = debridavConfigurationProperties.webdavPassword

        // Fail-closed when WebDAV credentials aren't configured. Previously this branch
        // minted a valid JWT for any supplied username — meaning a default install with
        // blank creds left /api/v2/** open to anyone who could reach the port. Now: no
        // creds = no login. qBittorrent's failed-login convention is 200 + body "Fails.",
        // so clients see the same surface as a wrong-password attempt.
        if (expectedUsername.isNullOrBlank() || expectedPassword.isNullOrBlank()) {
            logger.warn(
                "qBittorrent login attempted but no WebDAV credentials are configured — " +
                    "rejecting. Set debridav.webdav-username and debridav.webdav-password to enable login."
            )
            return failsResponse()
        }

        if (username != expectedUsername || password != expectedPassword) {
            return failsResponse()
        }

        response.addCookie(buildSidCookie(jwtService.generateToken(username)))
        return okResponse()
    }

    @RequestMapping(
        path = ["/api/v2/auth/logout"],
        method = [RequestMethod.POST]
    )
    fun logout(response: HttpServletResponse): ResponseEntity<String> {
        response.addCookie(Cookie(SID_COOKIE, "").apply {
            path = "/"
            maxAge = 0
            isHttpOnly = true
        })
        return okResponse()
    }

    private fun buildSidCookie(token: String): Cookie = Cookie(SID_COOKIE, token).apply {
        path = "/"
        isHttpOnly = true
    }

    // qBittorrent's responses are text/plain — Spring's default String->JSON serializer
    // would otherwise wrap `Ok.` in quotes. Force the content type.
    private fun okResponse(): ResponseEntity<String> = ResponseEntity
        .status(HttpStatus.OK)
        .contentType(MediaType.TEXT_PLAIN)
        .body("Ok.")

    private fun failsResponse(): ResponseEntity<String> = ResponseEntity
        .status(HttpStatus.OK)
        .contentType(MediaType.TEXT_PLAIN)
        .body("Fails.")

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
        // qBittorrent spec: every query param here is optional. If `category` is not
        // supplied, return all live torrents — *arr poll-style clients (Sportarr, Sonarr
        // status checks, Prowlarr health probes) hit this without a filter, and we'd
        // previously NPE on `category!!`.
        val torrents = requestParams.category
            ?.let { torrentService.getTorrentsByCategory(it) }
            ?: torrentService.getAllTorrents()
        return torrents.map {
            TorrentsInfoResponse.ofTorrent(it, debridavConfigurationProperties.mountPath)
        }
    }

    @GetMapping("/api/v2/torrents/properties")
    fun torrentsProperties(@RequestParam hash: TorrentHash): TorrentPropertiesResponse? {
        return torrentService.getTorrentByHash(hash)?.let {
            TorrentPropertiesResponse.ofTorrent(it)
        }
    }

    @Suppress("MagicNumber")
    @GetMapping("/api/v2/torrents/files")
    fun torrentFiles(@RequestParam hash: TorrentHash): List<TorrentFilesResponse>? {
        return torrentService.getTorrentFilesByHash(hash)?.let { files ->
            files.map { torrentFile ->
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
        // Optional — the bundled UI's add-torrent form only includes `category` when the
        // user filled it in. qBittorrent's protocol treats category as optional too;
        // making this `@RequestPart category: String` (non-null) caused the request to
        // fail with 400 "Required request part 'category' is not present" whenever the
        // field was left empty, which is the normal path for "just add this magnet".
        @RequestPart(required = false) category: String?,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        logger.info("${request.method} ${request.requestURL}")
        val categoryValue = category.orEmpty()
        urls?.let { rejectMalformedMagnet(it) }?.let { return it }
        val result = urls?.let {
            torrentService.addMagnet(categoryValue, TorrentMagnet(it))
        } ?: run {
            torrents?.let {
                torrentService.addTorrent(categoryValue, it)
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
        rejectMalformedMagnet(request.urls)?.let { return it }
        return if (torrentService.addMagnet(request.category, TorrentMagnet(request.urls))) {
            ResponseEntity.ok("")
        } else {
            ResponseEntity.unprocessableEntity().build()
        }
    }

    /**
     * Returns a 400 ResponseEntity if [magnetUrl] doesn't carry an `xt=urn:btih:...`
     * hash of 40 hex characters, otherwise null. Without one, the downstream RealDebrid
     * path can't do its by-hash DB lookup (used to NPE), and `TorrentService.createTorrent`
     * would also fail with `error("could not get hash from magnet")` — so reject early
     * with a clear 400 instead of a 5xx surfaced from deeper in the stack. A base32 hash
     * counts as malformed: [io.skjaere.debridav.torrent.TorrentHash] takes 40 characters
     * only. Logs the magnet prefix (truncated) for diagnosis since the exception itself
     * doesn't carry the offending URL.
     */
    private fun rejectMalformedMagnet(magnetUrl: String): ResponseEntity<String>? {
        val hash = runCatching { TorrentMagnet(magnetUrl).getHash() }.getOrNull()
        return if (hash == null) {
            logger.warn(
                "Rejecting malformed magnet (no 40-character btih hash): {}", magnetUrl.take(MAGNET_HASH_LENGTH)
            )
            ResponseEntity.badRequest()
                .body("Malformed magnet: needs an xt=urn:btih: parameter with a 40-character hex hash")
        } else {
            null
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
