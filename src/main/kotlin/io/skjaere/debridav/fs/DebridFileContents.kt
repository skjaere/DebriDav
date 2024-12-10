package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.model.DebridFile
import kotlinx.serialization.Serializable

@Serializable
data class DebridFileContents(
    var originalPath: String,
    var size: Long,
    var modified: Long,
    var magnet: String,
    var debridLinks: MutableList<DebridFile>
) {
    fun replaceOrAddDebridLink(debridLink: DebridFile) {
        if (debridLinks.any { link -> link.provider == debridLink.provider }) {
            val index = debridLinks.indexOfFirst { link -> link.provider == debridLink.provider }
            debridLinks[index] = debridLink
        } else {
            debridLinks.add(debridLink)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other is DebridFileContents) {
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

enum class DebridProvider { REAL_DEBRID, PREMIUMIZE }
