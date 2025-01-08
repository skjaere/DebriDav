package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.model.DebridFile
import kotlinx.serialization.Serializable

@Serializable(with = DebridFileContentsSerializer::class)
sealed interface DebridFileContents {
    var id: Long?
    var originalPath: String
    var size: Long
    var modified: Long
    var mimeType: String?
    var type: DebridFileType
    var debridLinks: MutableList<DebridFile>

    fun replaceOrAddDebridLink(debridLink: DebridFile) {
        if (debridLinks.any { link -> link.provider == debridLink.provider }) {
            val index = debridLinks.indexOfFirst { link -> link.provider == debridLink.provider }
            debridLinks[index] = debridLink
        } else {
            debridLinks.add(debridLink)
        }
    }
}

enum class DebridProvider { REAL_DEBRID, PREMIUMIZE, TORBOX, EASYNEWS }
enum class DebridFileType { CACHED_USENET, CACHED_TORRENT, USENET_DOWNLOAD }
