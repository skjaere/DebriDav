package io.skjaere.debridav.resource

import io.milton.http.http11.auth.DigestResponse
import io.milton.resource.CollectionResource
import io.milton.resource.DigestResource
import io.milton.resource.MoveableResource
import io.milton.resource.PropFindableResource
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity


abstract class AbstractResource(
    val fileService: DatabaseFileService,
    open var dbItem: DbEntity
) : DigestResource, PropFindableResource, MoveableResource {
    override fun authenticate(user: String, requestedPassword: String): Any? {
        return null
    }

    override fun authenticate(digestRequest: DigestResponse): Any? {
        return null
    }

    override fun moveTo(rDest: CollectionResource, name: String) {
        fileService.moveResource(
            dbItem,
            (rDest as DirectoryResource).directory.fileSystemPath()!!,
            name
        )
    }
}
