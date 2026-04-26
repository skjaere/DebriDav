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
import io.skjaere.debridav.fs.NzbContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.stream.StreamingService
import io.skjaere.debridav.usenet.nzb.findStreamableFile
import io.skjaere.debridav.usenet.nzb.toNzbDocument
import io.skjaere.nzbstreamer.NzbStreamer
import org.slf4j.LoggerFactory

class StreamableResourceFactory(
    private val fileService: DatabaseFileService,
    private val debridService: DebridLinkService,
    private val streamingService: StreamingService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val localContentsService: LocalContentsService,
    private val nzbStreamer: NzbStreamer?,
    private val pgmqClient: PgmqClient?
) : ResourceFactory {
    private val logger = LoggerFactory.getLogger(StreamableResourceFactory::class.java)

    @Throws(NotAuthorizedException::class, BadRequestException::class)
    override fun getResource(host: String?, url: String): Resource? {
        val path: Path = Path.path(stripWebdavPrefix(url))
        return find(path)
    }

    private fun stripWebdavPrefix(url: String): String = when {
        url == WEBDAV_PREFIX || url == "$WEBDAV_PREFIX/" -> "/"
        url.startsWith("$WEBDAV_PREFIX/") -> url.removePrefix(WEBDAV_PREFIX)
        else -> url
    }

    companion object {
        const val WEBDAV_PREFIX = "/webdav"
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
        return DirectoryResource(dbItem, this, localContentsService, fileService, debridavConfigurationProperties)
    }

    fun toFileResource(dbItem: DbEntity): Resource? {
        return when (dbItem) {
            is DbDirectory -> error("Provided file is a directory")
            is RemotelyCachedEntity -> {
                when (val contents = dbItem.contents) {
                    is NzbContents -> toNzbFileResource(dbItem, contents)

                    null -> null

                    else -> DebridFileResource(
                        file = dbItem,
                        fileService = fileService,
                        streamingService = streamingService,
                        debridService = debridService,
                        debridavConfigurationProperties = debridavConfigurationProperties
                    )
                }
            }

            is LocalEntity -> FileResource(dbItem, fileService, localContentsService, debridavConfigurationProperties)
            else -> error("Unknown dbItemType type: ${dbItem::class.simpleName}")
        }
    }

    private fun toNzbFileResource(dbItem: RemotelyCachedEntity, contents: NzbContents): NzbFileResource? {
        val streamer = nzbStreamer ?: run {
            logger.warn(
                "NzbContents found but NNTP is not enabled, cannot stream: {}",
                contents.originalPath
            )
            return null
        }
        val nzbDocument = contents.nzbDocument!!.toNzbDocument()
        return contents.nzbDocument!!.findStreamableFile(contents.originalPath!!)
            ?.let { streamableFile ->
                NzbFileResource(
                    file = dbItem,
                    fileService = fileService,
                    nzbStreamer = streamer,
                    nzbDocument = nzbDocument,
                    streamableFile = streamableFile,
                    debridavConfigurationProperties = debridavConfigurationProperties,
                    pgmqClient = pgmqClient,
                    nzbDocumentId = contents.nzbDocument!!.id!!
                )
            }
            .also { if (it == null) logger.error("No streamable file found for path: {}", contents.originalPath) }
    }
}
