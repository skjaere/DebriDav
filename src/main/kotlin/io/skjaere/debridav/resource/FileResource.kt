package io.skjaere.debridav.resource

import io.milton.common.RangeUtils
import io.milton.http.Auth
import io.milton.http.Range
import io.milton.http.Request
import io.milton.resource.DeletableResource
import io.milton.resource.GetableResource
import io.skjaere.debridav.fs.FileService
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.*

class FileResource(
    val file: File,
    fileService: io.skjaere.debridav.fs.FileService
) : AbstractResource(fileService), GetableResource, DeletableResource {

    override fun getUniqueId(): String {
        return file.name.toString()
    }

    override fun getName(): String {
        return file.name
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(Instant.now())
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        file.delete()
    }

    private fun File.isDebridFile(): Boolean = this.name.endsWith(".debridfile")

    override fun sendContent(
        out: OutputStream,
        range: Range?,
        params: MutableMap<String, String>?,
        contentType: String?
    ) {
        sendLocalContent(out, range)
    }

    private fun sendLocalContent(
        out: OutputStream,
        range: Range?
    ) {
        val stream = file.inputStream()
        if (range != null) {
            RangeUtils.writeRange(stream, range, out)
        } else {
            stream.transferTo(out)
        }
    }

    override fun getMaxAgeSeconds(auth: Auth?): Long {
        return 100
    }

    override fun getContentType(accepts: String?): String? {
        return if (file.isDebridFile()) {
            "video/mp4"
        } else {
            file.toURI().toURL().openConnection().contentType
        }
    }

    override fun getContentLength(): Long {
        return file.length()
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.now())
    }
}
