package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.databasefs.DbDirectory
import io.skjaere.debridav.fs.databasefs.DbFile
import io.skjaere.debridav.fs.databasefs.DbItem
import io.skjaere.debridav.fs.databasefs.DebridCachedTorrentContentDTO
import io.skjaere.debridav.fs.databasefs.DebridFileContentsDTO
import io.skjaere.debridav.fs.databasefs.DebridUsenetContentsDTO
import io.skjaere.debridav.fs.databasefs.LocalFile
import io.skjaere.debridav.repository.DebridFileContentsRepository
import kotlin.io.path.exists
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.core.convert.ConversionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Service
class DatabaseFileService(
    private val debridFileRepository: DebridFileContentsRepository,
    private val usenetConversionService: ConversionService
) : FileService {
    private val logger = LoggerFactory.getLogger(DatabaseFileService::class.java)
    private val lock = Mutex()
    private val defaultDirectories = listOf("/downloads", "/tv", "/movies")

    init {
        defaultDirectories.forEach {
            if (debridFileRepository.findByPath(it) == null) {
                createDirectory(it)
            }
        }
    }

    @Transactional
    override fun createDebridFile(
        path: String,
        debridFileContents: DebridFileContents,
        type: DebridFileType
    ): DebridFsFile = runBlocking {
        val parent = getOrCreateDirectory(path.substringBeforeLast("/"))

        val fileEntity = DbFile()
        fileEntity.path = path
        fileEntity.parent = parent
        fileEntity.name = path.substringAfterLast("/")
        fileEntity.lastModified = Instant.now().toEpochMilli()
        fileEntity.size = debridFileContents.size
        fileEntity.mimeType = debridFileContents.mimeType
        fileEntity.type = debridFileContents.type

        /**
         * [io.skjaere.debridav.fs.databasefs.converters.DebridTorrentFileContentsDTOConverter]
         */
        fileEntity.contents = usenetConversionService.convert(debridFileContents, DebridFileContentsDTO::class.java)
        if (debridFileRepository.findByPath(fileEntity.path!!) != null) {
            logger.info("Deleting ${fileEntity.path}")
            debridFileRepository.deleteByPath(fileEntity.path!!)
        }
        logger.info("Creating ${fileEntity.path}")
        fileEntity.contents!!.debridLinks!!.filterIsInstance<CachedFile>().firstOrNull { it.params.isEmpty() }?.let {
            logger.error("Creating file with no params in debrid links: $it")
        }
        val createdFile = debridFileRepository.save(fileEntity) as DbItem
        logger.info("Created ${fileEntity.path}")

        usenetConversionService.convert(
            createdFile, DebridFsItem::class.java
        ) as DebridFsFile
    }

    override fun getDebridFileContents(path: String): DebridFileContents? {
        return when (val dto = debridFileRepository.findByPath(path)) {
            is DbFile -> usenetConversionService.convert(dto.contents, DebridFileContents::class.java)!!

            null -> null
            else -> {
                error("Could not convert $dto at $path to debrid file contents")
            }
        }
    }

    @Transactional
    override fun writeContentsToFile(path: String, debridFileContents: DebridFileContents) {
        debridFileRepository.findByPath(path)?.let { debridFile ->
            when (debridFile) {
                is DbFile -> {
                    debridFile.contents =
                        usenetConversionService.convert(debridFileContents, DebridFileContentsDTO::class.java)
                    debridFileRepository.save(debridFile)
                }

                else -> error("Cant write content to file $path")
            }
        } ?: run {
            error("File at path $path not found")
        }
    }

    @Transactional
    override fun moveResource(itemPath: String, destination: String, name: String) {
        val parent = getOrCreateDirectory(destination)
        debridFileRepository.findByPath(itemPath)?.let { debridFile ->
            when (debridFile) {
                is DbFile -> {
                    debridFile.path = "$destination/$name"
                    debridFile.name = name
                    debridFile.parent = parent
                }

                is DbDirectory -> {
                    // TODO: nasty hack
                    val descendants = debridFileRepository.findAllByPathStartingWith("${debridFile.path!!}/")
                    val updatedDescendants = descendants.map { descendant ->
                        val subPath = descendant.path!!
                            .substringAfter("${debridFile.path}/")

                        descendant.path = "$destination/$name/$subPath"
                        descendant
                    }
                    debridFile.path = "$destination/$name"
                    debridFile.name = name
                    debridFile.parent = parent
                    debridFile.children = debridFile.children.map { child ->
                        child.path = "$destination/$name"
                        child
                    }.toMutableList()
                    debridFileRepository.saveAll(listOf(debridFile) + updatedDescendants)
                }
            }

            debridFileRepository.save(debridFile)
        } ?: run {
            error("File at path $itemPath not found")
        }
    }

    override fun deleteFile(path: String) {
        debridFileRepository.findByPath(path)?.let { debridFile ->
            debridFileRepository.delete(debridFile)
            deleteNzbFileIfNecessary(debridFile)

        } ?: kotlin.run { logger.debug("File at path $path not found") }
    }

    private fun deleteNzbFileIfNecessary(debridFile: DbItem) {
        if (debridFile is DbFile && debridFile.contents is DebridUsenetContentsDTO && nzbFileIsOrphan(debridFile)) {
            Path.of(
                (debridFile.contents as DebridUsenetContentsDTO).nzbFileLocation!!
            ).let {
                if (it.exists()) {
                    Files.delete(it)
                }
            }
        }
    }

    private fun nzbFileIsOrphan(debridFile: DbFile) = debridFileRepository.findAllDebridFilesByNzbFilePath(
        (debridFile.contents as DebridUsenetContentsDTO).nzbFileLocation!!
    ).isEmpty()


    @Transactional
    override fun handleNoLongerCachedFile(path: String) {
        debridFileRepository.findByPath(path)?.let { debridFile ->
            if (debridFile is DbFile) {
                when (debridFile.contents) {
                    is DebridCachedTorrentContentDTO -> {
                        val idsOfFilesWithMagnet = debridFileRepository.findFileIdsByMagnet(
                            (debridFile.contents as DebridCachedTorrentContentDTO).magnet!!
                        )
                        debridFileRepository.deleteAllById(idsOfFilesWithMagnet)
                    }

                    is DebridUsenetContentsDTO -> {
                        val idsOfFilesWithHash = debridFileRepository.findFileIdsByHash(
                            (debridFile.contents as DebridUsenetContentsDTO).hash!!
                        )
                        debridFileRepository.deleteAllById(idsOfFilesWithHash)
                    }
                }
            }
        }
    }

    @Transactional
    override fun createLocalFile(path: String, inputStream: InputStream): DebridFsLocalFile {
        val directory = getOrCreateDirectory(path.substringBeforeLast("/"))
        val localFile = LocalFile()
        val bytes = inputStream.readBytes()
        localFile.name = path.substringAfterLast("/")
        localFile.path = path
        localFile.lastModified = System.currentTimeMillis()
        localFile.size = bytes.size.toLong()
        localFile.parent = directory
        localFile.contents = bytes

        debridFileRepository.save(localFile)
        return usenetConversionService.convert(localFile as DbItem, DebridFsItem::class.java) as DebridFsLocalFile
    }

    @Transactional
    override fun getFileAtPath(path: String): DebridFsItem? {
        return usenetConversionService.convert(
            debridFileRepository.findByPath(path), DebridFsItem::class.java
        )
    }

    @Transactional
    override fun createDirectory(path: String): DebridFsDirectory {
        val createdDirectory = if (path == "/") {
            val dbDirectory = DbDirectory()
            dbDirectory.name = null
            dbDirectory.path = "/"
            dbDirectory.lastModified = Instant.now().toEpochMilli()
            debridFileRepository.save(dbDirectory)
        } else {
            val name = path.substringAfterLast("/")
            val dbDirectory = DbDirectory()
            dbDirectory.name = name
            dbDirectory.path = path
            dbDirectory.lastModified = Instant.now().toEpochMilli()
            dbDirectory.parent = getOrCreateDirectory(path.substringBeforeLast("/"))
            debridFileRepository.save(dbDirectory)
        }

        return usenetConversionService.convert(createdDirectory, DebridFsItem::class.java) as DebridFsDirectory
    }

    override fun getChildren(path: String): List<DebridFsItem> {
        return debridFileRepository.findAllByParentPath(path)
            ?.map { usenetConversionService.convert(it, DebridFsItem::class.java)!! } ?: kotlin.run { emptyList() }
    }

    private fun getChildrenDbItems(path: String): List<DbItem>? {
        return debridFileRepository.findAllByParentPath(path)
    }

    override fun deleteFilesWithHash(hash: String) {
        debridFileRepository.findFileIdsByHash(hash).let { debridFileIds ->
            debridFileRepository.deleteAllById(debridFileIds)
        }
    }


    private fun getOrCreateDirectory(path: String): DbDirectory = runBlocking {
        lock.withLock {
            getDirectoryTreePaths(path).map {
                val directoryEntity = debridFileRepository.findByPath(it)
                if (directoryEntity == null) {
                    val parent = getParentDirectory(it)?.let { debridFileRepository.findByPath(it) }
                    val newDirectoryEntity = DbDirectory()
                    newDirectoryEntity.path = it
                    newDirectoryEntity.name = if (it != "/") it.substringAfterLast("/") else null
                    newDirectoryEntity.lastModified = Instant.now().toEpochMilli()
                    newDirectoryEntity.parent = parent as DbDirectory?
                    debridFileRepository.save(newDirectoryEntity)
                } else directoryEntity
            }.lastOrNull()?.let { it as DbDirectory } ?: debridFileRepository.findByPath("/") as DbDirectory
        }
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

    private fun getParentDirectory(path: String): String? {
        if (path == "/") {
            return null
        }
        return path.substringBeforeLast("/").let { it.ifBlank { "/" } }
    }
}
