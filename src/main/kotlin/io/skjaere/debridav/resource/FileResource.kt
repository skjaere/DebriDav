package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.milton.resource.ReplaceableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.LocalContentsService
import io.skjaere.debridav.fs.LocalEntity
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.*

class FileResource(
    val file: LocalEntity,
    fileService: DatabaseFileService,
    private val localContentsService: LocalContentsService,
    debridavConfigurationProperties: DebridavConfigurationProperties
) : AbstractResource(fileService, file, debridavConfigurationProperties), GetableResource, DeletableResource,
    ReplaceableResource {

    override fun getUniqueId(): String {
        return dbItem.id.toString()
    }

    override fun getName(): String {
        return dbItem.name!!
    }

    override fun getModifiedDate(): Date {
        return Date.from(Instant.now())
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        fileService.deleteFile(dbItem)
    }

    //private fun File.isDebridFile(): Boolean = this.name.endsWith(".debridfile")

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) = runBlocking<Unit> {
        localContentsService.getContentsOfLocalEntity(file, range?.start, range?.finish, out)
    }

    override fun getMaxAgeSeconds(auth: Auth?): Long {
        return 100
    }

    override fun getContentType(accepts: String?): String? {
        return file.mimeType
    }

    override fun getContentLength(): Long {
        return file.size!!
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.now())
    }

    override fun replaceContent(inputStream: InputStream, size: Long?) {
        fileService.writeContentsToLocalFile(file, inputStream, size!!)
    }
}
