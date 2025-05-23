package io.skjaere.debridav.debrid.model

import io.skjaere.debridav.debrid.DebridProvider
import kotlinx.serialization.Serializable

@Serializable
data class CachedFile(
    val path: String,
    val size: Long,
    val mimeType: String,
    val link: String?,
    override val provider: DebridProvider,
    override val lastChecked: Long,
    val params: Map<String, String> = emptyMap()
) : DebridFile {
    override val status: DebridFileType
        get() = DebridFileType.CACHED

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CachedFile

        if (path != other.path) return false
        if (size != other.size) return false
        if (provider != other.provider) return false

        return true
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + provider.hashCode()
        return result
    }
}
