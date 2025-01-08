package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.StreamingService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridFsFile
import io.skjaere.debridav.fs.DebridFsItem
import io.skjaere.debridav.fs.FileService
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.time.Instant
import java.util.*

class DebridFileResource(
    override val file: DebridFsItem,
    fileService: FileService,
    private val streamingService: StreamingService,
    private val debridLinkService: DebridLinkService,
    private val debridavConfiguration: DebridavConfiguration
) : AbstractResource(fileService, file), GetableResource, DeletableResource {
    private val debridFileContents: DebridFileContents = fileService.getDebridFileContents(file.path)!!
    private val logger = LoggerFactory.getLogger(DebridCachedContentClient::class.java)

    init {
        file as DebridFsFile
    }

    override fun getUniqueId(): String {
        return (file as DebridFsFile).name
    }

    override fun getName(): String {
        return (file as DebridFsFile).name
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(Instant.ofEpochMilli((file as DebridFsFile).lastModified))
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        fileService.deleteFile(file.path)
    }

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) {
        runBlocking {
            out.use { outputStream ->
                debridLinkService.getCheckedLinks(file.path)
                    .firstOrNull()
                    ?.let { cachedFile ->
                        logger.info("streaming: {}", cachedFile)
                        streamingService.streamDebridLink(
                            cachedFile,
                            range,
                            debridFileContents.size,
                            outputStream
                        )
                    } ?: run {
                    if (
                        (file as DebridFsFile).isNoLongerCached(debridavConfiguration.debridClients)
                        && debridavConfiguration.shouldDeleteNonWorkingFiles
                    ) {
                        fileService.handleNoLongerCachedFile(file.path)
                    }

                    logger.info("No working link found for ${debridFileContents.originalPath}")
                }
            }
        }
    }

    /*private fun File.isNoLongerCached() = Json
        .decodeFromString<DebridTorrentFileContents>(this.readText(charset = Charsets.UTF_8))
        .debridLinks
        .filter { it.provider in debridavConfiguration.debridClients }
        .all { it is MissingFile }*/

    override fun getMaxAgeSeconds(auth: Auth?): Long {
        return 100
    }

    override fun getContentType(accepts: String?): String {
        return "video/mp4"
    }

    override fun getContentLength(): Long {
        return (file as DebridFsFile).size
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return modifiedDate
    }
}
