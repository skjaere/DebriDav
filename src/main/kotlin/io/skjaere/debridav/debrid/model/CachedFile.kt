package io.skjaere.debridav.debrid.model

import io.skjaere.debridav.fs.DebridProvider
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class CachedFile(
    val path: String,
    val size: Long,
    val mimeType: String,
    val link: String,
    override val provider: DebridProvider,
    override val lastChecked: Long,
    val params: Map<String, String> = emptyMap()
) : DebridFile {
    /*    override val status: DebridFileType
            get() = DebridFileType.CACHED*/

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

    fun withNewLink(link: String): CachedFile {
        return CachedFile(
            path = path,
            size = size,
            mimeType = mimeType,
            link = link,
            provider = provider,
            lastChecked = Instant.now().toEpochMilli(),
        )
    }
}
