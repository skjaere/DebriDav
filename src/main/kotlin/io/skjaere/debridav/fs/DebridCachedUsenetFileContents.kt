package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.model.DebridFile
import kotlinx.serialization.Serializable

@Serializable
data class DebridCachedUsenetFileContents(
    override var id: Long? = null,
    override var originalPath: String,
    override var size: Long,
    override var modified: Long,
    var releaseName: String,
    override var debridLinks: MutableList<DebridFile>,
    override var mimeType: String? = null,
    override var type: DebridFileType,
) : DebridFileContents {

    override fun equals(other: Any?): Boolean {
        if (other is DebridCachedUsenetFileContents) {
            return originalPath == other.originalPath &&
                    size == other.size &&
                    releaseName == other.releaseName &&
                    debridLinks == other.debridLinks
        }

        return super.equals(other)
    }

    override fun hashCode(): Int {
        var result = originalPath.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + modified.hashCode()
        result = 31 * result + releaseName.hashCode()
        result = 31 * result + debridLinks.hashCode()
        return result
    }
}
