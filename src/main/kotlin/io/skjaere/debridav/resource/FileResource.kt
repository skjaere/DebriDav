package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.fs.DebridFsItem
import io.skjaere.debridav.fs.DebridFsLocalFile
import io.skjaere.debridav.fs.FileService
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.time.Instant
import java.util.*

class FileResource(
    override val file: DebridFsItem,
    fileService: FileService
) : AbstractResource(fileService, file), GetableResource, DeletableResource {

    override fun getUniqueId(): String {
        return (file as DebridFsLocalFile).name
    }

    override fun getName(): String {
        return (file as DebridFsLocalFile).name
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(
            Instant.ofEpochMilli((file as DebridFsLocalFile).lastModified)
        )
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        fileService.deleteFile(file.path!!)
    }

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) {
        sendLocalContent(out, range)
    }

    @Suppress("NestedBlockDepth", "MagicNumber")
    private fun sendLocalContent(
        out: OutputStream,
        range: Range?
    ) {
        file as DebridFsLocalFile
        out.use {
            ByteArrayInputStream(file.contents)
                .use { inputStream ->
                    inputStream.skipNBytes(range?.start ?: 0)
                    LongRange(0, range?.length ?: 0)
                        .chunked(2048)
                        .forEach { chunk ->
                            inputStream.readNBytes(chunk.size).let { bytes -> out.write(bytes) }
                        }
                }
        }
    }

    override fun getMaxAgeSeconds(auth: Auth?): Long {
        return 100
    }

    override fun getContentType(accepts: String?): String? {
        return (file as DebridFsLocalFile).mimeType
    }

    override fun getContentLength(): Long {
        return (file as DebridFsLocalFile).size
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.now())
    }
}
