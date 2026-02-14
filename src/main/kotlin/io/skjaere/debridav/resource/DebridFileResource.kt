package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridClient
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.ClientError
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.NetworkError
import io.skjaere.debridav.fs.ProviderError
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.fs.UnknownDebridLinkError
import io.skjaere.debridav.stream.StreamResult
import io.skjaere.debridav.stream.StreamingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
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
    debridavConfigurationProperties: DebridavConfigurationProperties
) : AbstractResource(fileService, file as DbEntity, debridavConfigurationProperties), GetableResource, DeletableResource {
    private val debridFileContents: DebridFileContents = (dbItem as RemotelyCachedEntity).contents!!
    private val logger = LoggerFactory.getLogger(DebridClient::class.java)

    override fun getUniqueId(): String {
        return dbItem.id!!.toString()
    }

    override fun getName(): String {
        return dbItem.name!!.replace(".debridfile", "")
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
                debridService.getCachedFileCached(file)
                    ?.let { cachedFile ->
                        logger.info("streaming: {} range {} from {}", cachedFile.path, range, cachedFile.provider)
                        val result = try {
                            streamingService.streamContents(
                                cachedFile,
                                range,
                                outputStream,
                                file
                            )
                        } catch (_: CancellationException) {
                            this.coroutineContext.cancelChildren()
                            StreamResult.OK
                        }
                        if (result != StreamResult.OK) {
                            val updatedDebridLink = mapResultToDebridFile(result, cachedFile)
                            file.contents!!.replaceOrAddDebridLink(updatedDebridLink)
                            fileService.saveDbEntity(file)
                        }
                    } ?: run {
                    if (file.isNoLongerCached(debridavConfigurationProperties.debridClients)
                        && debridavConfigurationProperties.shouldDeleteNonWorkingFiles
                    ) {
                        fileService.handleNoLongerCachedFile(file)
                    }

                    logger.info("No working link found for ${debridFileContents.originalPath}")
                }
            }
        }
    }

    private fun mapResultToDebridFile(
        result: StreamResult,
        cachedFile: CachedFile
    ): DebridFile = when (result) {
        StreamResult.DEAD_LINK -> MissingFile(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.IO_ERROR -> NetworkError(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.PROVIDER_ERROR -> ProviderError(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.CLIENT_ERROR -> ClientError(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.UNKNOWN_ERROR -> UnknownDebridLinkError(
            cachedFile.provider!!,
            Instant.now().toEpochMilli()
        )

        StreamResult.OK -> error("how?")
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

    override fun getCreateDate(): Date {
        return modifiedDate
    }
}
