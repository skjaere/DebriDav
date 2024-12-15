package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.resource.CollectionResource
import io.milton.resource.DeletableResource
import io.milton.resource.MakeCollectionableResource
import io.milton.resource.MoveableResource
import io.milton.resource.PutableResource
import io.milton.resource.Resource
import io.skjaere.debridav.fs.DebridFsDirectory
import io.skjaere.debridav.fs.DebridFsItem
import io.skjaere.debridav.fs.FileService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.time.Instant
import java.util.*

class DirectoryResource(
    val directory: DebridFsItem,
    private val children: List<Resource>,
    fileService: FileService,
    //private val usenetConversionService: ConversionService

) : AbstractResource(fileService, directory), MakeCollectionableResource, MoveableResource, PutableResource,
    DeletableResource {

    private val mutex = Mutex()

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
        return Date.from(Instant.ofEpochMilli((file as DebridFsDirectory).lastModified))
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        fileService.deleteFile(directory.path!!)
    }

    override fun moveTo(rDest: CollectionResource, name: String) {
        fileService.moveResource(this.name, (rDest as DirectoryResource).directory.path!!, name)
    }

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.ofEpochMilli((file as DebridFsDirectory).lastModified))
    }

    override fun child(childName: String?): Resource? {
        return children.firstOrNull { it.name == childName }
    }

    override fun getChildren(): MutableList<out Resource> {
        return children.toMutableList()
    }

    override fun createNew(newName: String, inputStream: InputStream, length: Long, contentType: String?): Resource {
        return runBlocking {
            mutex.withLock {
                val newPath = if (directory.path == "/") {
                    "/$newName"
                } else {
                    "${directory.path}/$newName"
                }
                val createdFile = fileService.createLocalFile(
                    newPath,
                    inputStream
                )
                FileResource(createdFile, fileService)
            }

        }

    }

    override fun createCollection(newName: String?): CollectionResource {
        val directory = fileService.createDirectory("${(file as DebridFsDirectory).getPathString()}/$newName")
        return DirectoryResource(directory, emptyList(), fileService)
    }
}
