package io.skjaere.debridav.resource

import io.milton.common.Path
import io.milton.http.ResourceFactory
import io.milton.http.exceptions.BadRequestException
import io.milton.http.exceptions.NotAuthorizedException
import io.milton.resource.Resource
import io.skjaere.debridav.StreamingService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.LocalContentsService
import io.skjaere.debridav.fs.LocalEntity
import io.skjaere.debridav.fs.RemotelyCachedEntity

class StreamableResourceFactory(
    private val fileService: DatabaseFileService,
    private val debridService: DebridLinkService,
    private val streamingService: StreamingService,
    private val debridavConfiguration: DebridavConfiguration,
    private val localContentsService: LocalContentsService
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

    @Suppress("TooGenericExceptionCaught")
    private fun getResourceAtPath(path: String): Resource? {
        return try {
            fileService.getFileAtPath(path)
                ?.let {
                    if (it is DbDirectory) {
                        toDirectoryResource(it)
                    } else {
                        toFileResource(it)
                    }
                } ?: run {
                fileService.getFileAtPath("$path.debridfile")?.let {
                    toFileResource(it)
                }
            }
        } catch (e: Exception) {
            error("could not load item at path: $path")
            throw e
        }
    }

    fun toDirectoryResource(dbItem: DbEntity): DirectoryResource {
        if (dbItem !is DbDirectory) {
            error("Not a directory")
        }
        return DirectoryResource(dbItem, this, localContentsService, fileService)
    }

    fun toFileResource(dbItem: DbEntity): Resource? {
        return when (dbItem) {
            is DbDirectory -> error("Provided file is a directory")
            is RemotelyCachedEntity -> DebridFileResource(
                file = dbItem,
                fileService = fileService,
                streamingService = streamingService,
                debridService = debridService,
                debridavConfiguration = debridavConfiguration
            )

            is LocalEntity -> FileResource(dbItem, fileService, localContentsService)
            else -> error("Unknown dbItemType type: ${dbItem::class.simpleName}")
        }
    }
}
