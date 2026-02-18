package io.skjaere.debridav.resource

import io.milton.http.Auth
import io.milton.http.Request
import io.milton.http.http11.auth.DigestResponse
import io.milton.resource.CollectionResource
import io.milton.resource.DigestResource
import io.milton.resource.MoveableResource
import io.milton.resource.PropFindableResource
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbEntity


abstract class AbstractResource(
    val fileService: DatabaseFileService,
    open var dbItem: DbEntity,
    val debridavConfigurationProperties: DebridavConfigurationProperties
) : DigestResource, PropFindableResource, MoveableResource {

    override fun authenticate(user: String, password: String): Any? {
        if (!debridavConfigurationProperties.isWebdavAuthEnabled()) return user
        return if (user == debridavConfigurationProperties.webdavUsername
            && password == debridavConfigurationProperties.webdavPassword
        ) user else null
    }

    override fun authenticate(digestRequest: DigestResponse): Any? {
        if (!debridavConfigurationProperties.isWebdavAuthEnabled()) return digestRequest.user
        return null
    }

    override fun authorise(request: Request?, method: Request.Method?, auth: Auth?): Boolean {
        if (!debridavConfigurationProperties.isWebdavAuthEnabled()) return true
        return auth?.tag != null
    }

    override fun getRealm(): String = "debridav"

    override fun isDigestAllowed(): Boolean = !debridavConfigurationProperties.isWebdavAuthEnabled()

    override fun moveTo(rDest: CollectionResource, name: String) {
        fileService.moveResource(
            dbItem,
            (rDest as DirectoryResource).directory.fileSystemPath()!!,
            name
        )
    }
}
