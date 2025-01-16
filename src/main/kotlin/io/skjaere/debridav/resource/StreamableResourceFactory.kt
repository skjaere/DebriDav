package io.skjaere.debridav.resource

import io.milton.common.Path
import io.milton.http.ResourceFactory
import io.milton.http.exceptions.BadRequestException
import io.milton.http.exceptions.NotAuthorizedException
import io.milton.resource.Resource
import io.skjaere.debridav.StreamingService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.FileService
import java.io.File

class StreamableResourceFactory(
    private val fileService: FileService,
    private val debridService: DebridLinkService,
    private val streamingService: StreamingService,
    private val debridavConfiguration: DebridavConfiguration
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
                if (it.isDirectory) {
                    toDirectoryResource(it)
                } else {
                    toFileResource(it)
                }
            } ?: run {
            fileService.getFileAtPath("$path.debridfile")?.let {
                toFileResource(it)
            }
        }
    }

    fun toDirectoryResource(file: File): DirectoryResource {
        if (!file.isDirectory) {
            error("Not a directory")
        }
        return DirectoryResource(file, this, fileService)
    }

    fun toFileResource(file: File): Resource? {
        if (file.isDirectory) {
            error("Provided file is a directory")
        }
        return if (file.name.endsWith(".debridfile")) {
            DebridFileResource(
                file = file,
                fileService = fileService,
                streamingService = streamingService,
                debridService = debridService,
                debridavConfiguration = debridavConfiguration
            )
        } else {
            if (file.exists()) {
                return FileResource(file, fileService)
            }
            null
        }
    }


}
