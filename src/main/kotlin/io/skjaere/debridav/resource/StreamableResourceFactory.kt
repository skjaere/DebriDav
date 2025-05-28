package io.skjaere.debridav.resource

import io.milton.common.Path
import io.milton.http.ResourceFactory
import io.milton.http.exceptions.BadRequestException
import io.milton.http.exceptions.NotAuthorizedException
import io.milton.resource.Resource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.LocalContentsService
import io.skjaere.debridav.fs.LocalEntity
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.stream.StreamingService
import org.slf4j.LoggerFactory

class StreamableResourceFactory(
    private val fileService: DatabaseFileService,
    private val debridService: DebridLinkService,
    private val streamingService: StreamingService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val localContentsService: LocalContentsService
) : ResourceFactory {
    private val logger = LoggerFactory.getLogger(StreamableResourceFactory::class.java)

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
                }

        } catch (e: Exception) {
            logger.error("could not load item at path: $path", e)
            null
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
                debridavConfigurationProperties = debridavConfigurationProperties
            )

            is LocalEntity -> FileResource(dbItem, fileService, localContentsService)
            else -> error("Unknown dbItemType type: ${dbItem::class.simpleName}")
        }
    }
}
