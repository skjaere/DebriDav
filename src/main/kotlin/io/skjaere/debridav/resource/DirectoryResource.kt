package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.resource.CollectionResource
import io.milton.resource.DeletableResource
import io.milton.resource.MakeCollectionableResource
import io.milton.resource.MoveableResource
import io.milton.resource.PutableResource
import io.milton.resource.Resource
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.*

class DirectoryResource(
    val directory: File,
    private val children: List<Resource>,
    fileService: io.skjaere.debridav.fs.FileService
) : AbstractResource(fileService), MakeCollectionableResource, MoveableResource, PutableResource, DeletableResource {

    override fun getUniqueId(): String {
        return directory.path
    }

    override fun getName(): String {
        return if (directory.path == "/") "/" else directory.path.split("/").last()
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
        directory.delete()
    }

    override fun moveTo(rDest: CollectionResource, name: String) {
        fileService.moveResource(this, (rDest as DirectoryResource).directory.path, name)
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.ofEpochMilli(directory.lastModified()))
    }

    override fun child(childName: String?): Resource? {
        return children.firstOrNull { it.name == childName }
    }

    override fun getChildren(): MutableList<out Resource> {
        return children.toMutableList()
    }

    override fun createNew(newName: String, inputStream: InputStream, length: Long, contentType: String?): Resource {
        val createdFile = fileService.createLocalFile(
            "${directory.path}/$newName",
            inputStream
        )
        return FileResource(createdFile, fileService)
    }

    override fun createCollection(newName: String?): CollectionResource {
        return fileService.createDirectory("${directory.path}/$newName/")
    }
}
