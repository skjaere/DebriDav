package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.model.DebridFile
import kotlinx.serialization.Serializable

@Serializable
data class DebridTorrentFileContents(
    override var id: Long? = null,
    override var originalPath: String,
    override var size: Long,
    override var modified: Long,
    var magnet: String,
    override var debridLinks: MutableList<DebridFile>,
    override var mimeType: String? = null,
) : DebridFileContents {


    override fun equals(other: Any?): Boolean {
        if (other is DebridTorrentFileContents) {
            return originalPath == other.originalPath &&
                    size == other.size &&
                    magnet == other.magnet &&
                    debridLinks == other.debridLinks
        }

        return super.equals(other)
    }

    override fun hashCode(): Int {
        var result = originalPath.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + modified.hashCode()
        result = 31 * result + magnet.hashCode()
        result = 31 * result + debridLinks.hashCode()
        return result
    }
}

enum class DebridProvider { REAL_DEBRID, PREMIUMIZE, TORBOX }
