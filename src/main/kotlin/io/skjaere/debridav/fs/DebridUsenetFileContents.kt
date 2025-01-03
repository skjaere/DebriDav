package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.model.DebridFile
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class DebridUsenetFileContents(
    override var id: Long?,
    var type: DebridFileContentsType = DebridFileContentsType.USENET,
    override var originalPath: String,
    override var size: Long,
    override var modified: Long,
    override var debridLinks: MutableList<DebridFile>,
    var debridDownloadId: Long,
    var nzbFileLocation: String,
    var hash: String,
    override var mimeType: String?,
) : DebridFileContents {

    fun getNzb(): File {
        return File(nzbFileLocation)
    }
}

enum class DebridFileContentsType {
    TORRENT, USENET
}
