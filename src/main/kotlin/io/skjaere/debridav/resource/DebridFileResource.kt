package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.StreamingService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridClient
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.time.Instant
import java.util.*

class DebridFileResource(
    val file: RemotelyCachedEntity,
    fileService: DatabaseFileService,
    private val streamingService: StreamingService,
    private val debridService: DebridLinkService,
    private val debridavConfiguration: DebridavConfiguration
) : AbstractResource(fileService, file as DbEntity), GetableResource, DeletableResource {
    private val debridFileContents: DebridFileContents = (dbItem as RemotelyCachedEntity).contents!!
    private val logger = LoggerFactory.getLogger(DebridClient::class.java)

    override fun getUniqueId(): String {
        return dbItem.id!!.toString()
    }

    override fun getName(): String {
        return dbItem.name!!.replace(".debridfile", "")
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(Instant.ofEpochMilli(dbItem.lastModified!!))
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        fileService.deleteFile(dbItem)
    }

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) {
        runBlocking {
            out.use { outputStream ->
                debridService.getCheckedLinks(file)
                    .firstOrNull()
                    ?.let { cachedFile ->
                        logger.info("streaming: {}", cachedFile)
                        streamingService.streamDebridLink(
                            cachedFile,
                            range,
                            outputStream
                        )
                    } ?: run {
                    if (file.isNoLongerCached(debridavConfiguration.debridClients)
                        && debridavConfiguration.shouldDeleteNonWorkingFiles
                    ) {
                        fileService.handleNoLongerCachedFile(file)
                    }

                    logger.info("No working link found for ${debridFileContents.originalPath}")
                }
            }
        }
    }

    override fun getMaxAgeSeconds(auth: Auth?): Long {
        return 100
    }

    override fun getContentType(accepts: String?): String {
        return "video/mp4"
    }

    override fun getContentLength(): Long {
        return file.contents!!.size!!.toLong()
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return modifiedDate
    }
}
