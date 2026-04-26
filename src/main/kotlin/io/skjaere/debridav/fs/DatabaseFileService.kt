package io.skjaere.debridav.fs

import io.ipfs.multibase.Base58
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.rclone.FileSystemChangedEvent
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.torrent.TorrentRepository
import jakarta.persistence.EntityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.Strings
import org.hibernate.engine.jdbc.proxy.BlobProxy
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val ROOT_NODE = "ROOT"
private const val MEGABYTE = 1024 * 1024

@Service
@Suppress("TooManyFunctions")
class DatabaseFileService(
    private val debridFileRepository: DebridFileContentsRepository,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val torrentRepository: TorrentRepository,
    private val usenetRepository: UsenetRepository,
    private val entityManager: EntityManager,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(DatabaseFileService::class.java)
    private val lock = ReentrantLock()
    private val defaultDirectories = listOf("/", "/downloads", "/tv", "/movies")

    init {
        defaultDirectories.forEach {
            if (debridFileRepository.getDirectoryByPath(it.pathToLtree()) == null) {
                createDirectory(it)
            }
        }
    }

    @Transactional
    fun createDebridFile(
        path: String, hash: String, debridFileContents: DebridFileContents
    ): RemotelyCachedEntity {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val name = path.substringAfterLast("/")
        val entity = buildDebridFileEntity(path, name, hash, directory, debridFileContents)
        emitChange(parentOf(path))
        return entity
    }

    /**
     * Batch variant of [createDebridFile]. Pre-resolves the unique parent directories
     * once instead of N times AND prefetches existing entities at the (directory, name)
     * pairs in a single query, so the per-file existence check doesn't trigger an
     * auto-flush on every iteration (which would defeat hibernate.jdbc.batch_size).
     */
    @Transactional
    fun createDebridFiles(
        files: List<Pair<String, DebridFileContents>>,
        hash: String,
    ): List<RemotelyCachedEntity> {
        val parentByPath = files.associate { (path, _) -> path to path.substringBeforeLast("/") }
        val dirCache = parentByPath.values.toSet().associateWith { getOrCreateDirectory(it) }

        // One query for all potential collisions; filter to exact (dir, name) hits.
        val targets = files.map { (path, _) ->
            dirCache.getValue(parentByPath.getValue(path)) to path.substringAfterLast("/")
        }
        val existingByKey = if (targets.isEmpty()) {
            emptyMap()
        } else {
            debridFileRepository.findAllByDirectoryInAndNameIn(
                targets.map { it.first }.distinct(),
                targets.map { it.second }.distinct(),
            ).associateBy { it.directory!! to it.name!! }
        }

        val emittedParents = mutableSetOf<String>()
        return files.map { (path, contents) ->
            val name = path.substringAfterLast("/")
            val directory = dirCache.getValue(parentByPath.getValue(path))
            val entity = buildDebridFileEntity(path, name, hash, directory, contents, existingByKey[directory to name])
            emittedParents.add(parentOf(path))
            entity
        }.also { emitChanges(emittedParents) }
    }

    private fun buildDebridFileEntity(
        path: String,
        name: String,
        hash: String,
        directory: DbDirectory,
        contents: DebridFileContents,
        existing: DbEntity? = debridFileRepository.findByDirectoryAndName(directory, name),
    ): RemotelyCachedEntity {
        // Overwrite file if it exists
        existing?.let {
            it as? RemotelyCachedEntity ?: error("type ${it.javaClass.simpleName} exists at path $path")
            when (it.contents) {
                is DebridCachedTorrentContent -> debridFileRepository.unlinkFileFromTorrents(it)
                is DebridCachedUsenetReleaseContent -> debridFileRepository.unlinkFileFromUsenet(it)
                is NzbContents -> debridFileRepository.unlinkFileFromUsenet(it)
            }
            debridFileRepository.deleteDbEntityByHash(it.hash!!) // TODO: why doesn't debridFileRepository.delete() work?
        }
        val fileEntity = RemotelyCachedEntity()
        fileEntity.name = name
        fileEntity.lastModified = Instant.now().toEpochMilli()
        fileEntity.size = contents.size
        fileEntity.mimeType = contents.mimeType
        fileEntity.directory = directory
        fileEntity.contents = contents
        fileEntity.hash = hash
        logger.debug("Creating ${directory.path}/${fileEntity.name}")
        return fileEntity
    }

    @Transactional
    fun saveDbEntity(dbItem: DbEntity) {
        when (dbItem) {
            is RemotelyCachedEntity -> {
                debridFileRepository.save(dbItem)
            }

            else -> error("Cant write DebridFileContents to ${dbItem.javaClass.simpleName}")
        }
    }

    @Transactional
    fun writeDebridFileContentsToFile(dbItem: DbEntity, debridFileContents: DebridFileContents) {
        when (dbItem) {
            is RemotelyCachedEntity -> {
                dbItem.contents = debridFileContents
                debridFileRepository.save(dbItem)
            }

            else -> error("Cant write DebridFileContents to ${dbItem.javaClass.simpleName}")
        }
    }

    @Transactional
    fun writeContentsToLocalFile(dbItem: LocalEntity, contents: InputStream, size: Long) {
        if (size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
            && debridavConfigurationProperties.localEntityMaxSizeMb != 0
        ) {
            throw IllegalArgumentException(
                "Size: ${size / MEGABYTE} MB is greater than set maximum: " +
                        "${debridavConfigurationProperties.localEntityMaxSizeMb}"
            )
        }
        dbItem.blob!!.localContents = BlobProxy.generateProxy(contents, size)
        debridFileRepository.save(dbItem)
    }

    @Transactional
    fun moveResource(dbItem: DbEntity, destination: String, name: String) {
        when (dbItem) {
            is RemotelyCachedEntity -> moveFile(destination, dbItem, name)
            is LocalEntity -> moveFile(destination, dbItem, name)
            is DbDirectory -> {
                val oldParent = parentOfDirectory(dbItem)
                dbItem.name = name
                debridFileRepository.save(dbItem)
                if (directoriesHaveSameParent(dbItem.fileSystemPath()!!, destination)) {
                    debridFileRepository.renameDirectory(
                        dbItem.path!!, Base58.encode(name.encodeToByteArray()), name
                    )
                } else {
                    debridFileRepository.moveDirectory(
                        dbItem, destination.pathToLtree()

                    )
                }
                emitChanges(setOfNotNull(oldParent, destination))
            }
        }
    }

    @Transactional
    fun moveFile(
        destination: String, dbFile: DbEntity, name: String
    ) {
        if (dbFile is DbDirectory) error("entity is directory")
        val oldParent = dbFile.directory?.fileSystemPath()
        val destinationDirectory = getOrCreateDirectory(destination)
        dbFile.directory = destinationDirectory
        dbFile.name = name
        debridFileRepository.save(dbFile)
        emitChanges(setOfNotNull(oldParent, destination))
    }

    @Transactional
    fun deleteFile(file: DbEntity) {
        val parent = when (file) {
            is DbDirectory -> parentOfDirectory(file)
            else -> file.directory?.fileSystemPath()
        }
        when (file) {
            is RemotelyCachedEntity -> deleteRemotelyCachedEntity(file)
            is DbDirectory -> debridFileRepository.delete(file)
            is LocalEntity -> {
                deleteLargeObjectForLocalEntity(file)
                debridFileRepository.delete(file)
            }
        }
        parent?.let { emitChange(it) }
    }

    private fun deleteLargeObjectForLocalEntity(file: LocalEntity) {
        entityManager.createNativeQuery(
            """
            SELECT lo_unlink(b.loid) from (
                select distinct local_contents as loid from blob b
                where b.id=${file.blob!!.id}
            ) as b
           
        """.trimMargin()
        ).resultList
    }

    private fun deleteRemotelyCachedEntity(file: RemotelyCachedEntity) {
        when (file.contents) {
            is DebridCachedTorrentContent -> debridFileRepository.unlinkFileFromTorrents(file)
            is DebridCachedUsenetReleaseContent -> debridFileRepository.unlinkFileFromUsenet(file)
            is NzbContents -> debridFileRepository.unlinkFileFromUsenet(file)
        }
        debridFileRepository.delete(file)
    }

    @Transactional
    fun handleNoLongerCachedFile(debridFile: RemotelyCachedEntity) {
        when (debridFile.contents) {
            is DebridCachedTorrentContent -> {
                torrentRepository.deleteByHashIgnoreCase(debridFile.hash!!)
                debridFileRepository.getByHash(debridFile.hash!!).forEach {
                    debridFileRepository.delete(it)
                }
            }

            is DebridCachedUsenetReleaseContent -> {
                usenetRepository.deleteByHashIgnoreCase(debridFile.hash!!)
                debridFileRepository.getByHash(debridFile.hash!!).forEach {
                    debridFileRepository.delete(it)
                }
            }

            is NzbContents -> {
                usenetRepository.deleteByHashIgnoreCase(debridFile.hash!!)
                debridFileRepository.getByHash(debridFile.hash!!).forEach {
                    debridFileRepository.delete(it)
                }
            }
        }

    }

    @Transactional
    fun createLocalFile(path: String, inputStream: InputStream, size: Long?): LocalEntity {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val localFile = LocalEntity()

        if (size == null) {
            val bytes = inputStream.readAllBytes()
            if (bytes.size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
                && debridavConfigurationProperties.localEntityMaxSizeMb != 0
            ) {
                throw IllegalArgumentException(
                    "Size: ${bytes.size.times(MEGABYTE)} MB is greater than set maximum: " +
                            "${debridavConfigurationProperties.localEntityMaxSizeMb}"
                )
            }
            val streamSize = bytes.size.toLong()
            localFile.size = streamSize
            localFile.blob = Blob(BlobProxy.generateProxy(bytes.inputStream(), streamSize), streamSize)
        } else {
            if (size / MEGABYTE > debridavConfigurationProperties.localEntityMaxSizeMb
                && debridavConfigurationProperties.localEntityMaxSizeMb != 0
                && debridavConfigurationProperties.localEntityMaxSizeMb != 0
            ) {
                throw IllegalArgumentException(
                    "Size: ${size / MEGABYTE} MB is greater than set maximum: " +
                            "${debridavConfigurationProperties.localEntityMaxSizeMb}"
                )
            }
            localFile.size = size
            localFile.blob = Blob(BlobProxy.generateProxy(inputStream, size), size)
        }
        localFile.name = path.substringAfterLast("/")
        localFile.directory = directory
        localFile.lastModified = System.currentTimeMillis()

        val saved = debridFileRepository.save(localFile)
        emitChange(parentOf(path))
        return saved
    }


    fun getFileAtPath(path: String): DbEntity? {
        return debridFileRepository.getDirectoryByPath(path.pathToLtree()) ?: debridFileRepository.getDirectoryByPath(
            path.getDirectoryFromPath().pathToLtree()
        )?.let { directory ->
            return debridFileRepository.findByDirectoryAndName(directory, path.substringAfterLast("/"))
        }

    }

    @Transactional
    fun createDirectory(path: String): DbDirectory {
        return getOrCreateDirectory(if (path != "/") Strings.CS.removeEnd(path, "/") else path)
    }

    // No @Transactional: Spring binds the transaction to a thread, but a suspend
    // function can resume on a different thread (especially with the explicit
    // withContext(Dispatchers.IO) below), so the annotation was a no-op here.
    // The two repository calls are read-only and run in their own short-lived
    // Spring Data read transactions, which is what we want.
    suspend fun getChildren(directory: DbDirectory): List<DbEntity> = withContext(Dispatchers.IO) {
        listOf(
            async { debridFileRepository.getChildrenByDirectory(directory) },
            async { debridFileRepository.getByDirectory(directory) }).awaitAll().flatten()
    }

    @Transactional
    fun getOrCreateDirectory(path: String): DbDirectory = lock.withLock {
        getDirectoryTreePaths(path).map {
            val directoryEntity = debridFileRepository.getDirectoryByPath(it.pathToLtree())
            if (directoryEntity == null) {
                val newDirectoryEntity = DbDirectory()
                newDirectoryEntity.path = it.pathToLtree()
                newDirectoryEntity.name = if (it != "/") it.substringAfterLast("/") else null
                newDirectoryEntity.lastModified = Instant.now().toEpochMilli()
                debridFileRepository.save(newDirectoryEntity)
            } else directoryEntity
        }.last()
    }


    private fun getDirectoryTreePaths(path: String): List<String> {
        val tree = path.split("/").toMutableList()

        return tree.fold(mutableListOf()) { acc, part ->
            if (acc.isEmpty()) {
                acc.add("/")
            } else if (acc.last() == "/") {
                acc.add("/$part")
            } else {
                acc.add("${acc.last()}/$part")
            }
            acc
        }
    }

    private fun String.pathToLtree(): String {
        return if (this == "/") ROOT_NODE else {
            this.split("/").filter { it.isNotBlank() }
                .joinToString(separator = ".") { Base58.encode(it.encodeToByteArray()) }.let { "$ROOT_NODE.$it" }
        }
    }

    private fun String.getDirectoryFromPath(): String {
        return if (this == "/") {
            "/"
        } else this.substringBeforeLast("/").let {
            if (it.isBlank()) return "/"
            it
        }
    }

    private fun directoriesHaveSameParent(first: String, second: String): Boolean {
        return first.getDirectoryFromPath() == second
    }

    private fun parentOf(filePath: String): String = filePath.getDirectoryFromPath()

    private fun parentOfDirectory(dir: DbDirectory): String? =
        dir.fileSystemPath()?.getDirectoryFromPath()

    private fun emitChange(path: String) {
        eventPublisher.publishEvent(FileSystemChangedEvent(setOf(path)))
    }

    private fun emitChanges(paths: Set<String>) {
        if (paths.isNotEmpty()) {
            eventPublisher.publishEvent(FileSystemChangedEvent(paths))
        }
    }
}
