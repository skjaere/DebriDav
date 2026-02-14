package io.skjaere.debridav.fs.import

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.ClientError
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.NetworkError
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.fs.Blob
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.LocalEntity
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.fs.legacy.DebridFileContents
import io.skjaere.debridav.fs.legacy.DebridFileContents.Type
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentRepository
import io.skjaere.debridav.torrent.TorrentService
import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.UsenetDownloadStatus
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.hibernate.engine.jdbc.proxy.BlobProxy
import org.slf4j.LoggerFactory
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


@Service
@Suppress("LongParameterList")
class FileSystemImportService(
    private val databaseFileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val usenetRepository: UsenetRepository,
    private val importRegistryRepository: ImportRegistryRepository,
    private val torrentRepository: TorrentRepository,
    private val debridFileContentsRepository: DebridFileContentsRepository,
    platformTransactionManager: PlatformTransactionManager,
    categoryService: CategoryService
) : HealthIndicator {
    private val logger = LoggerFactory.getLogger(DatabaseFileService::class.java)
    private val ignoredFiles = listOf("lb-db.mv.db")
    private val importCategory: Category =
        categoryService.findByName("imported") ?: categoryService.createCategory("imported")
    private val transactionTemplate = TransactionTemplate(platformTransactionManager)
    private var isImporting = true

    @EventListener(ApplicationReadyEvent::class)
    fun startImport() {
        if (debridavConfigurationProperties.enableFileImportOnStartup) {
            runBlocking {
                importDebridFilesFromFileSystem()
            }
        }
        isImporting = false
    }

    suspend fun importDebridFilesFromFileSystem() = coroutineScope {
        launch {
            saveEntity(
                deserializeFiles(
                    getFlowOfFilesToImport()
                )
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.deserializeFiles(channel: ReceiveChannel<ImportContext>): ReceiveChannel<ImportContext> =
        produce {
            channel.consumeEach { ctx ->
                if (ctx.file.isDebridFile()) {
                    send(
                        ctx.copy(
                            fileContents = Json.decodeFromString(
                                DebridFileContents.serializer(),
                                ctx.path.toFile().readText()
                            )
                        )
                    )
                } else send(ctx)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getFlowOfFilesToImport(): ReceiveChannel<ImportContext> =
        this.produce {
            if (!Path.of(debridavConfigurationProperties.rootPath).exists()) {
                logger.warn(
                    "Can't start import. Root directory does not exist: " +
                            debridavConfigurationProperties.rootPath
                )
                return@produce
            }
            Files
                .walk(Paths.get(debridavConfigurationProperties.rootPath))
                .use { files ->
                    val filesList = files.toList()
                    filesList.asSequence()
                        .filter { it.isRegularFile() }
                        .filter { !ignoredFiles.contains(it.name.substringAfterLast('/')) }
                        .filter { !importRegistryRepository.existsByPath(it.toString()) }
                        .forEach { path -> send(ImportContext(path, path.toFile(), null)) }
                }

        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun saveEntity(channel: ReceiveChannel<ImportContext>) {
        channel.consumeEach { ctx ->
            try {
                transactionTemplate.execute {
                    val entity = mapFileToDbEntity(ctx)
                    when (entity) {
                        is RemotelyCachedEntity -> saveRemotelyCachedEntity(entity, ctx)
                        is LocalEntity -> debridFileContentsRepository.save(entity)
                    }
                    importRegistryRepository.save(FileImport(ctx.path.toString()))
                    logger.info("${ctx.path} was successfully imported")
                }
            } catch (e: DataIntegrityViolationException) {
                logger.error("An error occurred during import of ${ctx.path}:${e.message}")
            }
        }
    }

    private fun saveRemotelyCachedEntity(
        entity: RemotelyCachedEntity,
        ctx: ImportContext
    ) {
        when (entity.contents) {
            is DebridCachedTorrentContent -> saveTorrentEntity(ctx, entity)
            is DebridCachedUsenetReleaseContent -> saveUsenetEntity(ctx, entity)
        }
    }

    private fun saveTorrentEntity(ctx: ImportContext, entity: DbEntity) {
        val torrent: Torrent = TorrentService.getHashFromMagnet(
            TorrentMagnet(ctx.fileContents!!.magnet)
        )?.let { hash ->
            torrentRepository.getByHashIgnoreCase(hash.hash) ?: run {
                val newTorrent = Torrent()
                newTorrent.name = TorrentService.getNameFromMagnet(TorrentMagnet(ctx.fileContents.magnet))
                newTorrent.hash = hash.hash
                newTorrent.category = importCategory
                newTorrent.savePath = ""
                newTorrent
            }
        } ?: run {
            error("Could not get hash from torrent. File: ${ctx.path} cannot be imported")
        }
        torrent.files.add(entity as RemotelyCachedEntity)
        torrentRepository.save(torrent)
    }

    @Suppress("MagicNumber")
    private fun saveUsenetEntity(ctx: ImportContext, entity: DbEntity) {
        val usenetDownload: UsenetDownload =
            usenetRepository.getByName(ctx.fileContents!!.magnet)
                ?: run {
                    val newUsenetDownload = UsenetDownload()
                    newUsenetDownload.category = importCategory
                    newUsenetDownload.name = ctx.fileContents.magnet
                    newUsenetDownload.status = UsenetDownloadStatus.DELETED
                    newUsenetDownload.percentCompleted = 100.0

                    newUsenetDownload
                }

        usenetDownload.debridFiles.add(entity as RemotelyCachedEntity)
        usenetRepository.save(usenetDownload)
    }

    private fun mapFileToDbEntity(ctx: ImportContext): DbEntity {
        val file = ctx.path.toFile()
        return if (file.isDebridFile()) {
            mapDebridFileToRemotelyCachedItem(ctx)
        } else {
            mapLocalFileToLocalEntity(ctx.path.toFile())
        }
    }

    private fun mapLocalFileToLocalEntity(file: File): LocalEntity {
        val entity = LocalEntity()
        entity.directory = databaseFileService.getOrCreateDirectory(
            file.path
                .substringAfterLast(debridavConfigurationProperties.rootPath)
                .substringBeforeLast("/")
        )
        entity.name = file.name
        entity.lastModified = file.lastModified()
        entity.size = file.length()
        entity.blob = Blob(BlobProxy.generateProxy(file.inputStream(), file.length()), file.length())

        return entity
    }

    private fun mapDebridFileToRemotelyCachedItem(ctx: ImportContext): RemotelyCachedEntity {
        val entity = RemotelyCachedEntity()
        entity.name = ctx.file.path.getFileNameFromDebridFile()
        entity.directory = databaseFileService.getOrCreateDirectory(
            ctx.file.path
                .substringAfterLast(debridavConfigurationProperties.rootPath)
                .substringBeforeLast("/")
        )
        entity.lastModified = ctx.file.lastModified()

        return if (ctx.fileContents!!.type == Type.TORRENT_MAGNET) {
            mapFileContentsToDebridCachedTorrentContent(ctx.fileContents, entity)
        } else if (ctx.fileContents.type == Type.USENET_RELEASE) {
            mapFileContentsToDebridCachedUsenetReleaseContent(ctx.fileContents, entity)
        } else {
            error("unknown type: ${ctx.fileContents.type}")
        }
    }

    private fun String.getFileNameFromDebridFile(): String =
        this.substringAfterLast("/").substringBeforeLast(".debridfile")

    private fun mapFileContentsToDebridCachedUsenetReleaseContent(
        deserializedDebridFileContents: DebridFileContents,
        entity: RemotelyCachedEntity
    ): RemotelyCachedEntity {
        val contents = DebridCachedUsenetReleaseContent()
        contents.releaseName = deserializedDebridFileContents.magnet
        contents.originalPath = deserializedDebridFileContents.originalPath
        contents.size = deserializedDebridFileContents.size
        contents.debridLinks =
            mapLegacyDebridLinksToDebridLinks(deserializedDebridFileContents.debridLinks).toMutableList()
        entity.contents = contents
        return entity
    }

    private fun mapFileContentsToDebridCachedTorrentContent(
        deserializedDebridFileContents: DebridFileContents,
        entity: RemotelyCachedEntity
    ): RemotelyCachedEntity {
        val contents = DebridCachedTorrentContent()
        contents.magnet = deserializedDebridFileContents.magnet
        contents.originalPath = deserializedDebridFileContents.originalPath
        contents.size = deserializedDebridFileContents.size
        contents.debridLinks = mapLegacyDebridLinksToDebridLinks(
            deserializedDebridFileContents.debridLinks
        ).toMutableList()
        entity.contents = contents
        return entity
    }

    private fun mapLegacyDebridLinksToDebridLinks(
        legacyDebridLinks: List<io.skjaere.debridav.debrid.model.DebridFile>
    ): List<io.skjaere.debridav.fs.DebridFile> {
        return legacyDebridLinks.map {
            when (it) {
                is NetworkError -> io.skjaere.debridav.fs.NetworkError(it.provider.toNewProvider(), it.lastChecked)
                is CachedFile -> mapLegacyCachedFileToCachedFile(it)
                is ClientError -> io.skjaere.debridav.fs.ClientError(it.provider.toNewProvider(), it.lastChecked)
                is MissingFile -> io.skjaere.debridav.fs.MissingFile(it.provider.toNewProvider(), it.lastChecked)
                is ProviderError -> io.skjaere.debridav.fs.ProviderError(
                    it.provider.toNewProvider(),
                    it.lastChecked
                )
            }
        }
    }

    private fun mapLegacyCachedFileToCachedFile(cachedFile: CachedFile): io.skjaere.debridav.fs.CachedFile {
        return io.skjaere.debridav.fs.CachedFile(
            path = cachedFile.path,
            size = cachedFile.size,
            mimeType = cachedFile.mimeType,
            link = cachedFile.link!!,
            params = cachedFile.params,
            lastChecked = cachedFile.lastChecked,
            provider = cachedFile.provider.toNewProvider()
        )
    }

    fun DebridProvider.toNewProvider(): DebridProvider {
        return when (this) {
            DebridProvider.REAL_DEBRID -> DebridProvider.REAL_DEBRID
            DebridProvider.PREMIUMIZE -> DebridProvider.PREMIUMIZE
            DebridProvider.EASYNEWS -> DebridProvider.EASYNEWS
            DebridProvider.TORBOX -> DebridProvider.TORBOX
        }
    }

    private fun File.isDebridFile(): Boolean = this.path.endsWith(".debridfile")

    override fun health(): Health =
        Health.status(
            if (isImporting) "DOWN" else "UP"
        ).build()


    data class ImportContext(
        val path: Path,
        val file: File,
        val fileContents: DebridFileContents?
    )
}
