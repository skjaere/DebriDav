package io.william.debridav.resource

import io.milton.common.Path
import io.milton.http.ResourceFactory
import io.milton.http.exceptions.BadRequestException
import io.milton.http.exceptions.NotAuthorizedException
import io.milton.resource.Resource
import io.william.debridav.StreamingService
import io.william.debridav.debrid.DebridService
import io.william.debridav.fs.FileService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File


class StreamableResourceFactory(
    private val fileService: FileService,
    private val debridService: DebridService,
    private val streamingService: StreamingService,
) : ResourceFactory {
    @Throws(NotAuthorizedException::class, BadRequestException::class)
    override fun getResource(host: String?, url: String): Resource? {
        val path: Path = Path.path(url)
        return find(path)
    }

    @Throws(NotAuthorizedException::class, BadRequestException::class)
    private fun find(path: Path): Resource? {
        val actualPath = if (path.isRoot) "/" else path.toPath()
        return getResourceAtPath(actualPath)
    }

    private fun getResourceAtPath(path: String): Resource? {
        return fileService.getFileAtPath(path)
            ?.let {
                if (it.isDirectory) it.toDirectoryResource()
                else it.toFileResource()
            } ?: run {
            fileService.getFileAtPath("$path.debridfile")?.toFileResource()
        }
    }

    private fun File.toDirectoryResource(): DirectoryResource {
        if (!this.isDirectory) {
            throw RuntimeException("Not a directory")
        }
        return DirectoryResource(this, getChildren(this), fileService)
    }

    private fun File.toFileResource(): Resource? {
        if (this.isDirectory) {
            throw RuntimeException("Provided file is a directory")
        }
        return if (this.name.endsWith(".debridfile")) {
            DebridFileResource(
                this,
                fileService,
                streamingService,
                debridService
            )
        } else {
            if (this.exists())
                return FileResource(this, fileService)
            null
        }
    }

    private fun getChildren(directory: File): List<Resource> = runBlocking {
        directory.listFiles()
            ?.toList()
            ?.map { async { toResource(it) } }
            ?.awaitAll()
            ?.filterNotNull()
            ?: emptyList()
    }

    private fun toResource(file: File): Resource? {
        return if (file.isDirectory) file.toDirectoryResource() else file.toFileResource()
    }

}