package io.skjaere.debridav.resource

import io.milton.common.Path
import io.milton.http.ResourceFactory
import io.milton.http.exceptions.BadRequestException
import io.milton.http.exceptions.NotAuthorizedException
import io.milton.resource.Resource
import io.skjaere.debridav.StreamingService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.DebridFsDirectory
import io.skjaere.debridav.fs.DebridFsFile
import io.skjaere.debridav.fs.DebridFsItem
import io.skjaere.debridav.fs.DebridFsLocalFile
import io.skjaere.debridav.fs.FileService
import org.springframework.core.convert.ConversionService

class StreamableResourceFactory(
    private val fileService: FileService,
    private val debridLinkService: DebridLinkService,
    private val streamingService: StreamingService,
    private val debridavConfiguration: DebridavConfiguration,
    private val usenetConversionService: ConversionService
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
        fileService.getFileAtPath("/")?.let {

        } ?: fileService.createDirectory("/")

        return fileService.getFileAtPath(path)
            ?.let {
                if (it is DebridFsDirectory) {
                    it.toDirectoryResource()
                } else {
                    it.toFileResource()
                }
            } ?: run {
            fileService.getFileAtPath("$path.debridfile")?.toFileResource()
        }
    }

    private fun DebridFsItem.toDirectoryResource(): DirectoryResource {
        if (this !is DebridFsDirectory) {
            error("Not a directory")
        }
        return DirectoryResource(this, getChildren(this), fileService)
    }

    private fun DebridFsItem.toFileResource(): Resource {
        return when (this) {
            is DebridFsFile -> DebridFileResource(
                file = this,
                fileService = fileService,
                streamingService = streamingService,
                debridLinkService = debridLinkService,
                debridavConfiguration = debridavConfiguration
            )

            is DebridFsDirectory -> error("Is a directory")
            is DebridFsLocalFile -> FileResource(
                file = this,
                fileService = fileService
            )
        }
    }

    /* private fun DebridFsItem.toFileResource(): Resource? {
         if (this is DebridFsDirectory) {
             error("Provided file is a directory")
         }
         return if (this.name.endsWith(".debridfile")) {
             DebridFileResource(
                 file = this,
                 fileService = fileService,
                 streamingService = streamingService,
                 debridLinkService = debridLinkService,
                 debridavConfiguration = debridavConfiguration
             )
         } else {
             if (this.exists()) {
                 return FileResource(this, fileService)
             }
             null
         }
     }*/

    /*private fun getChildren(directory: DebridFsDirectory): List<Resource> = runBlocking {
        fileService.getFileAtPath(directory.path)
    }*/

    private fun getChildren(directory: DebridFsDirectory): MutableList<out Resource> {
        return fileService
            .getChildren(directory.path)
            .map { usenetConversionService.convert(it, DebridFsItem::class.java) }
            .map { toResource(it!!) }
            .toMutableList()
    }

    private fun toResource(file: DebridFsItem): Resource {
        return when (file) {
            is DebridFsFile -> DebridFileResource(
                file,
                fileService,
                streamingService,
                debridLinkService,
                debridavConfiguration
            )

            is DebridFsDirectory -> file.toDirectoryResource()
            is DebridFsLocalFile -> file.toFileResource()
        }
        //return if (file is DebridFsDirectory) file.toDirectoryResource() else file.toFileResource()
    }
}
