package io.skjaere.debridav.debrid.model

import io.skjaere.debridav.debrid.DebridProvider
import kotlinx.serialization.Serializable

@Serializable
data class ClientError(
    override val provider: DebridProvider,
    override val lastChecked: Long,

    ) : DebridFile {
    override val status = DebridFileType.CLIENT_ERROR
}
