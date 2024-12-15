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
