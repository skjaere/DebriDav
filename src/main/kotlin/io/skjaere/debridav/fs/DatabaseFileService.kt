package io.skjaere.debridav.fs

import io.skjaere.debridav.fs.databasefs.DbDirectory
import io.skjaere.debridav.fs.databasefs.DbFile
import io.skjaere.debridav.fs.databasefs.DbItem
import io.skjaere.debridav.fs.databasefs.DebridFileContentsDTO
import io.skjaere.debridav.fs.databasefs.DebridTorrentContentsDTO
import io.skjaere.debridav.fs.databasefs.DebridUsenetContentsDTO
import io.skjaere.debridav.fs.databasefs.LocalFile
import io.skjaere.debridav.repository.DebridFileContentsRepository
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

    @Transactional
    override fun createDebridFile(path: String, debridFileContents: DebridFileContents): DebridFsFile {
        val parent = getOrCreateDirectory(path.substringBeforeLast("/"))

        val fileEntity = DbFile()
        fileEntity.path = path
        fileEntity.parent = parent
        fileEntity.name = path.substringAfterLast("/")
        fileEntity.lastModified = Instant.now().toEpochMilli()
        fileEntity.size = debridFileContents.size
        fileEntity.mimeType = debridFileContents.mimeType
        /** @see: DebridTorrentFileContentsDTOConverter.class **/
        fileEntity.contents = usenetConversionService.convert(debridFileContents, DebridFileContentsDTO::class.java)

        val createdFile = debridFileRepository.save(fileEntity) as DbItem
        return usenetConversionService.convert(
            createdFile,
            DebridFsItem::class.java
        ) as DebridFsFile
    }

    override fun getDebridFileContents(path: String): DebridFileContents? {
        return when (val dto = debridFileRepository.getByPath(path)) {
            is DbFile -> {
                usenetConversionService.convert(dto.contents, DebridFileContents::class.java)!!
            }

            null -> null
            else -> {
                error("Could not convert $dto at $path to debrid file contents")
            }
        }
    }

    @Transactional
    override fun writeContentsToFile(path: String, debridFileContents: DebridFileContents) {
        debridFileRepository.getByPath(path)?.let { debridFile ->
            when (debridFile) {
                is DbFile -> {
                    debridFile.contents =
                        usenetConversionService.convert(debridFileContents, DebridFileContentsDTO::class.java)
                    debridFileRepository.save(debridFile)
                }

                else -> throw IllegalArgumentException("Cant write content to file $path")
            }
        } ?: run {
            throw IllegalStateException("File at path $path not found")
        }
    }

    @Transactional
    override fun moveResource(itemPath: String, destination: String, name: String) {
        val parent = getOrCreateDirectory(destination)
        debridFileRepository.getByPath(itemPath)?.let { debridFile ->
            debridFile.path = "$destination/$name"
            debridFile.name = name
            debridFile.parent = parent
            debridFileRepository.save(debridFile)
        } ?: run {
            throw IllegalArgumentException("File at path $itemPath not found")
        }
    }

    @Transactional
    override fun deleteFile(path: String) {
        debridFileRepository.getByPath(path)?.let { debridFile ->
            debridFileRepository.delete(debridFile)
            if (debridFile is DbFile && debridFile.contents is DebridUsenetContentsDTO) {
                if (debridFileRepository.findAllDebridFilesByNzbFilePath(
                        (debridFile.contents as DebridUsenetContentsDTO).nzbFileLocation!!
                    ).isEmpty()
                ) {
                    Files.delete(
                        Path.of(
                            (debridFile.contents as DebridUsenetContentsDTO).nzbFileLocation!!
                        )
                    )
                }

            }

        } ?: kotlin.run { throw IllegalStateException("File at path $path not found") }
    }

    @Transactional
    override fun handleNoLongerCachedFile(path: String) {
        debridFileRepository.getByPath(path)?.let { debridFile ->
            if (debridFile is DbFile) {
                when (debridFile.contents) {
                    is DebridTorrentContentsDTO -> {
                        val idsOfFilesWithMagnet = debridFileRepository.findFileIdsByMagnet(
                            (debridFile.contents as DebridTorrentContentsDTO).magnet!!
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
            debridFileRepository.getByPath(path),
            DebridFsItem::class.java
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
            ?.map { usenetConversionService.convert(it, DebridFsItem::class.java)!! }
            ?: kotlin.run { emptyList() }

    }

    override fun deleteFilesWithHash(hash: String) {
        debridFileRepository.findFileIdsByHash(hash).let { debridFileIds ->
            debridFileRepository.deleteAllById(debridFileIds)
        }
    }


    private fun getOrCreateDirectory(path: String): DbDirectory {
        return getDirectoryTreePaths(path)
            .map {
                val directoryEntity = debridFileRepository.getByPath(it)
                if (directoryEntity == null) {
                    val parent = getParentDirectory(it)?.let { debridFileRepository.getByPath(it) }
                    val newDirectoryEntity = DbDirectory()
                    newDirectoryEntity.path = it
                    newDirectoryEntity.name = if (it != "/") it.substringAfterLast("/") else null
                    newDirectoryEntity.lastModified = Instant.now().toEpochMilli()
                    newDirectoryEntity.parent = parent as DbDirectory?
                    debridFileRepository.save(newDirectoryEntity)
                } else directoryEntity
            }.lastOrNull()
            ?.let { it as DbDirectory }
            ?: debridFileRepository.getByPath("/") as DbDirectory
    }

    private fun getDirectoryTreePaths(path: String): List<String> {
        val tree = path
            .split("/")
            .toMutableList()

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
        return path.substringBeforeLast("/").let { if (it.isBlank()) "/" else it }
    }

}
