package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.resource.CollectionResource
import io.milton.resource.DeletableResource
import io.milton.resource.MakeCollectionableResource
import io.milton.resource.MoveableResource
import io.milton.resource.PutableResource
import io.milton.resource.Resource
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.LocalContentsService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.time.Instant
import java.util.*

class DirectoryResource(
    val directory: DbDirectory,
    //private val directoryChildren: List<Resource>,
    private val resourceFactory: StreamableResourceFactory,
    private val localContentsService: LocalContentsService,
    fileService: DatabaseFileService
) : AbstractResource(fileService, directory), MakeCollectionableResource, MoveableResource, PutableResource,
    DeletableResource {

    var directoryChildren: MutableList<Resource>? = null

    override fun getUniqueId(): String {
        return directory.id!!.toString()
    }

    override fun getName(): String {
        return directory.name ?: "/"
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        return true
    }

    override fun getRealm(): String {
        return "realm"
    }

    override fun getModifiedDate(): Date {
        return Date.from(
            Instant.ofEpochMilli(
                directory.lastModified!!
            )
        )
    }

    override fun checkRedirect(request: Request?): String? {
        return null
    }

    override fun delete() {
        fileService.deleteFile(directory)
    }

    /*override fun moveTo(rDest: CollectionResource, name: String) {
        fileService.moveResource(directory, (rDest as DirectoryResource).directory.path!!, name)
    }*/

    override fun isDigestAllowed(): Boolean {
        return true
    }

    override fun getCreateDate(): Date {
        return Date.from(Instant.ofEpochMilli(directory.lastModified!!))
    }

    override fun child(childName: String?): Resource? {
        return children.firstOrNull { it.name == childName }
    }

    override fun getChildren(): List<Resource> {
        return directoryChildren ?: getChildren(directory).toMutableList()
    }

    override fun createNew(newName: String, inputStream: InputStream, length: Long?, contentType: String?): Resource {
        /*fileService.getFileAtPath("${directory.fileSystemPath()}/$newName")
            ?.let {
                when(it) {
                    is RemotelyCachedEntity -> error("Cannot overwrite RemotelyCachedEntity")
                    is DbDirectory -> error("Cannot overwrite Directory")
                    is LocalEntity -> {
                        it.localContents = inputStream.readBytes()
                        fileService.writeDebridFileContentsToFile(it,)
                    }
                }
                if(it ) {

                } else if()
            }*/
        val createdFile = fileService.createLocalFile(
            "${directory.fileSystemPath()}/$newName",
            inputStream,
            length
        )
        directoryChildren?.add(toResource(createdFile)!!)
        return FileResource(createdFile, fileService, localContentsService)
    }

    override fun createCollection(newName: String?): CollectionResource {
        return DirectoryResource(
            fileService.createDirectory("${directory.fileSystemPath()}/$newName/"),
            resourceFactory,
            localContentsService,
            fileService
        )
    }


    private fun getChildren(directory: DbDirectory): List<Resource> = runBlocking {
        fileService.getChildren(directory)
            .toList()
            .map { async { toResource(it) } }
            .awaitAll()
            .filterNotNull()

    }

    private fun toResource(file: DbEntity): Resource? {
        return if (file is DbDirectory)
            DirectoryResource(file, resourceFactory, localContentsService, fileService)
        else resourceFactory.toFileResource(file)
    }
}
