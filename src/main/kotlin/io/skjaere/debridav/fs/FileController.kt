package io.skjaere.debridav.fs

import io.skjaere.debridav.config.auth.JwtService
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/files")
class FileController(
    private val databaseFileService: DatabaseFileService,
    private val jwtService: JwtService
) {
    @Suppress("ReturnCount")
    @GetMapping("/stream-url")
    fun streamUrl(@RequestParam path: String): ResponseEntity<StreamUrlDto> {
        val entity = databaseFileService.getFileAtPath(path)
            ?: return ResponseEntity.notFound().build()

        if (entity is DbDirectory) {
            return ResponseEntity.badRequest().build()
        }

        val token = jwtService.generateStreamToken(path)
        return ResponseEntity.ok(
            StreamUrlDto(
                url = "/api/v1/stream/t/$token",
                expiresIn = JwtService.STREAM_TOKEN_EXPIRY_SECONDS
            )
        )
    }

    @Suppress("ReturnCount")
    @GetMapping("/detail")
    fun detail(@RequestParam path: String): ResponseEntity<FileDetailDto> {
        val entity = databaseFileService.getFileAtPath(path)
            ?: return ResponseEntity.notFound().build()

        if (entity is DbDirectory) {
            return ResponseEntity.badRequest().build()
        }

        val dto = when (entity) {
            is RemotelyCachedEntity -> buildRemoteDetail(entity, path)
            is LocalEntity -> buildLocalDetail(entity, path)
            else -> return ResponseEntity.badRequest().build()
        }

        return ResponseEntity.ok(dto)
    }

    private fun buildRemoteDetail(entity: RemotelyCachedEntity, path: String): FileDetailDto {
        val contents = entity.contents
        val fileType = when (contents) {
            is DebridCachedTorrentContent -> FileType.TORRENT
            is DebridCachedUsenetReleaseContent -> FileType.USENET_RELEASE
            is NzbContents -> FileType.NZB
            else -> FileType.LOCAL
        }
        val providerStatus = contents?.debridLinks?.mapNotNull { link ->
            val provider = link.provider ?: return@mapNotNull null
            val status = when (link) {
                is CachedFile -> ProviderCacheStatus.CACHED
                is MissingFile -> ProviderCacheStatus.MISSING
                is ProviderError -> ProviderCacheStatus.PROVIDER_ERROR
                is ClientError -> ProviderCacheStatus.CLIENT_ERROR
                is NetworkError -> ProviderCacheStatus.NETWORK_ERROR
                else -> ProviderCacheStatus.UNKNOWN_ERROR
            }
            ProviderStatusDto(
                provider = provider,
                status = status,
                lastChecked = link.lastChecked
            )
        }
        return FileDetailDto(
            name = entity.name ?: "",
            path = path,
            size = entity.size,
            lastModified = entity.lastModified,
            mimeType = entity.mimeType,
            fileType = fileType,
            hash = entity.hash,
            providerStatus = providerStatus
        )
    }

    private fun buildLocalDetail(entity: LocalEntity, path: String): FileDetailDto {
        return FileDetailDto(
            name = entity.name ?: "",
            path = path,
            size = entity.size,
            lastModified = entity.lastModified,
            mimeType = entity.mimeType,
            fileType = FileType.LOCAL,
            hash = null,
            providerStatus = null
        )
    }

    @Suppress("ReturnCount")
    @GetMapping
    fun list(@RequestParam(defaultValue = "/") path: String): ResponseEntity<List<FileEntryDto>> {
        val entity = databaseFileService.getFileAtPath(path)
            ?: return ResponseEntity.notFound().build()

        if (entity !is DbDirectory) {
            return ResponseEntity.badRequest().build()
        }

        val children = runBlocking { databaseFileService.getChildren(entity) }

        val entries = children.mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            when (child) {
                is DbDirectory -> FileEntryDto(
                    name = name,
                    path = child.fileSystemPath() ?: path,
                    isDirectory = true,
                    size = null,
                    lastModified = child.lastModified,
                    mimeType = null
                )
                else -> FileEntryDto(
                    name = name,
                    path = "${path.trimEnd('/')}/$name",
                    isDirectory = false,
                    size = child.size,
                    lastModified = child.lastModified,
                    mimeType = child.mimeType
                )
            }
        }.sortedWith(compareByDescending<FileEntryDto> { it.isDirectory }.thenBy { it.name.lowercase() })

        return ResponseEntity.ok(entries)
    }
}
