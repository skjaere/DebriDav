package io.skjaere.debridav.debrid.model

import io.skjaere.debridav.fs.DebridProvider
import kotlinx.serialization.Serializable

@Serializable
data class MissingFile(
    override val provider: DebridProvider,
    override val lastChecked: Long
) : DebridFile {
    override val status: DebridFileType
        get() = DebridFileType.MISSING
}
