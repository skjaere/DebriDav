package io.skjaere.debridav.fs

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import io.milton.resource.Resource
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.resource.DebridFileResource
import io.skjaere.debridav.resource.DirectoryResource
import io.skjaere.debridav.resource.FileResource
import jakarta.annotation.PostConstruct
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.io.FileExistsException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.naming.ConfigurationException

@Service
class FileService(
    private val debridavConfiguration: DebridavConfiguration
) {
    companion object {
        private const val CACHE_SIZE: Long = 1000
    }

    private val logger = LoggerFactory.getLogger(FileService::class.java)

    private val cache: LoadingCache<String, DebridFileContents?> = CacheBuilder.newBuilder()
        .maximumSize(CACHE_SIZE)
        .build(CacheLoader.from { path -> loadContentsFromFile(path) })

    @PostConstruct
    fun postConstruct() {
        if (debridavConfiguration.filePath.endsWith("/")) {
            throw ConfigurationException(
                "debridav.local.file.path: ${debridavConfiguration.filePath} should not contain a trailing slash"
            )
        }
    }

    fun createDebridFile(
        path: String,
        debridFileContents: DebridFileContents
    ): File {
        return createLocalFile(
            "${debridavConfiguration.filePath}/$path.debridfile",
            Json.encodeToString(debridFileContents).byteInputStream()
        )
    }

    fun createLocalFile(
        directory: String,
        inputStream: InputStream
    ): File {
        val file = File(directory)
        return writeFile(file, inputStream)
    }

    private fun writeFile(file: File, inputStream: InputStream): File {
        if (file.exists()) {
            throw FileExistsException("${file.path} already exists")
        }
        if (!Files.exists(file.toPath().parent)) {
            Files.createDirectories(file.toPath().parent)
        }
        file.createNewFile()
        inputStream.transferTo(file.outputStream())
        return file
    }

    fun moveFile(path: String, destinationDirectory: String, name: String) {
        val src = File(path)
        val destination = File("$destinationDirectory/$name")

        if (!destination.parentFile.exists()) {
            destination.parentFile.mkdirs()
        }
        Files.move(
            src.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        cache.getIfPresent(path)?.let {
            cache.invalidate(path)
            cache.put(src.path, it)
        }
    }

    fun deleteFile(file: File) {
        cache.invalidate(file.path)
        file.delete()
    }

    fun createDirectory(path: String): DirectoryResource {
        val file = File(path)

        if (!Files.exists(file.toPath())) {
            Files.createDirectory(file.toPath())
        }

        return DirectoryResource(file, listOf(), this)
    }

    fun getFileAtPath(path: String): File? {
        val file = File("${debridavConfiguration.filePath}$path")
        if (file.exists()) return file
        return null
    }

    fun moveResource(item: Resource, destination: String, name: String) {
        when (item) {
            is FileResource -> moveFile(item.file.path, destination, name)
            is DebridFileResource -> moveFile(item.file.path, destination, "$name.debridfile")
            is DirectoryResource -> moveFile(item.directory.path, destination, name)
        }
    }

    fun getSizeOfCachedContent(debridFile: File): Long {
        return cache.get(debridFile.path)!!.size
    }

    fun writeContentsToFile(file: File, debridFileContents: DebridFileContents) {
        file.writeText(Json.encodeToString(debridFileContents))
        cache.put(file.path, debridFileContents)
    }

    fun getDebridFileContents(file: File): DebridFileContents = cache.get(file.path)!!

    fun handleNoLongerCachedFile(file: File) {
        if (debridavConfiguration.shouldDeleteNonWorkingFiles) {
            logger.info("file ${file.name} is no longer cached. Deleting...")
            file.delete()
        }
    }

    private fun loadContentsFromFile(path: String): DebridFileContents? {
        return if (File(path).exists()) {
            try {
                Json.decodeFromString<DebridFileContents>(File(path).readText(Charsets.UTF_8))
            } catch (e: SerializationException) {
                logger.error("Error deserializing contents of debrid file: $path", e)
                return null
            }
        } else {
            null
        }
    }
}
